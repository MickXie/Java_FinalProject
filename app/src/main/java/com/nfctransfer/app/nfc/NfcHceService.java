package com.nfctransfer.app.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * NFC Host Card Emulation (HCE) service — receiver side.
 *
 * When the sender taps their phone against this device, this service responds
 * to SELECT AID and a custom READ command with the Wi-Fi Direct credentials
 * encoded as a UTF-8 JSON payload.
 *
 * AID: F0 4E 46 43 54 52 01 01  (8 bytes, registered in res/xml/apdu_service.xml)
 *
 * APDU protocol:
 *   SELECT AID  (00 A4 04 00 08 F0 4E 46 43 54 52 01 01 00) → 90 00
 *   READ        (00 B0 00 00 00)  → <UTF-8 JSON bytes> 90 00
 *   unknown     → 6F 00
 */
public class NfcHceService extends HostApduService {

    private static final String TAG = "NfcHceService";

    public static final String AID = "F04E464354520101";

    private static final byte[] SW_OK      = {(byte) 0x90, (byte) 0x00};
    private static final byte[] SW_UNKNOWN = {(byte) 0x6F, (byte) 0x00};

    // SELECT APDU header: CLA INS P1 P2
    private static final byte[] SELECT_APDU_HEADER = {
            (byte) 0x00,
            (byte) 0xA4,
            (byte) 0x04,
            (byte) 0x00
    };

    private static final byte INS_READ = (byte) 0xB0;

    /** JSON credentials staged by the Activity; volatile for cross-thread visibility. */
    private static volatile String pendingJson = null;

    /**
     * Called by ReceiveActivity after the Wi-Fi Direct group is created.
     *
     * @param ssid       Wi-Fi Direct group SSID
     * @param passphrase Wi-Fi Direct group passphrase
     */
    public static void setCredentials(String ssid, String passphrase) {
        String escaped_ssid = ssid.replace("\\", "\\\\").replace("\"", "\\\"");
        String escaped_pass = passphrase.replace("\\", "\\\\").replace("\"", "\\\"");
        pendingJson = "{\"ssid\":\"" + escaped_ssid + "\",\"pass\":\"" + escaped_pass + "\"}";
        Log.d(TAG, "Credentials armed: ssid=" + ssid);
    }

    public static void clearCredentials() {
        pendingJson = null;
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null) {
            return SW_UNKNOWN;
        }

        Log.d(TAG, "Received APDU: " + bytesToHex(commandApdu));

        if (isSelectAid(commandApdu)) {
            if (pendingJson == null) {
                Log.w(TAG, "SELECT AID — credentials not ready, rejecting");
                return SW_UNKNOWN;
            }
            Log.d(TAG, "SELECT AID — responding 9000");
            return SW_OK;
        }

        if (commandApdu.length >= 2 && commandApdu[1] == INS_READ) {
            return buildReadResponse();
        }

        Log.w(TAG, "Unrecognised APDU");
        return SW_UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "HCE deactivated, reason=" + reason);
    }

    // -------------------------------------------------------------------------

    private boolean isSelectAid(byte[] apdu) {
        if (apdu.length < SELECT_APDU_HEADER.length) return false;
        for (int i = 0; i < SELECT_APDU_HEADER.length; i++) {
            if (apdu[i] != SELECT_APDU_HEADER[i]) return false;
        }
        return true;
    }

    private byte[] buildReadResponse() {
        String json = pendingJson;
        if (json == null || json.isEmpty()) {
            Log.w(TAG, "READ request but no credentials staged");
            return SW_UNKNOWN;
        }
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] response = new byte[payload.length + SW_OK.length];
        System.arraycopy(payload, 0, response, 0, payload.length);
        System.arraycopy(SW_OK, 0, response, payload.length, SW_OK.length);
        Log.d(TAG, "Vending credentials (" + payload.length + " bytes)");
        return response;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}
