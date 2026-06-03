package com.nfctransfer.app.transfer;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
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

    // Static cache so ReceiveActivity can recover credentials after rotation or
    // re-resume without needing to wait for another BROADCAST_READY.
    private static volatile String cachedSsid = null;
    private static volatile String cachedPass = null;

    /** Returns the SSID last broadcast by this service, or null if not ready. */
    public static String getCachedSsid() { return cachedSsid; }

    /** Returns the passphrase last broadcast by this service, or null if not ready. */
    public static String getCachedPass() { return cachedPass; }

    /** True when the service has a live group (credentials are valid). */
    public static volatile boolean isReady = false;

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_ID, buildNotification("初始化中..."),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_ID, buildNotification("初始化中..."));
            }
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
        // Guard against double-start: stop old server so port 8888 is freed
        if (fileServer != null) {
            fileServer.stop();
            fileServer = null;
        }
        wifiDirectManager.createGroup(new WifiDirectManager.GroupInfoCallback() {
            @Override
            public void onGroupCreated(String ssid, String passphrase) {
                Log.d(TAG, "Group created: ssid=" + ssid);

                // Cache credentials so ReceiveActivity can recover them after rotation
                cachedSsid = ssid;
                cachedPass = passphrase;
                isReady = true;

                NfcHceService.setCredentials(ssid, passphrase);
                wifiDirectManager.warmUpRadio();

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

        // Clear the static credential cache so ReceiveActivity knows the service stopped
        cachedSsid = null;
        cachedPass = null;
        isReady = false;

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
        cachedSsid = null;
        cachedPass = null;
        isReady = false;
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
