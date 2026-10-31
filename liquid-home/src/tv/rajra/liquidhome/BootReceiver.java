package tv.rajra.liquidhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the Fire OS 6 UsageStats keeper after boot or app replacement. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Keeper.start(context);
    }
}
