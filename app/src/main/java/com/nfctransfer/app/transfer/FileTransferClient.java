package com.nfctransfer.app.transfer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferClient {

    private static final String TAG = "FileTransferClient";
    private static final int BUFFER_SIZE = 65536;
    private static final int RETRY_COUNT = 15;
    // Pixel (stock Android) DHCP negotiation after Wi-Fi Direct group formation
    // can take 4–6 s; Samsung OEM is typically done in ~1 s.  With 15 retries
    // at 500 ms gap we cover up to ~7.5 s of DHCP settle time without increasing
    // the socket connect timeout (which blocks the thread).
    private static final int RETRY_DELAY_MS = 500;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    public interface Callback {
        void onProgressUpdate(String fileName, int percent);
        void onFileSent(String fileName, long fileSize);
        void onAllFilesSent(int count);
        void onError(String fileName, Exception e);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void sendFiles(Context ctx, String serverIp, List<Uri> uris, Callback callback) {
        executor.execute(() -> doSend(ctx.getApplicationContext(), serverIp, uris, callback));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void doSend(Context context, String serverIp, List<Uri> uris, Callback callback) {
        Socket socket = null;
        IOException lastError = null;

        for (int attempt = 0; attempt < RETRY_COUNT; attempt++) {
            Socket s = new Socket();
            try {
                s.connect(new java.net.InetSocketAddress(serverIp, FileTransferServer.PORT),
                        SOCKET_TIMEOUT_MS);
                socket = s;
                break;
            } catch (IOException e) {
                lastError = e;
                try { s.close(); } catch (IOException ignored) {} // prevent fd leak
                Log.w(TAG, "Socket attempt " + (attempt + 1) + "/" + RETRY_COUNT + " failed");
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }

        if (socket == null) {
            final IOException err = lastError;
            mainHandler.post(() -> {
                if (callback != null) callback.onError(null, err);
            });
            return;
        }

        try (Socket s = socket;
             OutputStream raw = s.getOutputStream()) {

            DataOutputStream out = new DataOutputStream(raw);

            int totalFiles = uris.size();
            out.writeInt(totalFiles);

            for (Uri uri : uris) {
                String fileName = resolveFileName(context, uri);
                long fileSize = resolveFileSize(context, uri);

                byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.writeLong(fileSize);

                Log.d(TAG, "Sending: " + fileName + " (" + fileSize + " bytes)");

                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    long sent = 0;
                    int read;

                    while ((read = input.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        sent += read;
                        if (fileSize > 0) {
                            final int percent = (int) (sent * 100L / fileSize);
                            final String fn = fileName;
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgressUpdate(fn, percent);
                            });
                        }
                    }
                }

                out.flush();
                Log.d(TAG, "Sent: " + fileName);
                final String fn = fileName;
                final long fs = fileSize;
                mainHandler.post(() -> {
                    if (callback != null) callback.onFileSent(fn, fs);
                });
            }

            final int total = totalFiles;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllFilesSent(total);
            });

        } catch (IOException e) {
            Log.e(TAG, "Error sending file", e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError(null, e);
            });
        }
    }

    private String resolveFileName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file_" + System.currentTimeMillis();
    }

    private long resolveFileSize(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
