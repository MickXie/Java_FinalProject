package com.nfctransfer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.nfctransfer.app.transfer.ReceiverForegroundService;
import com.nfctransfer.app.transfer.TransferNotificationHelper;
import com.nfctransfer.app.util.PermissionHelper;
import com.nfctransfer.app.util.QrCodeHelper;

import java.util.List;

public class ReceiveActivity extends AppCompatActivity {

    private static final String TAG = "ReceiveActivity";

    private TextView tvStatus;
    private TextView tvFileInfo;
    private ProgressBar progressReceive;
    private Button btnStartReceive;
    private Button btnShowQr;
    private ImageView ivQrCode;

    private boolean serviceRunning = false;
    private String currentSsid = null;
    private String currentPass = null;

    private BroadcastReceiver serviceReceiver;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                List<String> stillMissing = PermissionHelper.getMissingPermissions(this);
                if (!stillMissing.isEmpty()) {
                    Toast.makeText(this, "需要權限才能接收檔案", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);

        tvStatus        = findViewById(R.id.tv_status);
        tvFileInfo      = findViewById(R.id.tv_file_info);
        progressReceive = findViewById(R.id.progress_receive);
        btnStartReceive = findViewById(R.id.btn_start_receive);
        btnShowQr       = findViewById(R.id.btn_show_qr);
        ivQrCode        = findViewById(R.id.iv_qr_code);

        TransferNotificationHelper.createNotificationChannel(this);

        List<String> missing = PermissionHelper.getMissingPermissions(this);
        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }

        tvStatus.setText("點擊「開始接收」以準備接收檔案");
        btnShowQr.setEnabled(false);

        btnStartReceive.setOnClickListener(v -> {
            if (!serviceRunning) {
                startReceiveService();
            } else {
                stopReceiveService();
            }
        });

        btnShowQr.setOnClickListener(v -> toggleQrCode());

        serviceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (action == null) return;

                switch (action) {
                    case ReceiverForegroundService.BROADCAST_READY:
                        currentSsid = intent.getStringExtra(ReceiverForegroundService.EXTRA_SSID);
                        currentPass = intent.getStringExtra(ReceiverForegroundService.EXTRA_PASS);
                        serviceRunning = true;
                        tvStatus.setText("等待接收中...");
                        btnShowQr.setEnabled(true);
                        btnStartReceive.setText(getString(R.string.btn_stop_receive));
                        break;

                    case ReceiverForegroundService.BROADCAST_PROGRESS:
                        String fileName = intent.getStringExtra(ReceiverForegroundService.EXTRA_FILE_NAME);
                        int percent = intent.getIntExtra(ReceiverForegroundService.EXTRA_PERCENT, 0);
                        progressReceive.setVisibility(View.VISIBLE);
                        progressReceive.setProgress(percent);
                        if (tvFileInfo != null) {
                            tvFileInfo.setVisibility(View.VISIBLE);
                            tvFileInfo.setText((fileName != null ? fileName : "") + "  " + percent + "%");
                        }
                        tvStatus.setText(getString(R.string.status_receiving));
                        break;

                    case ReceiverForegroundService.BROADCAST_COMPLETE:
                        int count = intent.getIntExtra(ReceiverForegroundService.EXTRA_FILE_COUNT, 0);
                        tvStatus.setText(getString(R.string.status_done));
                        progressReceive.setProgress(100);
                        if (tvFileInfo != null) tvFileInfo.setVisibility(View.GONE);
                        Toast.makeText(ReceiveActivity.this,
                                "已接收 " + count + " 個檔案", Toast.LENGTH_SHORT).show();
                        break;

                    case ReceiverForegroundService.BROADCAST_ERROR:
                        String error = intent.getStringExtra(ReceiverForegroundService.EXTRA_FILE_NAME);
                        tvStatus.setText("接收失敗: " + (error != null ? error : "未知錯誤"));
                        break;
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ReceiverForegroundService.BROADCAST_READY);
        filter.addAction(ReceiverForegroundService.BROADCAST_PROGRESS);
        filter.addAction(ReceiverForegroundService.BROADCAST_COMPLETE);
        filter.addAction(ReceiverForegroundService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver);
    }

    private void startReceiveService() {
        tvStatus.setText("初始化中...");
        btnStartReceive.setText(getString(R.string.btn_stop_receive));
        Intent intent = new Intent(this, ReceiverForegroundService.class);
        intent.setAction(ReceiverForegroundService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopReceiveService() {
        serviceRunning = false;
        currentSsid = null;
        currentPass = null;
        btnStartReceive.setText(getString(R.string.btn_start_receive));
        btnShowQr.setEnabled(false);
        ivQrCode.setVisibility(View.GONE);
        btnShowQr.setText(getString(R.string.btn_show_qr));
        progressReceive.setVisibility(View.GONE);
        if (tvFileInfo != null) tvFileInfo.setVisibility(View.GONE);
        tvStatus.setText("點擊「開始接收」以準備接收檔案");

        Intent intent = new Intent(this, ReceiverForegroundService.class);
        intent.setAction(ReceiverForegroundService.ACTION_STOP);
        startService(intent);
    }

    private void toggleQrCode() {
        if (ivQrCode.getVisibility() == View.VISIBLE) {
            ivQrCode.setVisibility(View.GONE);
            btnShowQr.setText(getString(R.string.btn_show_qr));
            return;
        }
        if (currentSsid == null || currentPass == null) {
            Toast.makeText(this, "服務尚未就緒", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap qr = QrCodeHelper.generateQrCode(currentSsid, currentPass, 512);
        if (qr != null) {
            Glide.with(this).load(qr).into(ivQrCode);
            ivQrCode.setVisibility(View.VISIBLE);
            btnShowQr.setText("隱藏 QR Code");
        }
    }
}
