package com.gathin.bingwall;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * Whether a fetch is worth starting. A TV in standby, or one whose Wi-Fi has dropped, would otherwise wait out
 * the connection timeout in the background for nothing.
 *
 * This is what the ACCESS_NETWORK_STATE permission is for; nothing about the network is read beyond this test.
 */
final class Online {

    private Online() {}

    /** True when the TV believes it has a connection; true when it cannot tell, so a fetch is still tried. */
    static boolean likely(Context context) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            NetworkInfo active = manager.getActiveNetworkInfo();
            return active == null || active.isConnected();
        } catch (RuntimeException e) {
            return true;   // Never block a fetch on this check failing.
        }
    }
}
