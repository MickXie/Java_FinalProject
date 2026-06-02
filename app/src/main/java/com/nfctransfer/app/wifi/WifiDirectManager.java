package com.nfctransfer.app.wifi;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Singleton wrapper around {@link WifiP2pManager} for creating and joining
 * Wi-Fi Direct groups.
 *
 * Receiver flow:
 *   1. {@link #createGroup(GroupInfoCallback)} — creates a persistent group and
 *      returns its SSID + passphrase via the callback.
 *   2. Hand credentials to {@link com.nfctransfer.app.nfc.NfcHceService}.
 *
 * Sender flow:
 *   1. Read credentials from NFC.
 *   2. {@link #connectToGroup(String, String, ConnectionCallback)} — joins the group.
 *   3. Use the peer IP from the callback to start FileTransferClient.
 */
public class WifiDirectManager {

    private static final String TAG = "WifiDirectManager";

    /** Fixed group owner IP in Wi-Fi Direct (GO is always 192.168.49.1). */
    public static final String GROUP_OWNER_IP = "192.168.49.1";

    public interface ConnectionCallback {
        void onConnected(String peerIpAddress);
        void onConnectionFailed(String reason);
        void onDisconnected();
    }

    public interface GroupInfoCallback {
        void onGroupCreated(String ssid, String passphrase);
        void onGroupCreationFailed(String reason);
    }

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile WifiDirectManager instance;

    public static WifiDirectManager getInstance(Context context) {
        if (instance == null) {
            synchronized (WifiDirectManager.class) {
                if (instance == null) {
                    instance = new WifiDirectManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Context context;
    private final WifiP2pManager manager;
    private final Channel channel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WifiP2pBroadcastReceiver broadcastReceiver;
    private ConnectionCallback pendingConnectionCallback;

    private WifiDirectManager(Context context) {
        this.context = context;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, Looper.getMainLooper(), null);
    }

    // -------------------------------------------------------------------------
    // Receiver side
    // -------------------------------------------------------------------------

    public boolean isWifiP2pEnabled() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) 
                context.getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    /**
     * Creates a persistent Wi-Fi Direct group on this device.
     * The SSID and passphrase are returned via {@code callback} on the main thread.
     */
    public void createGroup(GroupInfoCallback callback) {
        manager.createGroup(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup succeeded, requesting group info");
                manager.requestGroupInfo(channel, group -> {
                    if (group != null) {
                        String ssid = group.getNetworkName();
                        String pass = group.getPassphrase();
                        Log.d(TAG, "Group ready: ssid=" + ssid);
                        mainHandler.post(() -> {
                            if (callback != null) callback.onGroupCreated(ssid, pass);
                        });
                    } else {
                        Log.e(TAG, "requestGroupInfo returned null");
                        mainHandler.post(() -> {
                            if (callback != null) callback.onGroupCreationFailed("Group info null");
                        });
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "createGroup failed, reason=" + reason);
                // Reason 3 = BUSY / group already exists — try requestGroupInfo anyway
                if (reason == WifiP2pManager.BUSY) {
                    manager.requestGroupInfo(channel, group -> {
                        if (group != null) {
                            Log.d(TAG, "Existing group found: " + group.getNetworkName());
                            mainHandler.post(() -> {
                                if (callback != null)
                                    callback.onGroupCreated(group.getNetworkName(), group.getPassphrase());
                            });
                        } else {
                            mainHandler.post(() -> {
                                if (callback != null)
                                    callback.onGroupCreationFailed("reason=" + reason);
                            });
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onGroupCreationFailed("reason=" + reason);
                    });
                }
            }
        });
    }

    /** Removes the current Wi-Fi Direct group. */
    public void removeGroup() {
        manager.removeGroup(channel, new ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
            @Override public void onFailure(int reason) { Log.w(TAG, "removeGroup failed: " + reason); }
        });
    }

    // -------------------------------------------------------------------------
    // Sender side
    // -------------------------------------------------------------------------

    /**
     * Connects to an existing Wi-Fi Direct group using {@link WifiP2pConfig.Builder}
     * (API 29+). On success the group owner IP (192.168.49.1) is returned via
     * {@code callback}.
     */
    public void connectToGroup(String ssid, String passphrase, ConnectionCallback callback) {
        pendingConnectionCallback = callback;

        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .build();

        manager.connect(channel, config, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "connect() initiated, waiting for broadcast");
                // Actual IP delivery happens in onConnectionChanged via the BroadcastReceiver
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "connect() failed, reason=" + reason);
                mainHandler.post(() -> {
                    if (callback != null) callback.onConnectionFailed("reason=" + reason);
                });
                pendingConnectionCallback = null;
            }
        });
    }

    /** Disconnects from the current group. */
    public void disconnect() {
        manager.removeGroup(channel, new ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "disconnect: group removed"); }
            @Override public void onFailure(int reason) { Log.w(TAG, "disconnect failed: " + reason); }
        });
        if (pendingConnectionCallback != null) {
            ConnectionCallback cb = pendingConnectionCallback;
            pendingConnectionCallback = null;
            mainHandler.post(cb::onDisconnected);
        }
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver lifecycle
    // -------------------------------------------------------------------------

    /** Register the Wi-Fi P2P broadcast receiver for the given Activity. */
    public void registerReceiver(Activity activity) {
        registerReceiver((Context) activity);
    }

    /** Register the Wi-Fi P2P broadcast receiver for the given Context (Service-friendly). */
    public void registerReceiver(Context ctx) {
        broadcastReceiver = new WifiP2pBroadcastReceiver(manager, channel, this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ctx.registerReceiver(broadcastReceiver, filter);
    }

    /** Unregister the Wi-Fi P2P broadcast receiver. */
    public void unregisterReceiver(Activity activity) {
        unregisterReceiver((Context) activity);
    }

    /** Unregister the Wi-Fi P2P broadcast receiver for the given Context. */
    public void unregisterReceiver(Context ctx) {
        if (broadcastReceiver != null) {
            try {
                ctx.unregisterReceiver(broadcastReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered
            }
            broadcastReceiver = null;
        }
    }

    // -------------------------------------------------------------------------
    // Called by WifiP2pBroadcastReceiver
    // -------------------------------------------------------------------------

    void onConnectionChanged(android.net.NetworkInfo networkInfo) {
        // Always call requestConnectionInfo — on Android 13+ networkInfo may be null
        // even when a connection is established (deprecated extra).
        manager.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupFormed) {
                Log.d(TAG, "Connected to group, GO IP=" + GROUP_OWNER_IP);
                ConnectionCallback cb = pendingConnectionCallback;
                if (cb != null) {
                    pendingConnectionCallback = null;
                    mainHandler.post(() -> cb.onConnected(GROUP_OWNER_IP));
                }
            } else {
                boolean wasDisconnected = networkInfo != null && !networkInfo.isConnected();
                Log.d(TAG, "P2P state changed, groupFormed=false, disconnected=" + wasDisconnected);
            }
        });
    }
}
