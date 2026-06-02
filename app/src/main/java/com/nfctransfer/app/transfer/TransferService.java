package com.nfctransfer.app.transfer;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.nfctransfer.app.R;
import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.wifi.WifiDirectManager;

import java.util.ArrayList;
import java.util.List;

public class TransferService extends Service {

    private static final String TAG = "TransferService";

    public static final String ACTION_SEND = "ACTION_SEND";
    public static final String ACTION_RECEIVE = "ACTION_RECEIVE";
    public static final String EXTRA_TARGET_IP = "EXTRA_TARGET_IP";
    public static final String EXTRA_FILE_URIS = "EXTRA_FILE_URIS";

    public static final String BROADCAST_PROGRESS = "com.nfctransfer.PROGRESS";
    public static final String BROADCAST_COMPLETE = "com.nfctransfer.COMPLETE";
    public static final String BROADCAST_ERROR = "com.nfctransfer.ERROR";
    public static final String EXTRA_FILE_NAME = "fileName";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_FILE_COUNT = "fileCount";
    public static final String EXTRA_IS_SEND = "isSend";

    private static final int FOREGROUND_ID = 2001;

    private FileTransferServer server;
    private FileTransferClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        TransferNotificationHelper.createNotificationChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(FOREGROUND_ID, buildForegroundNotification());

        String action = intent.getAction();
        if (ACTION_SEND.equals(action)) {
            String targetIp = intent.getStringExtra(EXTRA_TARGET_IP);
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_FILE_URIS);
            if (targetIp != null && uris != null && !uris.isEmpty()) {
                startSend(targetIp, uris);
            } else {
                Log.w(TAG, "ACTION_SEND missing required extras");
                stopSelf();
            }
        } else if (ACTION_RECEIVE.equals(action)) {
            startReceive();
        } else {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startSend(String targetIp, List<Uri> uris) {
        client = new FileTransferClient();
        client.sendFiles(this, targetIp, uris, new FileTransferClient.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                TransferNotificationHelper.showProgressNotification(
                        TransferService.this, fileName, percent);
                Intent broadcast = new Intent(BROADCAST_PROGRESS);
                broadcast.putExtra(EXTRA_FILE_NAME, fileName);
                broadcast.putExtra(EXTRA_PERCENT, percent);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
            }

            @Override
            public void onFileSent(String fileName, long fileSize) {
                HistoryRepository repo = new HistoryRepository(TransferService.this);
                repo.insert(new TransferRecord(fileName, fileSize, null,
                        System.currentTimeMillis(), "SENT", "SUCCESS", null));
            }

            @Override
            public void onAllFilesSent(int totalCount) {
                TransferNotificationHelper.showCompletionNotification(
                        TransferService.this, totalCount, true);
                Intent broadcast = new Intent(BROADCAST_COMPLETE);
                broadcast.putExtra(EXTRA_FILE_COUNT, totalCount);
                broadcast.putExtra(EXTRA_IS_SEND, true);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
                stopSelf();
            }

            @Override
            public void onError(String fileName, Exception e) {
                Log.e(TAG, "Send error for " + fileName, e);
                TransferNotificationHelper.showErrorNotification(TransferService.this, fileName);
                if (fileName != null) {
                    HistoryRepository repo = new HistoryRepository(TransferService.this);
                    repo.insert(new TransferRecord(fileName, -1, null,
                            System.currentTimeMillis(), "SENT", "FAILED", null));
                }
                Intent broadcast = new Intent(BROADCAST_ERROR);
                broadcast.putExtra(EXTRA_FILE_NAME, fileName);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
            }
        });
    }

    private void startReceive() {
        server = new FileTransferServer();
        server.start(this, new FileTransferServer.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                TransferNotificationHelper.showProgressNotification(
                        TransferService.this, fileName, percent);
                Intent broadcast = new Intent(BROADCAST_PROGRESS);
                broadcast.putExtra(EXTRA_FILE_NAME, fileName);
                broadcast.putExtra(EXTRA_PERCENT, percent);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
            }

            @Override
            public void onFileReceived(String fileName, String filePath, long fileSize) {
                HistoryRepository repo = new HistoryRepository(TransferService.this);
                repo.insert(new TransferRecord(fileName, fileSize, filePath,
                        System.currentTimeMillis(), "RECEIVED", "SUCCESS", null));
            }

            @Override
            public void onAllFilesReceived(int totalCount) {
                TransferNotificationHelper.showCompletionNotification(
                        TransferService.this, totalCount, false);
                Intent broadcast = new Intent(BROADCAST_COMPLETE);
                broadcast.putExtra(EXTRA_FILE_COUNT, totalCount);
                broadcast.putExtra(EXTRA_IS_SEND, false);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
                stopSelf();
            }

            @Override
            public void onError(String fileName, Exception e) {
                Log.e(TAG, "Receive error", e);
                TransferNotificationHelper.showErrorNotification(TransferService.this, fileName);
                Intent broadcast = new Intent(BROADCAST_ERROR);
                broadcast.putExtra(EXTRA_FILE_NAME, fileName);
                LocalBroadcastManager.getInstance(TransferService.this).sendBroadcast(broadcast);
            }
        });
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, TransferNotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("NFC Transfer")
                .setContentText("傳輸進行中...")
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
        if (client != null) client.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
