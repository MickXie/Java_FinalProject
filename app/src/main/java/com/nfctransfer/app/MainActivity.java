package com.nfctransfer.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.nfctransfer.app.util.PermissionHelper;

public class MainActivity extends AppCompatActivity {

    // 1. 宣告新排版中的四個大卡片按鈕
    private CardView btnSendCard;
    private CardView btnReceiveCard;
    private CardView btnHistoryCard;
    private CardView btnProfileCard;

    // 2. 宣告底部懸浮視窗相關元件
    private CardView bottomSheet;
    private TextView tvSheetTitle;
    private BottomSheetBehavior<CardView> bottomSheetBehavior;

    // 3. 宣告懸浮面板裡面的動態元件
    private LinearLayout layoutSendAction;
    private LinearLayout layoutProgress;
    private Button btnPickFile;
    private TextView tvSelectedFile;

    // 權限請求啟動器
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                updateNfcStatus();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 綁定 XML 中的 ID ---
        btnSendCard = findViewById(R.id.btn_send_card);
        btnReceiveCard = findViewById(R.id.btn_receive_card);
        btnHistoryCard = findViewById(R.id.btn_history_card);
        btnProfileCard = findViewById(R.id.btn_profile_card);

        bottomSheet = findViewById(R.id.bottom_sheet);
        tvSheetTitle = findViewById(R.id.tv_sheet_title);

        layoutSendAction = findViewById(R.id.layout_send_action);
        layoutProgress = findViewById(R.id.layout_progress);
        btnPickFile = findViewById(R.id.btn_pick_file);
        tvSelectedFile = findViewById(R.id.tv_selected_file);

        // 取得 BottomSheet 的滑動控制權
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);

        // --- 設定按鈕點擊事件 ---

        // 點擊「傳送」：切換為傳送模式
        btnSendCard.setOnClickListener(v -> {
            tvSheetTitle.setText("準備傳送：請先選擇檔案");

            // 顯示「選擇檔案」區塊，隱藏「進度條」
            layoutSendAction.setVisibility(View.VISIBLE);
            layoutProgress.setVisibility(View.GONE);

            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });

        // 點擊「接收」：切換為接收模式
        btnReceiveCard.setOnClickListener(v -> {
            tvSheetTitle.setText("準備接收：請將手機靠近");

            // 接收端不需要選檔案，所以隱藏「選擇檔案」區塊
            layoutSendAction.setVisibility(View.GONE);

            // 也可以先把進度條藏起來，等連線成功再顯示
            layoutProgress.setVisibility(View.GONE);

            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        });

        // 歷史紀錄：跳轉畫面
        btnHistoryCard.setOnClickListener(v -> {
            startActivity(new Intent(this, HistoryActivity.class));
        });

        // 個人主頁：因為還沒建立這個畫面，先用 Toast 提示
        btnProfileCard.setOnClickListener(v -> {
            Toast.makeText(this, "個人主頁與設定功能即將推出", Toast.LENGTH_SHORT).show();
        });

        // --- 檢查權限 ---
        if (savedInstanceState == null) {
            String[] perms = PermissionHelper.getRequiredPermissions();
            permissionLauncher.launch(perms);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcStatus();
    }

    // --- 檢查 NFC 狀態並更新 UI ---
    private void updateNfcStatus() {
        if (!PermissionHelper.isNfcEnabled(this)) {
            // NFC 沒開：把懸浮視窗標題變紅色，並自動彈出來警告使用者
            tvSheetTitle.setText("NFC 未開啟 — 點此前往設定");
            tvSheetTitle.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvSheetTitle.setOnClickListener(v -> PermissionHelper.showNfcEnableDialog(this));

            if (bottomSheetBehavior != null) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        } else {
            // NFC 有開：恢復原本深色的文字設定，並取消點擊事件
            tvSheetTitle.setTextColor(getResources().getColor(R.color.text_dark));
            tvSheetTitle.setOnClickListener(null);
        }
    }
}