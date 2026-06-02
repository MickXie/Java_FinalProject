package com.nfctransfer.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nfctransfer.app.data.HistoryRepository;
import com.nfctransfer.app.data.TransferRecord;
import com.nfctransfer.app.transfer.FileTransferClient;
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

public class SendActivity extends AppCompatActivity {

    private static final String TAG = "SendActivity";
    private static final int QR_SCAN_REQUEST = 1002;

    private static final byte[] SELECT_AID_APDU = {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x08,
            (byte) 0xF0, 0x4E, 0x46, 0x43, 0x54, 0x52, 0x01, 0x01, 0x00
    };
    private static final byte[] READ_APDU = {0x00, (byte) 0xB0, 0x00, 0x00, 0x00};

    private Button btnPickFile;
    private Button btnStartSend;
    private Button btnShowQr;
    private ProgressBar progressSend;
    private RecyclerView rvFiles;
    private TextView tvNfcHint;

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private boolean waitingForNfc = false;
    private final ExecutorService nfcExecutor = Executors.newSingleThreadExecutor();

    private final List<Uri> selectedUris = new ArrayList<>();
    private WifiDirectManager wifiDirectManager;
    private FileTransferClient fileTransferClient;
    private boolean sending = false;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    selectedUris.clear();
                    selectedUris.addAll(uris);
                    btnStartSend.setEnabled(true);
                    Toast.makeText(this, "已選擇 " + uris.size() + " 個檔案", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                List<String> missing = PermissionHelper.getMissingPermissions(this);
                if (missing.isEmpty()) {
                    startSendFlow();
                } else {
                    Toast.makeText(this, "需要權限才能傳送檔案", Toast.LENGTH_SHORT).show();
                    progressSend.setVisibility(View.GONE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        btnPickFile  = findViewById(R.id.btn_pick_file);
        btnStartSend = findViewById(R.id.btn_start_send);
        btnShowQr    = findViewById(R.id.btn_show_qr);
        progressSend = findViewById(R.id.progress_send);
        rvFiles      = findViewById(R.id.rv_files);
        tvNfcHint    = findViewById(R.id.tv_nfc_hint);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));

        wifiDirectManager = WifiDirectManager.getInstance(this);
        TransferNotificationHelper.createNotificationChannel(this);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        nfcPendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);

        btnPickFile.setOnClickListener(v ->
                filePickerLauncher.launch(new String[]{"*/*"}));

        btnStartSend.setOnClickListener(v -> {
            if (!sending) {
                startSendFlow();
            } else {
                stopSendMode();
            }
        });

