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

public class WifiDirectManager {

    private static final String TAG = "WifiDirectManager";

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

    // ── Singleton ────────────────────────────────────────────────────────────

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

    // ── State ────────────────────────────────────────────────────────────────

    private final Context context;
    private final WifiP2pManager manager;
    private final Channel channel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WifiP2pBroadcastReceiver broadcastReceiver;

    // volatile so stop()/disconnect() on any thread see latest value
    private volatile ConnectionCallback pendingConnectionCallback;

    // Timeout runnable stored so it can be cancelled
    private Runnable connectTimeoutRunnable;

    // Cancel flag for pollGroupInfo chain
    private volatile boolean groupPollCancelled = false;

    private WifiDirectManager(Context context) {
        this.context = context;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, Looper.getMainLooper(), null);
    }

    // ── Receiver side ────────────────────────────────────────────────────────

    public boolean isWifiP2pEnabled() {
        android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    public void createGroup(GroupInfoCallback callback) {
        groupPollCancelled = false;
        manager.createGroup(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup succeeded, polling group info");
                pollGroupInfo(callback, 6);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "createGroup failed, reason=" + reason);
                if (reason == WifiP2pManager.BUSY) {
                    // Group already exists — retrieve its info
                    pollGroupInfo(callback, 4);
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onGroupCreationFailed("reason=" + reason);
                    });
                }
            }
        });
    }

    private void pollGroupInfo(GroupInfoCallback callback, int attemptsLeft) {
        if (groupPollCancelled) return;
        manager.requestGroupInfo(channel, group -> {
            if (groupPollCancelled) return;
            if (group != null && group.getNetworkName() != null && group.getPassphrase() != null) {
                Log.d(TAG, "Group ready: ssid=" + group.getNetworkName());
                mainHandler.post(() -> {
                    if (callback != null)
                        callback.onGroupCreated(group.getNetworkName(), group.getPassphrase());
                });
            } else if (attemptsLeft > 1) {
                Log.w(TAG, "Group info not ready, retry in 500ms (left=" + attemptsLeft + ")");
                mainHandler.postDelayed(() -> pollGroupInfo(callback, attemptsLeft - 1), 500);
            } else {
                Log.e(TAG, "Group info still null after all retries");
                mainHandler.post(() -> {
                    if (callback != null) callback.onGroupCreationFailed("Group info unavailable");
                });
            }
        });
    }

    public void removeGroup() {
        groupPollCancelled = true; // stop any ongoing pollGroupInfo chain
        manager.removeGroup(channel, new ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
            @Override public void onFailure(int r) { Log.w(TAG, "removeGroup failed: " + r); }
        });
    }

    // ── Sender side ──────────────────────────────────────────────────────────

    public void connectToGroup(String ssid, String passphrase, ConnectionCallback callback) {
        pendingConnectionCallback = callback;
        // Stop discovery to reduce channel contention
        manager.stopPeerDiscovery(channel, new ActionListener() {
            @Override public void onSuccess() { cancelThenConnect(ssid, passphrase, callback); }
            @Override public void onFailure(int r) { cancelThenConnect(ssid, passphrase, callback); }
        });
    }

    private void cancelThenConnect(String ssid, String passphrase, ConnectionCallback callback) {
        manager.cancelConnect(channel, new ActionListener() {
            @Override public void onSuccess() {
                // Add 200 ms settling delay: stock Android (Pixel) enforces a strict P2P state
                // machine and may still be in the CANCELLING→IDLE transition when cancelConnect
                // reports success.  Attempting connect() too quickly returns BUSY (reason=2).
                // Samsung OEM is lenient; Pixel is not — the delay bridges that gap.
                Log.d(TAG, "cancelConnect onSuccess — waiting 200ms for state machine to settle (Pixel compat)");
                mainHandler.postDelayed(() -> doConnect(ssid, passphrase, callback, false, 0), 200);
            }
            @Override public void onFailure(int r) {
                Log.d(TAG, "cancelConnect onFailure r=" + r + " — waiting 200ms before connect");
                mainHandler.postDelayed(() -> doConnect(ssid, passphrase, callback, false, 0), 200);
            }
        });
    }

    /**
     * Pre-warms the P2P radio by stopping any active discovery.
     *
     * On stock Android (Pixel), an active discoverPeers session holds the P2P
     * state machine in the SCANNING state. When the sender then calls connect(),
     * the framework returns BUSY (reason=2) because it cannot start a connection
     * while scanning is in progress.  Samsung OEM firmware is lenient about this;
     * Pixel (AOSP) enforces the state machine strictly.
     *
     * The correct approach is to stop discovery on the receiver side so the
     * framework is idle and ready to accept an incoming connection negotiation.
     */
    public void warmUpRadio() {
        manager.stopPeerDiscovery(channel, new ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "P2P radio warmed up: discovery stopped, ready for connection"); }
            @Override public void onFailure(int r) { Log.d(TAG, "warmUp stopPeerDiscovery: " + r + " (ok — not scanning)"); }
        });
    }

    private void doConnect(String ssid, String passphrase, ConnectionCallback callback,
                           boolean afterRemoveGroup, int busyRetries) {
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .build();

        manager.connect(channel, config, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "connect() initiated");
                // 5-second fallback in case WIFI_P2P_CONNECTION_CHANGED_ACTION is dropped
                connectTimeoutRunnable = () -> {
                    if (pendingConnectionCallback == null) return;
                    Log.w(TAG, "8s timeout — polling requestConnectionInfo");
                    manager.requestConnectionInfo(channel, info -> {
                        if (info != null && info.groupFormed) {
                            deliverConnected();
                        } else if (!afterRemoveGroup) {
                            Log.w(TAG, "groupFormed=false after timeout — cancelConnect + retry");
                            // The sender is a group CLIENT; use cancelConnect() to cleanly
                            // abort the pending association before retrying.  removeGroup()
                            // on a client device destroys the GO's group (which it doesn't
                            // own), causing the Pixel receiver to lose its group entirely.
                            manager.cancelConnect(channel, new ActionListener() {
                                @Override public void onSuccess() {
                                    // 500ms delay: let framework fully tear down before reconnect
                                    mainHandler.postDelayed(
                                        () -> doConnect(ssid, passphrase, callback, true, 0), 500);
                                }
                                @Override public void onFailure(int r) {
                                    mainHandler.postDelayed(
                                        () -> doConnect(ssid, passphrase, callback, true, 0), 500);
                                }
                            });
                        } else {
                            ConnectionCallback cb = pendingConnectionCallback;
                            if (cb != null) {
                                pendingConnectionCallback = null;
                                mainHandler.post(() -> cb.onConnectionFailed("timeout"));
                            }
                        }
                    });
                };
                // 15 s timeout: cross-OEM connections (e.g. Samsung client → Pixel GO)
                // require extra time for WPA supplicant handshake + DHCP.  Pixel-only
                // connections needed ~8 s; Samsung→Pixel empirically needs 12-15 s.
                mainHandler.postDelayed(connectTimeoutRunnable, 15000);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "connect() failed reason=" + reason);
                if (reason == WifiP2pManager.BUSY && busyRetries < 5) {
                    // BUSY = framework still processing; retry up to 5× regardless of afterRemoveGroup.
                    // Use exponential-like backoff: 500 ms, 1000, 1500, 2000, 3000.
                    // Pixel (stock Android) holds the P2P state machine in longer transitions
                    // than Samsung OEM, so the flat 1 s retry is often not enough.
                    long delayMs = (busyRetries < 4) ? (500L * (busyRetries + 1)) : 3000L;
                    Log.w(TAG, "connect BUSY — retry " + (busyRetries + 1) + "/5 after " + delayMs + "ms");
                    mainHandler.postDelayed(
                        () -> doConnect(ssid, passphrase, callback, afterRemoveGroup, busyRetries + 1),
                        delayMs);
                } else if (reason == WifiP2pManager.ERROR && !afterRemoveGroup) {
                    Log.w(TAG, "connect ERROR — cancelConnect + retry");
                    // Sender is a group client; use cancelConnect() not removeGroup().
                    manager.cancelConnect(channel, new ActionListener() {
                        @Override public void onSuccess() {
                            mainHandler.postDelayed(
                                () -> doConnect(ssid, passphrase, callback, true, 0), 500);
                        }
                        @Override public void onFailure(int r) {
                            mainHandler.post(() -> {
                                if (callback != null) callback.onConnectionFailed("reason=" + reason);
                            });
                            pendingConnectionCallback = null;
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onConnectionFailed("reason=" + reason);
                    });
                    pendingConnectionCallback = null;
                }
            }
        });
    }

    private void cancelTimeoutRunnable() {
        if (connectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    private void deliverConnected() {
        cancelTimeoutRunnable();
        ConnectionCallback cb = pendingConnectionCallback;
        if (cb != null) {
            pendingConnectionCallback = null;
            mainHandler.post(() -> cb.onConnected(GROUP_OWNER_IP));
        }
    }

    public void disconnect() {
        cancelTimeoutRunnable();
        groupPollCancelled = true;
        // Use cancelConnect() here instead of removeGroup().
        // The sender is a group CLIENT, not the group owner — calling removeGroup()
        // on a client device tries to tear down a group it doesn't own, which either
        // fails silently (Samsung) or leaves the P2P state machine in a bad state.
        // cancelConnect() is the correct API for a client to leave a pending or
        // established connection without destroying the GO's group.
        manager.cancelConnect(channel, new ActionListener() {
            @Override public void onSuccess() { Log.d(TAG, "disconnect: cancelConnect succeeded"); }
            @Override public void onFailure(int r) { Log.w(TAG, "disconnect cancelConnect failed: " + r + " (ok if not connected)"); }
        });
        ConnectionCallback cb = pendingConnectionCallback;
        if (cb != null) {
            pendingConnectionCallback = null;
            mainHandler.post(cb::onDisconnected);
        }
    }

    // ── BroadcastReceiver lifecycle ───────────────────────────────────────────

    public void registerReceiver(Activity activity) { registerReceiver((Context) activity); }

    public void registerReceiver(Context ctx) {
        // Unregister stale receiver before creating new one (prevents rotation leak)
        if (broadcastReceiver != null) {
            try { ctx.unregisterReceiver(broadcastReceiver); } catch (IllegalArgumentException ignored) {}
            broadcastReceiver = null;
        }
        broadcastReceiver = new WifiP2pBroadcastReceiver(manager, channel, this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ctx.registerReceiver(broadcastReceiver, filter);
    }

    public void unregisterReceiver(Activity activity) { unregisterReceiver((Context) activity); }

    public void unregisterReceiver(Context ctx) {
        if (broadcastReceiver != null) {
            try { ctx.unregisterReceiver(broadcastReceiver); } catch (IllegalArgumentException ignored) {}
            broadcastReceiver = null;
        }
    }

    // ── Called by WifiP2pBroadcastReceiver ───────────────────────────────────

    void onConnectionChanged(android.net.NetworkInfo networkInfo) {
        manager.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupFormed) {
                Log.d(TAG, "Connected to group, GO IP=" + GROUP_OWNER_IP);
                deliverConnected(); // also cancels the timeout runnable
            } else {
                Log.d(TAG, "P2P state changed, groupFormed=false");
            }
        });
    }
}
