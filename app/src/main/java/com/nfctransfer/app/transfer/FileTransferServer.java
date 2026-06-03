package com.nfctransfer.app.transfer;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferServer {

    private static final String TAG = "FileTransferServer";
    public static final int PORT = 8888;
    public static final int BUFFER_SIZE = 65536;

    public interface Callback {
        void onProgressUpdate(String fileName, int percent);
        void onFileReceived(String fileName, String filePath, long fileSize);
        void onAllFilesReceived(int count);
        void onError(String fileName, Exception e);
    }

    private volatile ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    public void start(Context ctx, Callback callback) {
        running = true;
        executor.execute(() -> acceptAndReceive(ctx.getApplicationContext(), callback));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing server socket", e);
        }
        executor.shutdownNow();
    }

    private void acceptAndReceive(Context context, Callback callback) {
        // Retry bind with SO_REUSEADDR to survive TIME_WAIT from previous session
        IOException bindError = null;
        for (int attempt = 0; attempt < 5 && running; attempt++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                serverSocket = ss;
                bindError = null;
                break;
            } catch (IOException e) {
                bindError = e;
                Log.w(TAG, "Bind attempt " + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt < 4 && running) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }

        if (bindError != null || serverSocket == null) {
            if (running) {
                final IOException err = bindError;
                mainHandler.post(() -> { if (callback != null) callback.onError(null, err); });
            }
            return;
        }

        Log.d(TAG, "Listening on port " + PORT);
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Log.d(TAG, "Client connected: " + client.getInetAddress());
                handleClient(context, client, callback);
            } catch (SocketException e) {
                break; // normal shutdown via serverSocket.close()
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Accept error", e);
                    mainHandler.post(() -> { if (callback != null) callback.onError(null, e); });
                }
                break;
            }
        }
    }

    private void handleClient(Context context, Socket client, Callback callback) {
        File saveDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NFC Transfer");
        saveDir.mkdirs();

        try (Socket s = client;
             InputStream raw = s.getInputStream()) {

            DataInputStream in = new DataInputStream(raw);

            int totalFiles = in.readInt();
            Log.d(TAG, "Expecting " + totalFiles + " file(s)");

            for (int i = 0; i < totalFiles; i++) {
                int nameLen = in.readInt();
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                String fileName = new String(nameBytes, StandardCharsets.UTF_8);
                long fileSize = in.readLong();

                Log.d(TAG, "Receiving: " + fileName + " (" + fileSize + " bytes)");

                File outFile = new File(saveDir, fileName);
                String filePath = outFile.getAbsolutePath();

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    long received = 0;
                    int read;

                    while ((fileSize < 0 || received < fileSize) &&
                            (read = in.read(buf, 0, fileSize < 0 ? buf.length
                                    : (int) Math.min(buf.length, fileSize - received))) != -1) {
                        fos.write(buf, 0, read);
                        received += read;
                        final int percent = (fileSize > 0) ? (int) (received * 100L / fileSize) : -1;
                        final String fn = fileName;
                        if (percent >= 0) {
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgressUpdate(fn, percent);
                            });
                        }
                    }
                }

                Log.d(TAG, "File saved: " + filePath);
                final String fn = fileName;
                final long fs = fileSize;
                final String fp = filePath;
                mainHandler.post(() -> {
                    if (callback != null) callback.onFileReceived(fn, fp, fs);
                });
            }

            final int total = totalFiles;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllFilesReceived(total);
            });

        } catch (IOException e) {
            Log.e(TAG, "Error receiving file", e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError(null, e);
            });
        }
    }
}