        btnShowQr.setOnClickListener(v ->
                QrCodeHelper.startQrScanner(this, QR_SCAN_REQUEST));
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifiDirectManager.registerReceiver(this);
        if (waitingForNfc) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiDirectManager.unregisterReceiver(this);
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nfcExecutor.shutdownNow();
        wifiDirectManager.disconnect();
        if (fileTransferClient != null) {
            fileTransferClient.shutdown();
        }
    }

    private void startSendFlow() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "請先選擇檔案", Toast.LENGTH_SHORT).show();
            return;
        }

        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Toast.makeText(this, "請先開啟 NFC", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> missing = PermissionHelper.getMissingPermissions(this);
        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
            return;
        }

        sending = true;
        waitingForNfc = true;
        btnStartSend.setText("停止傳送");
        progressSend.setVisibility(View.VISIBLE);
        progressSend.setProgress(0);
        tvNfcHint.setText("請靠近接收方手機...");

        enableNfcForegroundDispatch();
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        IntentFilter isoDepFilter = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        IntentFilter[] filters = {isoDepFilter};
        String[][] techLists = {{IsoDep.class.getName()}};
        nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, filters, techLists);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            Toast.makeText(this, "不支援的 NFC 標籤類型", Toast.LENGTH_SHORT).show();
            return;
        }

        waitingForNfc = false;
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }

        nfcExecutor.execute(() -> readCredentialsAndConnect(isoDep));
    }

    private void readCredentialsAndConnect(IsoDep isoDep) {
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);

            byte[] selectResponse = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                selectResponse = isoDep.transceive(SELECT_AID_APDU);
                if (isSuccess(selectResponse)) break;
                if (attempt < 2) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
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
            if (payloadLen <= 0) {
                runOnUiThread(() -> showNfcError("回應資料為空"));
                return;
            }
            String json = new String(readResponse, 0, payloadLen, java.nio.charset.StandardCharsets.UTF_8);
            Log.d(TAG, "NFC JSON: " + json);

            JSONObject obj = new JSONObject(json);
            String ssid = obj.getString("ssid");
            String pass = obj.getString("pass");

            runOnUiThread(() -> {
                tvNfcHint.setText("已取得憑證，連線中...");
                connectAndSend(ssid, pass);
            });

        } catch (IOException e) {
            Log.e(TAG, "NFC IO error", e);
            runOnUiThread(() -> showNfcError("NFC 通訊失敗: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "NFC error", e);
            runOnUiThread(() -> showNfcError("錯誤: " + e.getMessage()));
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    private boolean isSuccess(byte[] response) {
        if (response == null || response.length < 2) return false;
        return response[response.length - 2] == (byte) 0x90
                && response[response.length - 1] == (byte) 0x00;
    }

    private void showNfcError(String msg) {
        tvNfcHint.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        waitingForNfc = true;
        enableNfcForegroundDispatch();
    }

    private void connectAndSend(String ssid, String pass) {
        wifiDirectManager.connectToGroup(ssid, pass, new WifiDirectManager.ConnectionCallback() {
            @Override
            public void onConnected(String peerIpAddress) {
                Log.d(TAG, "Connected to group, GO IP=" + peerIpAddress);
                tvNfcHint.setText("已連線，傳送中...");
                sendFiles(WifiDirectManager.GROUP_OWNER_IP);
            }

            @Override
            public void onConnectionFailed(String reason) {
                tvNfcHint.setText("連線失敗: " + reason);
                Toast.makeText(SendActivity.this,
                        "Wi-Fi Direct 連線失敗", Toast.LENGTH_SHORT).show();
                sending = false;
                btnStartSend.setText("開始傳送");
            }

            @Override
            public void onDisconnected() {
                tvNfcHint.setText("連線中斷");
            }
        });
    }

    private void sendFiles(String serverIp) {
        fileTransferClient = new FileTransferClient();
        fileTransferClient.sendFiles(this, serverIp, selectedUris, new FileTransferClient.Callback() {
            @Override
            public void onProgressUpdate(String fileName, int percent) {
                runOnUiThread(() -> {
                    progressSend.setProgress(percent);
                    tvNfcHint.setText("傳送中: " + fileName + "  " + percent + "%");
                    TransferNotificationHelper.showProgressNotification(
                            SendActivity.this, fileName, percent);
                });
            }

            @Override
            public void onFileSent(String fileName, long fileSize) {
                runOnUiThread(() -> {
                    Toast.makeText(SendActivity.this,
                            "已傳送：" + fileName, Toast.LENGTH_SHORT).show();
                    HistoryRepository repo = new HistoryRepository(SendActivity.this);
                    repo.insert(new TransferRecord(fileName, fileSize, null,
                            System.currentTimeMillis(), "SENT", "SUCCESS", null));
                });
            }

            @Override
            public void onAllFilesSent(int count) {
                runOnUiThread(() -> {
                    tvNfcHint.setText("傳送完成！");
                    progressSend.setProgress(100);
                    TransferNotificationHelper.showCompletionNotification(
                            SendActivity.this, count, true);
                    sending = false;
                    btnStartSend.setText("開始傳送");
                });
            }

            @Override
            public void onError(String fileName, Exception e) {
                runOnUiThread(() -> {
                    tvNfcHint.setText("傳送失敗");
                    Toast.makeText(SendActivity.this,
                            "傳送錯誤：" + (e != null ? e.getMessage() : "未知"), Toast.LENGTH_LONG).show();
                    TransferNotificationHelper.showErrorNotification(
                            SendActivity.this, fileName);
                });
            }
        });
    }

    private void stopSendMode() {
        if (!sending) return;
        sending = false;
        waitingForNfc = false;
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
        if (btnStartSend != null) btnStartSend.setText("開始傳送");
        if (tvNfcHint != null) tvNfcHint.setText("");
        if (progressSend != null) progressSend.setVisibility(View.GONE);

        if (fileTransferClient != null) {
            fileTransferClient.shutdown();
            fileTransferClient = null;
        }
        wifiDirectManager.disconnect();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != QR_SCAN_REQUEST || resultCode != RESULT_OK || data == null) return;

        String qrContent = data.getStringExtra("SCAN_RESULT");
        if (qrContent == null) {
            Toast.makeText(this, "QR Code 讀取失敗", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] creds = QrCodeHelper.parseQrResult(qrContent);
        if (creds == null) {
            Toast.makeText(this, "QR Code 格式錯誤", Toast.LENGTH_SHORT).show();
            return;
        }

        progressSend.setVisibility(View.VISIBLE);
        progressSend.setProgress(0);
        tvNfcHint.setText("已取得憑證，連線中...");
        sending = true;
        btnStartSend.setText("停止傳送");
        connectAndSend(creds[0], creds[1]);
    }
}
