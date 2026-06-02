package com.nfctransfer.app.transfer;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.nfctransfer.app.R;
import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.nfc.NfcHceService;
import com.nfctransfer.app.wifi.WifiDirectManager;

public class ReceiverForegroundService extends Service {

    private static final String TAG = "ReceiverFgService";

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";

    public static final String BROADCAST_READY = "com.nfctransfer.RECEIVER_READY";
    public static final String BROADCAST_PROGRESS = "com.nfctransfer.PROGRESS";
    public static final String BROADCAST_COMPLETE = "com.nfctransfer.COMPLETE";
    public static final String BROADCAST_ERROR = "com.nfctransfer.ERROR";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_PASS = "pass";
    public static final String EXTRA_FILE_NAME = "fileName";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_FILE_COUNT = "fileCount";

    private static final int FOREGROUND_ID = 3001;

    private WifiDirectManager wifiDirectManager;
    private FileTransferServer fileServer;
    private boolean receiverRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        TransferNotificationHelper.createNotificationChannel(this);
        wifiDirectManager = WifiDirectManager.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(FOREGROUND_ID, buildNotification("初始化中..."));
            registerWifiReceiver();
            startGroupAndServer();
        } else if (ACTION_STOP.equals(action)) {
            stopReceiving();
        }

        return START_NOT_STICKY;
    }

    private void registerWifiReceiver() {
        if (!receiverRegistered) {
            wifiDirectManager.registerReceiver((Context) this);
            receiverRegistered = true;
        }
    }

    private void unregisterWifiReceiver() {
        if (receiverRegistered) {
            wifiDirectManager.unregisterReceiver((Context) this);
            receiverRegistered = false;
        }
    }

    private void startGroupAndServer() {
        wifiDirectManager.createGroup(new WifiDirectManager.GroupInfoCallback() {
            @Override
            public void onGroupCreated(String ssid, String passphrase) {
                Log.d(TAG, "Group created: ssid=" + ssid);

                NfcHceService.setCredentials(ssid, passphrase);
                startService(new Intent(ReceiverForegroundService.this, NfcHceService.class));

                startFileServer();

                updateNotification("等待接收中...");

                Intent broadcast = new Intent(BROADCAST_READY);
                broadcast.putExtra(EXTRA_SSID, ssid);
                broadcast.putExtra(EXTRA_PASS, passphrase);
                LocalBroadcastManager.getInstance(ReceiverForegroundService.this)
                        .sendBroadcast(broadcast);
            }

            @Override
            public void onGroupCreationFailed(String reason) {
                Log.e(TAG, "Group creation failed: " + reason);
                updateNotification("初始化失敗: " + reason);

                Intent broadcast = new Intent(BROADCAST_ERROR);
                broadcast.putExtra(EXTRA_FILE_NAME, reason);
                LocalBroadcastManager.getInstance(ReceiverForegroundService.this)
                        .sendBroadcast(broadcast);
            }
        });
    }

    private void startFileServer() {
        fileServer = new FileTransferServer();
        fileServer.start(this, new FileTransferServer.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                updateNotification("接收中: " + fileName + " " + percent + "%");
                TransferNotificationHelper.showProgressNotification(
                        ReceiverForegroundService.this, fileName, percent);

                Intent broadcast = new Intent(BROADCAST_PROGRESS);
                broadcast.putExtra(EXTRA_FILE_NAME, fileName);
                broadcast.putExtra(EXTRA_PERCENT, percent);
                LocalBroadcastManager.getInstance(ReceiverForegroundService.this)
                        .sendBroadcast(broadcast);
            }

            @Override
            public void onFileReceived(String fileName, String filePath, long fileSize) {
                Log.d(TAG, "File received: " + fileName);
                HistoryRepository repo = new HistoryRepository(ReceiverForegroundService.this);
                repo.insert(new TransferRecord(fileName, fileSize, filePath,
                        System.currentTimeMillis(), "RECEIVED", "SUCCESS", null));
            }

            @Override
            public void onAllFilesReceived(int count) {
                TransferNotificationHelper.showCompletionNotification(
                        ReceiverForegroundService.this, count, false);
                updateNotification("等待接收中...");

                Intent broadcast = new Intent(BROADCAST_COMPLETE);
                broadcast.putExtra(EXTRA_FILE_COUNT, count);
                LocalBroadcastManager.getInstance(ReceiverForegroundService.this)
                        .sendBroadcast(broadcast);
            }

            @Override
            public void onError(String fileName, Exception e) {
                Log.e(TAG, "Server error for " + fileName, e);
                String msg = e != null ? e.getMessage() : "未知錯誤";

                Intent broadcast = new Intent(BROADCAST_ERROR);
                broadcast.putExtra(EXTRA_FILE_NAME, msg);
                LocalBroadcastManager.getInstance(ReceiverForegroundService.this)
                        .sendBroadcast(broadcast);
            }
        });
    }

    private void stopReceiving() {
        NfcHceService.clearCredentials();
        stopService(new Intent(this, NfcHceService.class));

        wifiDirectManager.removeGroup();

        if (fileServer != null) {
            fileServer.stop();
            fileServer = null;
        }

        unregisterWifiReceiver();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fileServer != null) {
            fileServer.stop();
            fileServer = null;
        }
        NfcHceService.clearCredentials();
        unregisterWifiReceiver();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification(String text) {
        Notification notification = buildNotification(text);
        NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notification);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, TransferNotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("NFC Transfer — 接收模式")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }
}
