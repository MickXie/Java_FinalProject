package com.nfctransfer.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.transfer.FileTransferClient;
import com.nfctransfer.app.transfer.ReceiverForegroundService;
import com.nfctransfer.app.transfer.TransferNotificationHelper;
import com.nfctransfer.app.util.PermissionHelper;
import com.nfctransfer.app.util.QrCodeHelper;
import com.nfctransfer.app.wifi.WifiDirectManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int QR_SCAN_REQUEST = 1002;

    // NFC 相關 APDU 指令
    private static final byte[] SELECT_AID_APDU = {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x08,
            (byte) 0xF0, 0x4E, 0x46, 0x43, 0x54, 0x52, 0x01, 0x01, 0x00
    };
    private static final byte[] READ_APDU = {0x00, (byte) 0xB0, 0x00, 0x00, 0x00};

    // UI 元件
    private CardView bottomSheet;
    private TextView tvSheetTitle;
    private BottomSheetBehavior<CardView> bottomSheetBehavior;

    private LinearLayout layoutSendAction, layoutReceiveAction, layoutProgress;
    private Button btnPickFile, btnScanQr;
    private TextView tvSelectedFile, tvProgressFilename, tvProgressPercent;
    private ProgressBar progressBar;
    private ImageView ivQrCode;

    // 狀態管理
    private enum Mode { IDLE, SENDING, RECEIVING }
    private Mode currentMode = Mode.IDLE;

    // 傳送方物件
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private final ExecutorService nfcExecutor = Executors.newSingleThreadExecutor();
    private final List<Uri> selectedUris = new ArrayList<>();
    private WifiDirectManager wifiDirectManager;
    private FileTransferClient fileTransferClient;
    private boolean waitingForNfc = false;

    // 接收方物件
    private String currentSsid = null;
    private String currentPass = null;
    private BroadcastReceiver serviceReceiver;

    // 權限請求啟動器
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                updateNfcStatus();
            });

    // 檔案選取啟動器
    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedUris.clear();
                    selectedUris.addAll(uris);
                    tvSelectedFile.setText("已選擇 " + uris.size() + " 個檔案");
                    btnScanQr.setEnabled(true);
                    tvSheetTitle.setText("請將手機靠近接收方，或點擊掃描 QR Code");
                    waitingForNfc = true;
                    enableNfcForegroundDispatch();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initReceiversAndManagers();
        setupClickListeners();
        setupBottomSheetBehavior();

        if (savedInstanceState == null) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions());
        }
    }

    private void initViews() {
        bottomSheet = findViewById(R.id.bottom_sheet);
        tvSheetTitle = findViewById(R.id.tv_sheet_title);
        layoutSendAction = findViewById(R.id.layout_send_action);
        layoutReceiveAction = findViewById(R.id.layout_receive_action);
        layoutProgress = findViewById(R.id.layout_progress);

        btnPickFile = findViewById(R.id.btn_pick_file);
        btnScanQr = findViewById(R.id.btn_scan_qr);
        tvSelectedFile = findViewById(R.id.tv_selected_file);

        ivQrCode = findViewById(R.id.iv_qr_code);

        tvProgressFilename = findViewById(R.id.tv_progress_filename);
        tvProgressPercent = findViewById(R.id.tv_progress_percent);
        progressBar = findViewById(R.id.progress_bar);

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
    }

    private void initReceiversAndManagers() {
        TransferNotificationHelper.createNotificationChannel(this);
        wifiDirectManager = WifiDirectManager.getInstance(this);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcPendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);

        // 初始化接收端的背景服務廣播監聽器
        serviceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                switch (intent.getAction()) {
                    case ReceiverForegroundService.BROADCAST_READY:
                        currentSsid = intent.getStringExtra(ReceiverForegroundService.EXTRA_SSID);
                        currentPass = intent.getStringExtra(ReceiverForegroundService.EXTRA_PASS);
                        tvSheetTitle.setText("請用傳送端手機掃描 QR Code 連線");

                        // 關鍵點：收到 Ready 廣播後自動生成並直接顯示 QR Code
                        if (currentSsid != null && currentPass != null) {
                            Bitmap qr = QrCodeHelper.generateQrCode(currentSsid, currentPass, 512);
                            if (qr != null) {
                                Glide.with(MainActivity.this).load(qr).into(ivQrCode);
                                ivQrCode.setVisibility(View.VISIBLE);
                            }
                        }
                        break;
                    case ReceiverForegroundService.BROADCAST_PROGRESS:
                        String fileName = intent.getStringExtra(ReceiverForegroundService.EXTRA_FILE_NAME);
                        int percent = intent.getIntExtra(ReceiverForegroundService.EXTRA_PERCENT, 0);

                        layoutReceiveAction.setVisibility(View.GONE);
                        layoutProgress.setVisibility(View.VISIBLE);
                        progressBar.setProgress(percent);
                        tvProgressPercent.setText(percent + "%");
                        tvProgressFilename.setText(fileName != null ? fileName : "接收中...");
                        tvSheetTitle.setText("正在接收檔案...");
                        break;
                    case ReceiverForegroundService.BROADCAST_COMPLETE:
                        int count = intent.getIntExtra(ReceiverForegroundService.EXTRA_FILE_COUNT, 0);
                        tvSheetTitle.setText("接收完成！共 " + count + " 個檔案");
                        progressBar.setProgress(100);
                        tvProgressPercent.setText("100%");
                        Toast.makeText(MainActivity.this, "接收完成", Toast.LENGTH_SHORT).show();
                        break;
                    case ReceiverForegroundService.BROADCAST_ERROR:
                        String error = intent.getStringExtra(ReceiverForegroundService.EXTRA_FILE_NAME);
                        tvSheetTitle.setText("接收失敗: " + (error != null ? error : "未知錯誤"));
                        break;
                }
            }
        };
    }

    private void setupClickListeners() {
        findViewById(R.id.btn_send_card).setOnClickListener(v -> startSendFlow());
        findViewById(R.id.btn_receive_card).setOnClickListener(v -> startReceiveFlow());
        findViewById(R.id.btn_history_card).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btn_profile_card).setOnClickListener(v ->
                Toast.makeText(this, "個人主頁與設定功能即將推出", Toast.LENGTH_SHORT).show());

        btnPickFile.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));
        btnScanQr.setOnClickListener(v -> QrCodeHelper.startQrScanner(this, QR_SCAN_REQUEST));
    }

    private void setupBottomSheetBehavior() {
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                // 當使用者將 BottomSheet 向下滑動收合或隱藏時，自動斷開 P2P 網路並重置所有狀態
                if (newState == BottomSheetBehavior.STATE_HIDDEN || newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    resetAllStates();
                }
            }
            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });
    }

    // --- 傳送邏輯流程 ---
    private void startSendFlow() {
        currentMode = Mode.SENDING;
        selectedUris.clear();
        waitingForNfc = false;

        tvSheetTitle.setText("準備傳送：請先選擇檔案");
        tvSelectedFile.setText("尚未選擇檔案");
        btnScanQr.setEnabled(false);

        layoutSendAction.setVisibility(View.VISIBLE);
        layoutReceiveAction.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.GONE);

        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    // --- 接收邏輯流程 ---
    private void startReceiveFlow() {
        // 嚴格權限檢查，確保取得相關位置與硬體權限，避免 P2P 核心報 group info null 錯誤
        List<String> missing = PermissionHelper.getMissingPermissions(this);
        if (!missing.isEmpty()) {
            Toast.makeText(this, "請先允許相關權限才能建立接收端", Toast.LENGTH_LONG).show();
            permissionLauncher.launch(missing.toArray(new String[0]));
            return;
        }

        currentMode = Mode.RECEIVING;
        currentSsid = null;
        currentPass = null;

        tvSheetTitle.setText("準備接收：初始化網路...");
        if (ivQrCode != null) {
            ivQrCode.setVisibility(View.GONE);
        }

        layoutReceiveAction.setVisibility(View.VISIBLE);
        layoutSendAction.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.GONE);

        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        // 啟動背景接收服務 (Wi-Fi Direct Group Create)
        Intent intent = new Intent(this, ReceiverForegroundService.class);
        intent.setAction(ReceiverForegroundService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
    }

    // --- 重置與資源釋放機制 ---
    private void resetAllStates() {
        currentMode = Mode.IDLE;
        waitingForNfc = false;

        if (ivQrCode != null) {
            ivQrCode.setVisibility(View.GONE);
        }

        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }

        if (fileTransferClient != null) {
            fileTransferClient.shutdown();
            fileTransferClient = null;
        }
        wifiDirectManager.disconnect();

        // 停止背景接收服務
        Intent intent = new Intent(this, ReceiverForegroundService.class);
        intent.setAction(ReceiverForegroundService.ACTION_STOP);
        startService(intent);
    }

    // --- 生命週期與系統回調管理 ---
    @Override
    protected void onResume() {
        super.onResume();
        updateNfcStatus();
        wifiDirectManager.registerReceiver(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ReceiverForegroundService.BROADCAST_READY);
        filter.addAction(ReceiverForegroundService.BROADCAST_PROGRESS);
        filter.addAction(ReceiverForegroundService.BROADCAST_COMPLETE);
        filter.addAction(ReceiverForegroundService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, filter);

        if (waitingForNfc && currentMode == Mode.SENDING) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiDirectManager.unregisterReceiver(this);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver);
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetAllStates();
        nfcExecutor.shutdownNow();
    }

    // 處理 QR Code 掃描完成的回調
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != QR_SCAN_REQUEST || resultCode != RESULT_OK || data == null) return;

        String qrContent = data.getStringExtra("SCAN_RESULT");
        if (qrContent == null) return;

        String[] creds = QrCodeHelper.parseQrResult(qrContent);
        if (creds == null) {
            Toast.makeText(this, "QR Code 格式錯誤", Toast.LENGTH_SHORT).show();
            return;
        }

        executeConnectionAndSend(creds[0], creds[1]);
    }

    // 處理 NFC 感應靠近時的回調
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (currentMode != Mode.SENDING || intent == null) return;

        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action) && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return;

        waitingForNfc = false;
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);

        nfcExecutor.execute(() -> readCredentialsAndConnect(isoDep));
    }

    // --- 內部輔助方法 ---
    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        IntentFilter isoDepFilter = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        IntentFilter[] filters = {isoDepFilter};
        String[][] techLists = {{IsoDep.class.getName()}};
        nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, filters, techLists);
    }

    private void updateNfcStatus() {
        if (!PermissionHelper.isNfcEnabled(this)) {
            if (currentMode == Mode.IDLE) {
                tvSheetTitle.setText("NFC 未開啟 — 點此前往設定");
                tvSheetTitle.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                tvSheetTitle.setOnClickListener(v -> PermissionHelper.showNfcEnableDialog(this));
            }
        } else {
            tvSheetTitle.setTextColor(getResources().getColor(R.color.text_dark));
            tvSheetTitle.setOnClickListener(null);
        }
    }

    // 於子執行緒處理 NFC 連線與憑證讀取
    private void readCredentialsAndConnect(IsoDep isoDep) {
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);

            byte[] selectResponse = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                selectResponse = isoDep.transceive(SELECT_AID_APDU);
                if (isSuccess(selectResponse)) break;
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (!isSuccess(selectResponse)) {
                runOnUiThread(() -> showNfcError("傳送方尚未準備好，請確認接收方已開始接收"));
                return;
            }

            byte[] readResponse = isoDep.transceive(READ_APDU);
            if (!isSuccess(readResponse)) {
                runOnUiThread(() -> showNfcError("憑證讀取失敗，請重新靠近"));
                return;
            }

            int payloadLen = readResponse.length - 2;
            String json = new String(readResponse, 0, payloadLen, java.nio.charset.StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);

            // 修正處：先在原本執行緒將變數提取出來，避免 Lambda 內部拋出 JSONException 造成編譯錯誤
            String ssid = obj.getString("ssid");
            String pass = obj.getString("pass");

            runOnUiThread(() -> executeConnectionAndSend(ssid, pass));

        } catch (Exception e) {
            runOnUiThread(() -> showNfcError("錯誤: " + e.getMessage()));
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    private boolean isSuccess(byte[] response) {
        return response != null && response.length >= 2 &&
                response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == (byte) 0x00;
    }

    private void showNfcError(String msg) {
        tvSheetTitle.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        waitingForNfc = true;
        enableNfcForegroundDispatch();
    }

    // --- Wi-Fi Direct 連線建立與實際傳檔 ---
    private void executeConnectionAndSend(String ssid, String pass) {
        layoutSendAction.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        tvSheetTitle.setText("已取得憑證，連線中...");
        progressBar.setProgress(0);
        tvProgressPercent.setText("0%");
        tvProgressFilename.setText("準備傳輸");

        wifiDirectManager.connectToGroup(ssid, pass, new WifiDirectManager.ConnectionCallback() {
            @Override
            public void onConnected(String peerIpAddress) {
                tvSheetTitle.setText("已連線，傳送中...");
                sendFiles(WifiDirectManager.GROUP_OWNER_IP);
            }

            @Override
            public void onConnectionFailed(String reason) {
                tvSheetTitle.setText("連線失敗: " + reason);
                Toast.makeText(MainActivity.this, "Wi-Fi Direct 連線失敗", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDisconnected() {
                tvSheetTitle.setText("連線已中斷");
            }
        });
    }

    private void sendFiles(String serverIp) {
        fileTransferClient = new FileTransferClient();
        fileTransferClient.sendFiles(this, serverIp, selectedUris, new FileTransferClient.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    tvProgressPercent.setText(percent + "%");
                    tvProgressFilename.setText(fileName);
                    tvSheetTitle.setText("傳送中...");
                    TransferNotificationHelper.showProgressNotification(MainActivity.this, fileName, percent);
                });
            }

            @Override
            public void onFileSent(String fileName, long fileSize) {
                runOnUiThread(() -> {
                    HistoryRepository repo = new HistoryRepository(MainActivity.this);
                    repo.insert(new TransferRecord(fileName, fileSize, null,
                            System.currentTimeMillis(), "SENT", "SUCCESS", null));
                });
            }

            @Override
            public void onAllFilesSent(int count) {
                runOnUiThread(() -> {
                    tvSheetTitle.setText("傳送完成！共 " + count + " 個檔案");
                    progressBar.setProgress(100);
                    tvProgressPercent.setText("100%");
                    TransferNotificationHelper.showCompletionNotification(MainActivity.this, count, true);
                    Toast.makeText(MainActivity.this, "全部傳送完成", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String fileName, Exception e) {
                runOnUiThread(() -> {
                    tvSheetTitle.setText("傳送發生錯誤");
                    Toast.makeText(MainActivity.this, "錯誤：" + (e != null ? e.getMessage() : "未知"), Toast.LENGTH_LONG).show();
                    TransferNotificationHelper.showErrorNotification(MainActivity.this, fileName);
                });
            }
        });
    }
}