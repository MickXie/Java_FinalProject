package com.nfctransfer.app.util;

import android.app.Activity;
import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.CaptureActivity;

import java.util.HashMap;
import java.util.Map;

import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

public class QrCodeHelper {

    public static Bitmap generateQrCode(String ssid, String passphrase, int sizePixels) {
        try {
            JSONObject json = new JSONObject();
            json.put("ssid", ssid);
            json.put("pass", passphrase);
            String content = json.toString();

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BarcodeEncoder encoder = new BarcodeEncoder();
            return encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, sizePixels, sizePixels, hints);
        } catch (WriterException | JSONException e) {
            return null;
        }
    }

    public static void startQrScanner(Activity activity, int requestCode) {
        Intent intent = new Intent(activity, CaptureActivity.class);
        intent.setAction("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
        activity.startActivityForResult(intent, requestCode);
    }

    public static String[] parseQrResult(String qrContent) {
        try {
            JSONObject json = new JSONObject(qrContent);
            String ssid = json.getString("ssid");
            String pass = json.getString("pass");
            return new String[]{ssid, pass};
        } catch (JSONException e) {
            return null;
        }
    }
}
