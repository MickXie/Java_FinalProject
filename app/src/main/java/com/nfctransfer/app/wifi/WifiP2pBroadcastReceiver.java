package com.nfctransfer.app.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.os.Build;
import android.util.Log;

/**
 * Handles Wi-Fi Direct system broadcasts and delegates connection events
 * to {@link WifiDirectManager}.
 */
public class WifiP2pBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiP2pReceiver";

    private final WifiP2pManager manager;
    private final Channel channel;
    private final WifiDirectManager wifiDirectManager;

    public WifiP2pBroadcastReceiver(WifiP2pManager manager,
                                     Channel channel,
                                     WifiDirectManager wifiDirectManager) {
        this.manager = manager;
        this.channel = channel;
        this.wifiDirectManager = wifiDirectManager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                boolean enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                Log.d(TAG, "Wi-Fi P2P state changed: " + (enabled ? "enabled" : "disabled"));
                break;
            }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                NetworkInfo networkInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo.class);
                } else {
                    networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                }
                Log.d(TAG, "Wi-Fi P2P connection changed: connected=" +
                        (networkInfo != null && networkInfo.isConnected()));
                wifiDirectManager.onConnectionChanged(networkInfo);
                break;
            }

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: {
                WifiP2pDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class);
                } else {
                    device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                }
                if (device != null) {
                    Log.d(TAG, "This device changed: " + device.deviceName
                            + " status=" + device.status);
                }
                break;
            }

            default:
                break;
        }
    }
}
