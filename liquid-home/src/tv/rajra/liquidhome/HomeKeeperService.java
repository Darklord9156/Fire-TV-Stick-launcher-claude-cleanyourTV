package tv.rajra.liquidhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Reopens Liquid Home whenever Amazon's launcher comes to the foreground. */
public class HomeKeeperService extends AccessibilityService {
    private static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private long lastLaunch;

    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = new String[] { AMAZON_HOME };
        info.notificationTimeout = 0;
        setServiceInfo(info);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!AMAZON_HOME.contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - lastLaunch < 1200) return; // debounce
        lastLaunch = now;
        Intent launch = new Intent(this, Home.class);
        launch.putExtra("page", "home");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(launch);
    }

    @Override public void onInterrupt() { }
}
