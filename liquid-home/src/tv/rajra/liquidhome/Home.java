package tv.rajra.liquidhome;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.text.InputType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone, offline Apple TV-inspired home screen for Fire OS 6. */
public final class Home extends Activity {
    static volatile boolean returnToHomeFromKeeper;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String PREFS = "liquid-home";
    private static final String DISPLAY_NAME_KEY = "display_name";
    private static final String NAME_SETUP_COMPLETE = "name_setup_complete";
    private static final String FOCUS_EFFECT_COLOR_KEY = "focus_effect_color_index";
    private static final String MAIN_ROW_KEY = "main_row_packages";
    private static final String ALL_APPS_ORDER_KEY = "all_apps_order";
    private static final String[] STREAMING_PRIORITY = {
        "com.netflix.ninja", "com.amazon.firetv.youtube", "in.startv.hotstar",
        "com.amazon.amazonvideo.livingroom", "com.amazon.avod", "com.disney.disneyplus", "com.zee5.amazon",
        "com.sonyliv", "com.apple.atve.amazon.appletv", "com.nuvio.tv", "com.stremio.one"
    };
    private static final String[] FOCUS_EFFECT_COLOR_NAMES = {
        "Emerald Green", "Magenta Pink", "Midnight Blue", "Royal Purple", "Crimson Red",
        "Burnt Orange", "Teal", "Lime Green", "Slate Grey", "Antique Gold"
    };
    private static final int[] FOCUS_EFFECT_COLORS = {
        0xFF00651E, 0xFF8A0054, 0xFF03309B, 0xFF4B1593, 0xFF970009,
        0xFFA43900, 0xFF006565, 0xFF4B8100, 0xFF424B54, 0xFF8E6100
    };
    private static final String BUILTIN = "builtin";
    private static final String LEGACY_BUILTIN_WALLPAPER = "doom.jpg";
    private static final int REQUEST_ADD_WALLPAPER = 7301;
    private static final int REQUEST_UNINSTALL_APP = 7303;
    private static final int HOME_APPS_COLUMNS = 6;
    private static final float HOME_TILE_ASPECT_RATIO = 1.695f;
    private static final float HOME_HERO_COMPACT_SCALE = .66f;
    private static final int REQUEST_TMDB_TOKEN = 7302;
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService WALLPAPER_BLUR_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liquid-wallpaper-blur"); t.setDaemon(true); return t;
    });
    private static final int[] APP_GLOWS = {0xFFEC4E4E, 0xFF43A5FF, 0xFF38D49A, 0xFFB16CFF, 0xFFFF8A42, 0xFF8B7BFF, 0xFFFFD056, 0xFF4CCFC7, 0xFFFF6DAA};
    private static final String[] APP_ORDER = {
        "com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube",
        "in.startv.hotstar", "com.nuvio.tv", "com.apple.atve.amazon.appletv",
        "com.stremio.one", "com.esaba.downloader", "com.zee5.amazon"
    };
    private static final HashSet<String> EXCLUDED_ALL_APP_PACKAGES = new HashSet<>(Arrays.asList(
        "com.amazon.gamehub", "com.amazon.minitv.android.app", "com.amazon.bueller.photos",
        "com.amazon.ftv.downloader", "com.amazon.imdb.tv.android.app", "com.imdb.livingroom.firetv",
        "com.amazon.cloud9", "com.amazon.bueller.music", "com.amazon.tv.livetv",
        "com.android.documentsui", "com.amazon.ftv.screensaver"
    ));
    private static final HashSet<String> EXCLUDED_ALL_APP_LABELS = new HashSet<>(Arrays.asList(
        "amazon game center", "game center", "kids", "amazon kids", "amazon minitv", "minitv",
        "amazon photos", "photos", "downloads", "download manager", "fire tv profile ui", "profile ui",
        "files", "screen saver",
        "imdb", "imdb tv", "kftv launcher", "music & more", "silk", "silk browser",
        "what should i watch", "what should i watch?", "settings", "fire tv settings", "amazon home",
        "device settings", "system settings", "android settings"
    ));

    private final List<AppItem> apps = new ArrayList<>();
    private final List<AppItem> homeAllApps = new ArrayList<>();
    private final List<AppItem> favorites = new ArrayList<>();
    private final List<AppItem> eligibleMainRowApps = new ArrayList<>();
    private final List<GlassCard> navButtons = new ArrayList<>();
    private final List<String> navTargets = new ArrayList<>();
    private SharedPreferences prefs;
    private FrameLayout root;
    private ImageView wallpaperView;
    private ImageView wallpaperBlurView;
    private View wallpaperTransitionShade;
    private String wallpaperRenderKey;
    private int wallpaperBlurGeneration;
    private float wallpaperBlurAmount;
    private AmbientGlowView ambientGlow;
    private LinearLayout floatingNav;
    private HomeScrollView homeScroll;
    private View homeHeroView;
    private TextView homeClockView;
    private TextView homeGreetingView;
    private float homeHeroCollapseFraction;
    private View homeFavoriteStrip;
    private boolean homeChromeAutoHide;
    private final Runnable homeClockTick = new Runnable() {
        @Override public void run() { updateHomeClock(); }
    };
    private final List<GlassCard> homeFavoriteCards = new ArrayList<>();
    private final List<FrameLayout> homeFavoriteCells = new ArrayList<>();
    private final List<GlassCard> homeAllAppCards = new ArrayList<>();
    private View homeAllAppsTitle;
    private FrameLayout homeAllAppsSection;
    private ImageView homeAllAppsBlur;
    private boolean homeAllAppsExpanded;
    private boolean homeAllAppsTransitionTargetExpanded;
    // ---- Motion design system: one set of durations and curves used by every animation ----
    private static final long MOTION_FOCUS_IN_MS = 140L, MOTION_FOCUS_OUT_MS = 110L;   // tile / button focus
    private static final long MOTION_APP_TILE_FOCUS_IN_MS = 220L, MOTION_APP_TILE_FOCUS_OUT_MS = 190L;
    private static final long MOTION_VIEWPORT_MS = 560L;                               // Home <-> All Apps (both ways)
    private static final long MOTION_GLOW_MS = 160L, MOTION_NAV_MS = 200L, MOTION_PAGE_MS = 200L;
    /** Decelerating entrance: responsive start, soft landing (focus in, page enter). */
    private static final android.view.animation.Interpolator EASE_OUT = new PathInterpolator(.2f, 0f, .2f, 1f);
    /** Smooth acceleration and deceleration for movement (viewport, glow, nav). */
    private static final android.view.animation.Interpolator EASE_MOVE = new PathInterpolator(.4f, 0f, .2f, 1f);
    private ValueAnimator homeViewportAnimator;
    private int homeAllAppsTargetScroll;
    private int lastFavoriteFocus;
    private boolean homeNavKeyHandled;
    private ValueAnimator navAnimator;
    private int navExpandedSize, navCompactSize;
    private boolean navCompact;
    private LinearLayout pageHost;
    private EditText searchField;
    private ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener;
    private boolean searchImeVisible;
    private boolean searchKeyboardSuppressed;
    private boolean searchKeyboardOpenRequested;
    private boolean searchBackHandled;
    private boolean homeBackHandled;
    private boolean movingMainRowApp;
    private boolean consumeMoveBackUp;
    private int movingMainRowIndex = -1;
    private List<AppItem> mainRowOrderBeforeMove;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int[] screenLocationScratch = new int[2];
    private WallpaperTransferServer wallpaperTransferServer;
    private AlertDialog wallpaperTransferDialog;
    private TextView wallpaperTransferStatus;
    private int searchGeneration;
    private LinearLayout searchResultsHost;
    private List<TmdbClient.Item> currentSearchResults = new ArrayList<>();
    private boolean searchDetailOpen;
    private int searchDetailGeneration;
    private Runnable searchPending;
    private Runnable searchDetailBackAction;
    private int searchRestoreId = -1;
    private String currentQuery = "";
    private String page = "home";
    private BroadcastReceiver packageChangeReceiver;
    private String pendingUninstallPackage;
    private String pendingUninstallPage;
    private int pendingUninstallFocusIndex = -1;
    private boolean pendingUninstallFromMainRow;
    private final Runnable refreshInstalledApps = () -> {
        loadApps();
        if ("home".equals(page) || "apps".equals(page)) showPage(page);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(DISPLAY_NAME_KEY)) {
            String configuredName = getString(R.string.configured_display_name);
            if (configuredName != null && !configuredName.trim().isEmpty()) {
                prefs.edit().putString(DISPLAY_NAME_KEY, configuredName.trim()).putBoolean(NAME_SETUP_COMPLETE, true).apply();
            }
        }
        loadApps();
        buildShell();
        showPage(getIntent().getStringExtra("page"));
        registerPackageChangeReceiver();
        if (!prefs.getBoolean(NAME_SETUP_COMPLETE, false) && !prefs.contains(DISPLAY_NAME_KEY)) {
            root.post(() -> promptForGreetingName(true));
        }
        Keeper.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        // Keep the existing view tree intact while the OS uninstall confirmation is open.
        // The activity result refreshes it and restores focus when the system flow returns.
        if (pendingUninstallPackage != null) return;
        loadApps();
        setWallpaper();
        if (returnToHomeFromKeeper) {
            returnToHomeFromKeeper = false;
            showPage("home");
        } else if ("home".equals(page) || "apps".equals(page)) showPage(page);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showPage(intent.getStringExtra("page"));
        Keeper.start(this);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL_APP) {
            finishUninstallFlow(resultCode);
            return;
        }
        if (requestCode == REQUEST_TMDB_TOKEN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importTmdbToken(data.getData());
            return;
        }
        if (requestCode != REQUEST_ADD_WALLPAPER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importWallpaper(data.getData());
    }

    @Override public void onBackPressed() {
        if ("search".equals(page) && (searchKeyboardOpenRequested || isSearchKeyboardVisible())) {
            dismissSearchKeyboard();
            return;
        }
        if ("search".equals(page) && searchDetailOpen) {
            searchDetailOpen = false;
            if (searchDetailBackAction != null) searchDetailBackAction.run();
            else renderSearchResults(currentSearchResults, currentQuery, searchResultsHost);
            return;
        }
        if ("background".equals(page)) showPage("settings");
        else if ("search-detail".equals(page)) showPage("search");
        else if (!"home".equals(page)) showPage("home");
        else showPage("home");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (consumeMoveBackUp && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) consumeMoveBackUp = false;
            return true;
        }
        if ("home".equals(page) && movingMainRowApp) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) { cancelMainRowMove(); consumeMoveBackUp = true; }
                if (event.getAction() == KeyEvent.ACTION_UP && consumeMoveBackUp) { consumeMoveBackUp = false; }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) { moveMainRowSelection(-1); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { moveMainRowSelection(1); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { confirmMainRowMove(); return true; }
                if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_MENU) return true;
        }
        if ("home".equals(page) && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int index = indexOfCard(homeFavoriteCards, getCurrentFocus());
                if (index >= 0) showMainRowRemoteMenu(index);
                else {
                    int allAppsIndex = indexOfCard(homeAllAppCards, getCurrentFocus());
                    if (allAppsIndex >= 0) showAllAppsRemoteMenu(allAppsIndex);
                }
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && "home".equals(page) && homeScroll != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && homeBackHandled) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN && homeScroll.getScrollY() > 0) {
                homeBackHandled = true;
                if (homeViewportAnimator == null || homeAllAppsTransitionTargetExpanded) returnToFavorites();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && homeBackHandled) { homeBackHandled = false; return true; }
        }
        if ("home".equals(page) && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP)) {
            int key = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_UP && homeNavKeyHandled) { homeNavKeyHandled = false; return true; }
            if (event.getAction() == KeyEvent.ACTION_DOWN && homeNavKeyHandled) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                View focus = getCurrentFocus();
                int favoriteIndex = indexOfCard(homeFavoriteCards, focus);
                int allAppsIndex = indexOfCard(homeAllAppCards, focus);
                boolean inAllApps = allAppsIndex >= 0;
                if (homeViewportAnimator != null && key == KeyEvent.KEYCODE_DPAD_DOWN && !homeAllAppsTransitionTargetExpanded) {
                    if (favoriteIndex >= 0) lastFavoriteFocus = favoriteIndex;
                    homeNavKeyHandled = true; animateHomeViewport(true); return true;
                }
                if (homeViewportAnimator != null && key == KeyEvent.KEYCODE_DPAD_UP && homeAllAppsTransitionTargetExpanded) {
                    rememberFavoriteColumnFromAllApps();
                    homeNavKeyHandled = true; animateHomeViewport(false); return true;
                }
                if (homeViewportAnimator != null) { homeNavKeyHandled = true; return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && favoriteIndex >= 0 && !homeAllAppCards.isEmpty()) {
                    lastFavoriteFocus = favoriteIndex; homeNavKeyHandled = true; animateHomeViewport(true); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP && allAppsIndex >= 0) {
                    homeNavKeyHandled = true;
                    if (allAppsIndex < HOME_APPS_COLUMNS) {
                        rememberFavoriteColumnFromAllApps();
                        animateHomeViewport(false);
                    } else {
                        homeAllAppCards.get(allAppsIndex - HOME_APPS_COLUMNS).requestFocus();
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && allAppsIndex >= 0) {
                    homeNavKeyHandled = true;
                    int below = allAppsIndex + HOME_APPS_COLUMNS;
                    if (below < homeAllAppCards.size()) homeAllAppCards.get(below).requestFocus();
                    return true;
                }
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && "search".equals(page)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (searchKeyboardOpenRequested || isSearchKeyboardVisible())) {
                searchBackHandled = true;
                dismissSearchKeyboard();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && searchBackHandled) {
                searchBackHandled = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showMainRowRemoteMenu(int index) {
        if (index < 0 || index >= favorites.size()) return;
        AppItem selected = favorites.get(index);
        new AlertDialog.Builder(this).setTitle(selected.label)
                .setItems(new String[] {"Move Apps", "Uninstall", "Remove from Main Row", "Cancel"}, (dialog, choice) -> {
                    if (choice == 0) beginMainRowMove(index);
                    else if (choice == 1) uninstallApp(selected, true, index);
                    else if (choice == 2) removeMainRowApp(selected.pkg);
                }).show();
    }

    private void showAllAppsRemoteMenu(int index) {
        if (index < 0 || index >= homeAllApps.size()) return;
        AppItem selected = homeAllApps.get(index);
        new AlertDialog.Builder(this).setTitle(selected.label)
                .setItems(new String[] {"Move to Main Row", "Uninstall", "Cancel"}, (dialog, choice) -> {
                    if (choice == 0) moveAllAppToMainRow(index, selected.pkg);
                    else if (choice == 1) uninstallApp(selected, false, index);
                    else if (index < homeAllAppCards.size()) homeAllAppCards.get(index).requestFocus();
                }).show();
    }

    private void uninstallApp(AppItem item, boolean fromMainRow, int focusIndex) {
        if (item == null) return;
        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", item.pkg, null));
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (uninstall.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "Uninstall is unavailable for " + item.label, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingUninstallPackage = item.pkg;
        pendingUninstallPage = page;
        pendingUninstallFromMainRow = fromMainRow;
        pendingUninstallFocusIndex = focusIndex;
        try { startActivityForResult(uninstall, REQUEST_UNINSTALL_APP); }
        catch (ActivityNotFoundException | SecurityException e) {
            clearPendingUninstall();
            Toast.makeText(this, "Could not uninstall " + item.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishUninstallFlow(int resultCode) {
        String packageName = pendingUninstallPackage;
        String returnPage = pendingUninstallPage;
        int focusIndex = pendingUninstallFocusIndex;
        boolean fromMainRow = pendingUninstallFromMainRow;
        clearPendingUninstall();
        if (packageName == null) return;

        boolean removed = !isPackageInstalled(packageName);
        loadApps();
        String destination = ("apps".equals(returnPage) || "home".equals(returnPage)) ? returnPage : "home";
        showPage(destination);
        final boolean focusMain = fromMainRow;
        final int preferredIndex = Math.max(0, focusIndex);
        pageHost.post(() -> restoreUninstallFocus(focusMain, preferredIndex));
        if (removed) {
            Toast.makeText(this, "App uninstalled", Toast.LENGTH_SHORT).show();
        } else if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Uninstall cancelled", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not uninstall app", Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreUninstallFocus(boolean fromMainRow, int preferredIndex) {
        List<? extends View> cards = fromMainRow ? homeFavoriteCards : homeAllAppCards;
        if (cards.isEmpty()) return;
        int index = Math.min(preferredIndex, cards.size() - 1);
        cards.get(index).requestFocus();
    }

    private void clearPendingUninstall() {
        pendingUninstallPackage = null;
        pendingUninstallPage = null;
        pendingUninstallFocusIndex = -1;
        pendingUninstallFromMainRow = false;
    }

    private boolean isPackageInstalled(String packageName) {
        try { getPackageManager().getApplicationInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void moveAllAppToMainRow(int allAppsIndex, String packageName) {
        if (allAppsIndex < 0 || allAppsIndex >= homeAllApps.size() || favorites.size() != HOME_APPS_COLUMNS) return;
        AppItem selected = homeAllApps.get(allAppsIndex);
        if (!selected.pkg.equals(packageName) || indexOfFavoritePackage(packageName) >= 0) return;

        int replacementIndex = HOME_APPS_COLUMNS - 1;
        AppItem displaced = favorites.set(replacementIndex, selected);
        homeAllApps.remove(allAppsIndex);
        homeAllApps.add(displaced);

        List<String> mainOrder = new ArrayList<>();
        for (AppItem item : favorites) if (!mainOrder.contains(item.pkg)) mainOrder.add(item.pkg);
        if (mainOrder.size() != HOME_APPS_COLUMNS) return;
        prefs.edit().putString(MAIN_ROW_KEY, joinPackages(mainOrder))
                .putString(ALL_APPS_ORDER_KEY, packageOrder(homeAllApps)).apply();

        loadApps();
        showPage("home");
        pageHost.post(() -> {
            if (homeFavoriteCards.size() == HOME_APPS_COLUMNS) homeFavoriteCards.get(replacementIndex).requestFocus();
        });
        Toast.makeText(this, selected.label + " moved to Main Row; " + displaced.label + " moved to All Apps", Toast.LENGTH_SHORT).show();
    }

    private String packageOrder(List<AppItem> items) {
        List<String> packages = new ArrayList<>();
        for (AppItem item : items) if (!packages.contains(item.pkg)) packages.add(item.pkg);
        return joinPackages(packages);
    }

    private void beginMainRowMove(int index) {
        if (index < 0 || index >= homeFavoriteCards.size()) return;
        mainRowOrderBeforeMove = new ArrayList<>(favorites);
        movingMainRowIndex = index;
        movingMainRowApp = true;
        showMainRowMoveBadge();
        homeFavoriteCards.get(index).requestFocus();
        Toast.makeText(this, "Move Apps: use LEFT/RIGHT, SELECT to save, BACK to cancel", Toast.LENGTH_LONG).show();
    }

    private void moveMainRowSelection(int delta) {
        int target = movingMainRowIndex + delta;
        if (target < 0 || target >= favorites.size()) return;
        Collections.swap(favorites, movingMainRowIndex, target);
        Collections.swap(homeFavoriteCards, movingMainRowIndex, target);
        Collections.swap(homeFavoriteCells, movingMainRowIndex, target);
        GlassCard selected = homeFavoriteCards.get(target);
        FrameLayout cell = homeFavoriteCells.get(target);
        LinearLayout strip = (LinearLayout) cell.getParent();
        strip.removeView(cell); strip.addView(cell, target);
        movingMainRowIndex = target;
        selected.requestFocus();
        showMainRowMoveBadge();
        cell.announceForAccessibility("Position " + (target + 1) + " of " + favorites.size());
    }

    private void showMainRowMoveBadge() {
        for (int i = 0; i < homeFavoriteCards.size(); i++) {
            TextView badge = (TextView) homeFavoriteCards.get(i).findViewWithTag("main-row-position");
            if (badge == null) continue;
            if (i == movingMainRowIndex) {
                badge.setText((i + 1) + " / " + favorites.size()); badge.setVisibility(View.VISIBLE);
            } else badge.setVisibility(View.GONE);
        }
    }

    private void confirmMainRowMove() {
        List<String> order = new ArrayList<>();
        for (AppItem item : favorites) if (!order.contains(item.pkg)) order.add(item.pkg);
        prefs.edit().putString(MAIN_ROW_KEY, joinPackages(order)).apply();
        movingMainRowApp = false; movingMainRowIndex = -1; mainRowOrderBeforeMove = null;
        showMainRowMoveBadge();
        Toast.makeText(this, "Main Row order saved", Toast.LENGTH_SHORT).show();
    }

    private void cancelMainRowMove() {
        String keepFocusedPackage = movingMainRowIndex >= 0 && movingMainRowIndex < favorites.size()
                ? favorites.get(movingMainRowIndex).pkg : null;
        if (mainRowOrderBeforeMove != null) { favorites.clear(); favorites.addAll(mainRowOrderBeforeMove); }
        movingMainRowApp = false; movingMainRowIndex = -1; mainRowOrderBeforeMove = null;
        showPage("home");
        if (keepFocusedPackage != null) root.post(() -> requestMainRowFocus(keepFocusedPackage));
    }

    private void requestMainRowFocus(String pkg) {
        for (int i = 0; i < favorites.size() && i < homeFavoriteCards.size(); i++)
            if (favorites.get(i).pkg.equals(pkg)) { homeFavoriteCards.get(i).requestFocus(); return; }
    }

    private void removeMainRowApp(String pkg) {
        List<String> order = splitPackages(prefs.getString(MAIN_ROW_KEY, ""));
        order.remove(pkg);
        for (AppItem candidate : eligibleMainRowApps) {
            if (order.size() >= HOME_APPS_COLUMNS) break;
            if (!candidate.pkg.equals(pkg) && !order.contains(candidate.pkg)) order.add(candidate.pkg);
        }
        if (order.size() > HOME_APPS_COLUMNS) order = new ArrayList<>(order.subList(0, HOME_APPS_COLUMNS));
        prefs.edit().putString(MAIN_ROW_KEY, joinPackages(order)).apply();
        loadApps(); showPage("home");
    }

    @Override protected void onDestroy() {
        stopWallpaperTransfer();
        wallpaperBlurGeneration++;
        if (packageChangeReceiver != null) {
            unregisterReceiver(packageChangeReceiver);
            packageChangeReceiver = null;
        }
        mainHandler.removeCallbacks(refreshInstalledApps);
        if (imeLayoutListener != null && root != null) {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(imeLayoutListener);
        }
        super.onDestroy();
    }

    private void registerPackageChangeReceiver() {
        packageChangeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Uri changed = intent.getData();
                if (changed != null && getPackageName().equals(changed.getSchemeSpecificPart())) return;
                if (pendingUninstallPackage != null && changed != null
                        && pendingUninstallPackage.equals(changed.getSchemeSpecificPart())) return;
                mainHandler.removeCallbacks(refreshInstalledApps);
                mainHandler.post(refreshInstalledApps);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageChangeReceiver, filter);
    }

    private void loadApps() {
        apps.clear(); homeAllApps.clear(); favorites.clear();
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, Intent> launchables = new LinkedHashMap<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(leanback, 0)) {
            if (info.activityInfo == null || info.activityInfo.packageName.equals(getPackageName())) continue;
            launchables.put(info.activityInfo.packageName, leanback.cloneFilter().setClassName(
                    info.activityInfo.packageName, info.activityInfo.name));
        }
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null || info.activityInfo.packageName.equals(getPackageName())) continue;
            launchables.putIfAbsent(info.activityInfo.packageName, launcher.cloneFilter().setClassName(
                    info.activityInfo.packageName, info.activityInfo.name));
        }
        for (String pkg : APP_ORDER) {
            if (EXCLUDED_ALL_APP_PACKAGES.contains(pkg)) { launchables.remove(pkg); continue; }
            Intent intent = launchables.remove(pkg);
            if (intent == null && "com.amazon.firebat".equals(pkg)) intent = appIntent(pkg);
            AppItem item = intent == null ? null : app(pkg, intent);
            if (item != null && !isExcludedAllApp(item)) apps.add(item);
        }
        List<String> remainder = new ArrayList<>(launchables.keySet());
        Collections.sort(remainder, (a, b) -> labelFor(a).compareToIgnoreCase(labelFor(b)));
        for (String pkg : remainder) {
            if (EXCLUDED_ALL_APP_PACKAGES.contains(pkg) || isExcludedAppLabel(labelFor(pkg))) continue;
            Intent launch = launchables.get(pkg);
            if ("com.amazon.venezia".equals(pkg)) {
                Intent amazonStore = appIntent(pkg);
                if (amazonStore != null && amazonStore.resolveActivity(pm) != null) launch = amazonStore;
            }
            AppItem item = app(pkg, launch);
            if (item != null && !isExcludedAllApp(item)) apps.add(item);
        }
        eligibleMainRowApps.clear();
        for (AppItem item : apps) if (isEligibleMainRowApp(item)) eligibleMainRowApps.add(item);
        sortMainRowCandidates(eligibleMainRowApps);
        if (!prefs.contains(MAIN_ROW_KEY)) {
            List<String> detected = new ArrayList<>();
            for (AppItem item : eligibleMainRowApps) {
                if (detected.size() >= HOME_APPS_COLUMNS) break;
                detected.add(item.pkg);
            }
            prefs.edit().putString(MAIN_ROW_KEY, joinPackages(detected)).apply();
        }
        List<String> selectedPackages = splitPackages(prefs.getString(MAIN_ROW_KEY, ""));
        for (String pkg : selectedPackages) {
            if (favorites.size() >= HOME_APPS_COLUMNS) break;
            for (AppItem item : eligibleMainRowApps) if (pkg.equals(item.pkg)) { favorites.add(item); break; }
        }
        for (AppItem item : eligibleMainRowApps) {
            if (favorites.size() >= HOME_APPS_COLUMNS) break;
            if (indexOfFavoritePackage(item.pkg) < 0) favorites.add(item);
        }
        List<String> visibleFavoritePackages = new ArrayList<>();
        for (AppItem item : favorites) visibleFavoritePackages.add(item.pkg);
        if (!visibleFavoritePackages.equals(selectedPackages))
            prefs.edit().putString(MAIN_ROW_KEY, joinPackages(visibleFavoritePackages)).apply();
        HashSet<String> favoritePackages = new HashSet<>(visibleFavoritePackages);
        for (AppItem item : apps) {
            if (!favoritePackages.contains(item.pkg) && !isExcludedAllApp(item)) homeAllApps.add(item);
        }
        applyPersistedAllAppsOrder();
    }

    private int indexOfFavoritePackage(String pkg) {
        for (int i = 0; i < favorites.size(); i++) if (favorites.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private boolean isEligibleMainRowApp(AppItem item) {
        // Every supported launchable in All Apps can occupy a Main Row slot.
        return item != null && !isExcludedAllApp(item);
    }

    private boolean isPriorityStreamingPackage(String pkg) {
        for (String candidate : STREAMING_PRIORITY) if (candidate.equals(pkg)) return true;
        return false;
    }

    private void sortMainRowCandidates(List<AppItem> candidates) {
        Collections.sort(candidates, (a, b) -> {
            int ar = priorityRank(a.pkg), br = priorityRank(b.pkg);
            return ar != br ? Integer.compare(ar, br) : a.label.compareToIgnoreCase(b.label);
        });
    }

    private int priorityRank(String pkg) {
        for (int i = 0; i < STREAMING_PRIORITY.length; i++) if (STREAMING_PRIORITY[i].equals(pkg)) return i;
        return STREAMING_PRIORITY.length;
    }

    private String joinPackages(List<String> packages) { return android.text.TextUtils.join("\n", packages); }
    private List<String> splitPackages(String value) {
        List<String> packages = new ArrayList<>();
        if (value == null || value.isEmpty()) return packages;
        for (String pkg : value.split("\\n")) if (!pkg.trim().isEmpty() && !packages.contains(pkg.trim())) packages.add(pkg.trim());
        return packages;
    }

    private boolean isExcludedAllApp(AppItem item) {
        return item == null || EXCLUDED_ALL_APP_PACKAGES.contains(item.pkg) || isExcludedAppLabel(item.label);
    }

    private boolean isExcludedAppLabel(String label) {
        String normalized = label == null ? "" : label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return EXCLUDED_ALL_APP_LABELS.contains(normalized);
    }

    private void applyPersistedAllAppsOrder() {
        List<String> savedOrder = splitPackages(prefs.getString(ALL_APPS_ORDER_KEY, ""));
        if (savedOrder.isEmpty()) return;
        List<AppItem> ordered = new ArrayList<>();
        HashSet<String> included = new HashSet<>();
        for (String pkg : savedOrder) {
            for (AppItem item : homeAllApps) {
                if (item.pkg.equals(pkg) && included.add(pkg)) { ordered.add(item); break; }
            }
        }
        for (AppItem item : homeAllApps) if (included.add(item.pkg)) ordered.add(item);
        homeAllApps.clear(); homeAllApps.addAll(ordered);
    }

    private String labelFor(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private Intent appIntent(String pkg) {
        if ("com.amazon.venezia".equals(pkg)) {
            Intent findStore = new Intent("com.amazon.venezia.ade.FIND_LAUNCH").setPackage(pkg);
            if (findStore.resolveActivity(getPackageManager()) != null) return findStore;
            // Amazon's documented Appstore deep link uses s= for its search page.
            Intent storeSearch = new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?s="))
                    .setPackage(pkg);
            if (storeSearch.resolveActivity(getPackageManager()) != null) return storeSearch;
            String[] searchActions = {Intent.ACTION_SEARCH, "com.amazon.venezia.action.SEARCH", "com.amazon.venezia.SEARCH"};
            for (String action : searchActions) {
                Intent search = new Intent(action).setPackage(pkg);
                search.putExtra("query", "");
                search.putExtra("android.app.SearchManager.QUERY", "");
                if (search.resolveActivity(getPackageManager()) != null) return search;
            }
            return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, "com.amazon.venezia.grid.AppsGridLauncherActivity"));
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null && "com.amazon.firebat".equals(pkg)) launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(new ComponentName(pkg, "com.amazon.pyrocore.IgnitionActivity"));
        return launch;
    }

    private AppItem app(String pkg) {
        return app(pkg, appIntent(pkg));
    }

    private AppItem app(String pkg, Intent launch) {
        PackageManager pm = getPackageManager();
        if (launch == null || launch.resolveActivity(pm) == null) return null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            String label = "com.amazon.firebat".equals(pkg) ? "Prime Video" : pm.getApplicationLabel(info).toString();
            Drawable banner = null;
            try { banner = pm.getActivityBanner(launch); } catch (RuntimeException | PackageManager.NameNotFoundException ignored) { }
            if (banner == null) { try { banner = pm.getApplicationBanner(pkg); } catch (RuntimeException ignored) { } }
            Drawable icon = pm.getApplicationIcon(info);
            // Ask the app's own resources for its highest-density artwork instead of the device-density copy,
            // so icons and banners stay sharp when they fill a tile.
            try {
                android.content.res.Resources res = pm.getResourcesForApplication(pkg);
                int iconRes = 0, bannerRes = 0;
                try {
                    android.content.pm.ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                    iconRes = ai.icon; bannerRes = ai.banner;
                } catch (RuntimeException | PackageManager.NameNotFoundException ignored) { }
                if (iconRes == 0) iconRes = info.icon;
                if (bannerRes == 0) bannerRes = info.banner;
                if (bannerRes != 0) {
                    Drawable hiBanner = res.getDrawableForDensity(bannerRes, android.util.DisplayMetrics.DENSITY_XXHIGH, null);
                    if (hiBanner != null) banner = hiBanner;
                }
                if (iconRes != 0) {
                    Drawable hiIcon = res.getDrawableForDensity(iconRes, android.util.DisplayMetrics.DENSITY_XXHIGH, null);
                    if (hiIcon != null) icon = hiIcon;
                }
            } catch (RuntimeException | PackageManager.NameNotFoundException ignored) { }
            return new AppItem(pkg, label, launch, icon, banner);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void buildShell() {
        root = new FrameLayout(this);
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperBlurView = new ImageView(this);
        wallpaperBlurView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperBlurView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wallpaperBlurView.setAlpha(0f);
        root.addView(wallpaperBlurView, new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperTransitionShade = new View(this);
        wallpaperTransitionShade.setBackgroundColor(Color.BLACK);
        wallpaperTransitionShade.setAlpha(0f);
        root.addView(wallpaperTransitionShade, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setClipChildren(false); shell.setClipToPadding(false);
        root.addView(shell, new FrameLayout.LayoutParams(MATCH, MATCH));

        pageHost = new LinearLayout(this); pageHost.setOrientation(LinearLayout.VERTICAL);
        pageHost.setClipChildren(false); pageHost.setClipToPadding(false);
        shell.addView(pageHost, new LinearLayout.LayoutParams(MATCH, MATCH));

        navExpandedSize = dp(44); navCompactSize = dp(40);
        floatingNav = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        floatingNav.setGravity(Gravity.CENTER_VERTICAL);
        floatingNav.setClipChildren(false); floatingNav.setClipToPadding(false);
        floatingNav.setPadding(0, dp(8), 0, dp(8));
        addFloatingButton("Wi-Fi", "wifi", NavGlyph.WIFI);
        addFloatingButton("Remotes & Bluetooth Devices", "bluetooth", NavGlyph.BLUETOOTH);
        addFloatingButton("Settings", "settings", NavGlyph.SETTINGS);
        addFloatingButton("Fire TV Settings", "fire-settings", NavGlyph.FIRE_SETTINGS);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(WRAP, navExpandedSize + dp(16), Gravity.TOP | Gravity.RIGHT);
        navLp.topMargin = dp(22); navLp.rightMargin = dp(30);
        root.addView(floatingNav, navLp);
        setContentView(root); setWallpaper();
        imeLayoutListener = () -> {
            Rect visibleFrame = new Rect(); getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            boolean wasVisible = searchImeVisible;
            searchImeVisible = screenHeight - visibleFrame.bottom > dp(150);
            if ("search".equals(page) && wasVisible && !searchImeVisible) {
                searchKeyboardOpenRequested = false;
                searchKeyboardSuppressed = true;
                if (searchField != null) {
                    searchField.setShowSoftInputOnFocus(false);
                    searchField.requestFocus();
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(imeLayoutListener);
    }

    private void addFloatingButton(String label, String target, int glyph) {
        GlassCard button = glass(22); button.setFloatingStyle(true);
        button.setAmbientColor(0); button.setContentDescription(label);
        NavGlyphView icon = new NavGlyphView(this, glyph);
        button.addView(icon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        button.setOnClickListener(v -> {
            if ("fire-settings".equals(target)) openFireSettings();
            else if ("wifi".equals(target)) openSystemSettings(new String[] { Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS }, new String[] { "wifi", "network" });
            else if ("bluetooth".equals(target)) openRemoteBluetoothSettings();
            else showPage(target);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(navExpandedSize, navExpandedSize);
        lp.leftMargin = dp(10); lp.rightMargin = dp(3);
        floatingNav.addView(button, lp); navButtons.add(button); navTargets.add(target);
    }

    private void showPage(String requested) {
        if (requested == null || !Arrays.asList("home", "apps", "search", "settings", "background").contains(requested)) requested = "home";
        if (!"home".equals(requested)) applyWallpaperMotion(0f);
        if (!"search".equals(requested)) hideSearchKeyboard();
        else { searchKeyboardSuppressed = false; searchKeyboardOpenRequested = false; }
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (homeClockTick != null) mainHandler.removeCallbacks(homeClockTick);
        homeClockView = null;
        homeHeroView = null;
        final boolean pageChanged = !requested.equals(page);
        page = requested; pageHost.removeAllViews(); searchField = null;
        if (floatingNav != null) floatingNav.animate().cancel();
        applyNavScale(0f);
        for (int i = 0; i < navButtons.size(); i++) {
            navButtons.get(i).setActive(("settings".equals(page) || "background".equals(page)) && "settings".equals(navTargets.get(i)));
        }
        switch (page) {
            case "apps": buildApps(); break;
            case "search": buildSearch(); break;
            case "settings": buildSettings(); break;
            case "background": buildBackgrounds(); break;
            default: buildHome(); break;
        }
        pageHost.animate().cancel();
        if (pageChanged) { // rebuilds of the same page (e.g. after picking a colour) must not replay the entrance
            pageHost.setAlpha(0f); pageHost.setTranslationY(dp(10));
            pageHost.animate().alpha(1f).translationY(0f).setDuration(MOTION_PAGE_MS).setInterpolator(EASE_OUT).start();
        } else { pageHost.setAlpha(1f); pageHost.setTranslationY(0f); }
        pageHost.post(() -> {
            View v = "search".equals(page) && searchField != null ? searchField
                    : "home".equals(page) && !homeFavoriteCards.isEmpty() ? homeFavoriteCards.get(0) : firstFocusable(pageHost);
            if (v != null) v.requestFocus();
            if ("search".equals(page) && searchField != null) showSearchKeyboard();
        });
    }

    private View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) {
            View found = firstFocusable(((ViewGroup)v).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private void buildHome() {
        homeFavoriteCards.clear(); homeFavoriteCells.clear(); homeAllAppCards.clear(); lastFavoriteFocus = 0;
        homeHeroCollapseFraction = 0f;
        homeFavoriteStrip = null;
        homeChromeAutoHide = homeAllApps.size() > HOME_APPS_COLUMNS * 2;
        if (floatingNav != null) { floatingNav.setVisibility(View.VISIBLE); floatingNav.setAlpha(1f); }
        if (homeViewportAnimator != null) { ValueAnimator old = homeViewportAnimator; homeViewportAnimator = null; old.cancel(); }
        homeAllAppsExpanded = false; homeAllAppsBlur = null;
        applyWallpaperMotion(0f);
        homeScroll = new HomeScrollView(this);
        homeScroll.setFillViewport(true); homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setClipChildren(false); homeScroll.setClipToPadding(false);
        homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER); homeScroll.setFocusable(false); homeScroll.setFocusableInTouchMode(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false); content.setClipToPadding(false);
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL);
        homeHeroView = hero;
        hero.setPivotX(0f); hero.setPivotY(0f);
        hero.setLayerType(View.LAYER_TYPE_HARDWARE, null); // alpha + scale of text-with-shadow without re-rendering every frame
        homeClockView = text("", 42, 0xFFF6F8FF, Typeface.create("sans-serif-light", 0));
        // Tabular numerals keep the clock visually steady as minutes change.
        homeClockView.setFontFeatureSettings("'tnum'");
        homeClockView.setShadowLayer(dp(10), 0, dp(2), 0xAA000000);
        homeGreetingView = text(greetingText(), 24, 0xEAF6F8FF, Typeface.create("sans-serif-light", 0));
        TextView title = homeGreetingView;
        title.setShadowLayer(dp(8), 0, dp(2), 0x99000000);
        hero.addView(homeClockView);
        LinearLayout.LayoutParams greetingLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        greetingLp.topMargin = dp(5); hero.addView(title, greetingLp);
        hero.setPadding(dp(44), dp(34), dp(24), 0);
        content.addView(hero, new LinearLayout.LayoutParams(MATCH, WRAP));
        updateHomeClock();

        LinearLayout strip = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        strip.setGravity(Gravity.CENTER_HORIZONTAL);
        homeFavoriteStrip = strip;
        strip.setClipChildren(false); strip.setClipToPadding(false); strip.setPadding(dp(34), dp(8), dp(34), dp(8));
        int homeTileWidth = homeAppTileWidthPixels();
        int[] favoriteTileHeights = new int[favorites.size()];
        int favoriteRowTileHeight = 0;
        for (int i = 0; i < favorites.size(); i++) {
            favoriteTileHeights[i] = appTileHeightPixels(favorites.get(i), homeTileWidth);
            favoriteRowTileHeight = Math.max(favoriteRowTileHeight, favoriteTileHeights[i]);
        }
        for (int i = 0; i < favorites.size(); i++) {
            AppItem item = favorites.get(i);
            int tileHeight = favoriteTileHeights[i];
            GlassCard c = appCard(item, homeTileWidth, tileHeight);
            c.setId(View.generateViewId());
            homeFavoriteCards.add(c);
            FrameLayout cell = new FrameLayout(this); cell.setClipChildren(false); cell.setClipToPadding(false);
            cell.addView(labeledTile(c, item, tileHeight, favoriteRowTileHeight), new FrameLayout.LayoutParams(homeTileWidth,
                    favoriteRowTileHeight + tileLabelBlock(), Gravity.CENTER));
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0,
                    favoriteRowTileHeight + tileLabelBlock(), 1f); cellLp.leftMargin = dp(6); cellLp.rightMargin = dp(6);
            strip.addView(cell, cellLp);
            homeFavoriteCells.add(cell);
        }
        for (int i = 0; i < homeFavoriteCards.size(); i++) {
            GlassCard card = homeFavoriteCards.get(i);
            card.setNextFocusLeftId(homeFavoriteCards.get(Math.max(0, i - 1)).getId());
            card.setNextFocusRightId(homeFavoriteCards.get(Math.min(homeFavoriteCards.size() - 1, i + 1)).getId());
        }
        bindNavToScroll(homeScroll);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(MATCH,
                favoriteRowTileHeight + tileLabelBlock() + dp(16)); rowLp.topMargin = dp(206);
        content.addView(strip, rowLp);

        homeAllAppsSection = new FrameLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; } // alpha without a per-frame offscreen layer
        };
        LinearLayout allAppsContent = new LinearLayout(this); allAppsContent.setOrientation(LinearLayout.VERTICAL);
        allAppsContent.setClipChildren(false); allAppsContent.setClipToPadding(false);
        TextView allTitle = text("All Apps", 22, 0xFFF4F7FF, Typeface.create("sans-serif-light", 0));
        allTitle.setId(View.generateViewId());
        homeAllAppsTitle = allTitle;
        allTitle.setPadding(dp(44), dp(10), dp(24), dp(8));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(MATCH, WRAP); titleLp.topMargin = dp(5);
        allAppsContent.addView(allTitle, titleLp);
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(34), 0, dp(34), dp(28)); grid.setClipChildren(false); grid.setClipToPadding(false);
        int countPerRow = HOME_APPS_COLUMNS;
        for (int start = 0; start < homeAllApps.size(); start += countPerRow) {
            LinearLayout row = new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false);
            int rowTileHeight = 0;
            for (int col = 0; col < countPerRow && start + col < homeAllApps.size(); col++)
                rowTileHeight = Math.max(rowTileHeight, appTileHeightPixels(homeAllApps.get(start + col), homeTileWidth));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(MATCH, rowTileHeight + tileLabelBlock()); if (start > 0) rp.topMargin = dp(8);
            for (int col = 0; col < countPerRow; col++) {
                int ix = start + col;
                if (ix < homeAllApps.size()) {
                    AppItem item = homeAllApps.get(ix);
                    int tileHeight = appTileHeightPixels(item, homeTileWidth);
                    GlassCard card = appCard(item, homeTileWidth, tileHeight); card.setId(View.generateViewId());
                    card.setFocusable(false); card.setFocusableInTouchMode(false);
                    homeAllAppCards.add(card);
                    FrameLayout cell = new FrameLayout(this); cell.setClipChildren(false); cell.setClipToPadding(false);
                    cell.addView(labeledTile(card, item, tileHeight, rowTileHeight), new FrameLayout.LayoutParams(homeTileWidth,
                            rowTileHeight + tileLabelBlock(), Gravity.CENTER));
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                    cp.leftMargin = dp(6); cp.rightMargin = dp(6); row.addView(cell, cp);
                } else {
                    // Preserve the same gutters as real cards, so partial final rows
                    // keep the exact column centers used by every full row.
                    FrameLayout spacer = new FrameLayout(this);
                    LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                    spacerLp.leftMargin = dp(6); spacerLp.rightMargin = dp(6);
                    row.addView(spacer, spacerLp);
                }
            }
            grid.addView(row, rp);
        }
        if (!homeFavoriteCards.isEmpty() && !homeAllAppCards.isEmpty()) {
            for (int i = 0; i < homeFavoriteCards.size(); i++) {
                int targetIndex = Math.min(i, homeAllAppCards.size() - 1);
                homeFavoriteCards.get(i).setNextFocusDownId(homeAllAppCards.get(targetIndex).getId());
            }
            for (int i = 0; i < homeAllAppCards.size(); i++) {
                GlassCard card = homeAllAppCards.get(i);
                int rowStart = (i / HOME_APPS_COLUMNS) * HOME_APPS_COLUMNS;
                int rowEnd = Math.min(rowStart + HOME_APPS_COLUMNS - 1, homeAllAppCards.size() - 1);
                card.setNextFocusLeftId(homeAllAppCards.get(Math.max(rowStart, i - 1)).getId());
                card.setNextFocusRightId(homeAllAppCards.get(Math.min(rowEnd, i + 1)).getId());
                int favoriteIndex = Math.min(i % HOME_APPS_COLUMNS, homeFavoriteCards.size() - 1);
                int upIndex = i < HOME_APPS_COLUMNS ? -1
                        : Math.min(i - HOME_APPS_COLUMNS, homeAllAppCards.size() - 1);
                card.setNextFocusUpId(upIndex >= 0
                        ? homeAllAppCards.get(upIndex).getId()
                        : homeFavoriteCards.get(favoriteIndex).getId());
            }
        }
        allAppsContent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        homeAllAppsSection.addView(allAppsContent, new FrameLayout.LayoutParams(MATCH, WRAP));
        homeAllAppsSection.setAlpha(.78f);
        LinearLayout.LayoutParams allSectionLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        content.addView(homeAllAppsSection, allSectionLp);
        homeScroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        pageHost.addView(homeScroll, new LinearLayout.LayoutParams(MATCH, MATCH));
        scheduleAllAppsBlur(homeAllAppsSection, 0);
    }

    private int homeAppTileWidthPixels() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int nonTileSpace = dp(34) * 2 + HOME_APPS_COLUMNS * dp(12);
        return Math.min(dp(172), Math.max(1, (screenWidth - nonTileSpace) / HOME_APPS_COLUMNS));
    }

    private int appTileHeightPixels(AppItem item, int tileWidth) {
        // Match the reference's landscape card ratio at the responsive six-column
        // width. Every Main Row and All Apps card gets this same computed height.
        return Math.max(1, Math.round(tileWidth / HOME_TILE_ASPECT_RATIO));
    }

    private void updateHomeClock() {
        if (homeClockView == null || !"home".equals(page)) return;
        Calendar now = Calendar.getInstance();
        homeClockView.setText(DateFormat.getTimeFormat(this).format(now.getTime()));
        long delay = 60000L - (now.getTimeInMillis() % 60000L);
        mainHandler.removeCallbacks(homeClockTick);
        mainHandler.postDelayed(homeClockTick, Math.max(1000L, delay));
    }

    private boolean viewportMotionBusy() {
        return homeViewportAnimator != null || (homeScroll != null && homeScroll.isGliding());
    }

    private void animateHomeViewport(boolean expand) {
        if (homeScroll == null || homeAllAppCards.isEmpty() || homeAllAppsSection == null) return;
        if (homeViewportAnimator != null) {
            ValueAnimator old = homeViewportAnimator;
            homeViewportAnimator = null;
            homeScroll.setSuppressFocusAutoScroll(false);
            old.cancel();
        }
        homeAllAppsTargetScroll = Math.max(0, homeAllAppsSection.getTop() - dp(4));
        final int start = homeScroll.getScrollY();
        final int end = expand ? homeAllAppsTargetScroll : 0;
        homeAllAppsTransitionTargetExpanded = expand;
        homeScroll.setSuppressFocusAutoScroll(true);
        if (homeChromeAutoHide && !expand && floatingNav != null) {
            floatingNav.setVisibility(View.VISIBLE);
        }
        if (expand) {
            for (GlassCard card : homeAllAppCards) { card.setFocusable(true); card.setFocusableInTouchMode(true); }
            if (!homeAllAppCards.isEmpty()) {
                homeAllAppCards.get(Math.min(lastFavoriteFocus, homeAllAppCards.size() - 1)).requestFocus();
            }
        } else if (!homeFavoriteCards.isEmpty()) {
            homeFavoriteCards.get(Math.max(0, Math.min(lastFavoriteFocus, homeFavoriteCards.size() - 1))).requestFocus();
        }
        // Keep one shared curve for the hero, icons, and background so they stay in sync.
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        homeViewportAnimator = animator;
        final float startFraction = homeAllAppsTargetScroll <= 0 ? 0f
                : Math.max(0f, Math.min(1f, start / (float) homeAllAppsTargetScroll));
        final float endFraction = expand ? 1f : 0f;
        float travelShare = Math.abs(endFraction - startFraction);
        final long duration = Math.round(MOTION_VIEWPORT_MS * Math.max(.75f, Math.min(1f, travelShare)));
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animateHomeChrome(expand, endFraction, duration);
        animateWallpaperMotion(expand, duration);
        final View glowAnchor = getCurrentFocus();
        final boolean glowFollows = ambientGlow != null && (homeFavoriteCards.contains(glowAnchor) || homeAllAppCards.contains(glowAnchor));
        animator.addUpdateListener(a -> {
            float raw = (Float) a.getAnimatedValue();
            float eased = EASE_MOVE.getInterpolation(raw);
            float fraction = startFraction + (endFraction - startFraction) * eased;
            int y = Math.round(homeAllAppsTargetScroll * fraction);
            homeScroll.scrollTo(0, y);
            homeHeroCollapseFraction = fraction;
            updateHomeHeroForScroll(homeScroll.getScrollY());
            if (homeFavoriteStrip != null) homeFavoriteStrip.setAlpha(homeChromeAutoHide ? 1f - fraction : 1f);
            homeAllAppsSection.setAlpha(.78f + .22f * fraction);
            if (homeAllAppsBlur != null) {
                // INVISIBLE, never GONE: toggling GONE forces a full relayout in the middle of the scroll (the upward jitter).
                int blurVisibility = fraction >= 1f ? View.INVISIBLE : View.VISIBLE;
                if (homeAllAppsBlur.getVisibility() != blurVisibility) homeAllAppsBlur.setVisibility(blurVisibility);
                homeAllAppsBlur.setAlpha(1f - fraction);
            }
            if (glowFollows) ambientGlow.follow(glowAnchor);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (homeViewportAnimator != animator) return;
                homeViewportAnimator = null;
                homeScroll.finishFocusTransition();
                if (glowFollows) ambientGlow.settle(glowAnchor);
                homeAllAppsExpanded = expand;
                homeHeroCollapseFraction = expand ? 1f : 0f;
                updateHomeHeroForScroll(homeScroll.getScrollY());
                if (homeFavoriteStrip != null) homeFavoriteStrip.setAlpha(homeChromeAutoHide && expand ? 0f : 1f);
                if (floatingNav != null) {
                    floatingNav.setAlpha(homeChromeAutoHide && expand ? 0f : 1f);
                    floatingNav.setVisibility(homeChromeAutoHide && expand ? View.INVISIBLE : View.VISIBLE);
                }
                if (!expand) {
                    for (GlassCard card : homeAllAppCards) { card.setFocusable(false); card.setFocusableInTouchMode(false); }
                }
            }
        });
        animator.start();
    }

    private void revealAllApps() { animateHomeViewport(true); }
    private void returnToFavorites() { animateHomeViewport(false); }

    private void animateHomeChrome(boolean expand, float targetFraction, long duration) {
        float heroScale = 1f - (1f - HOME_HERO_COMPACT_SCALE) * targetFraction;
        float chromeAlpha = homeChromeAutoHide ? 1f - targetFraction : 1f;
        if (homeHeroView != null) {
            homeHeroView.animate().cancel();
            homeHeroView.animate().scaleX(heroScale).scaleY(heroScale).alpha(chromeAlpha)
                    .setDuration(duration).setInterpolator(EASE_MOVE).start();
        }
        if (floatingNav != null) {
            float navScale = 1f - (1f - navCompactSize / (float) navExpandedSize) * targetFraction;
            setFloatingNavPivot();
            floatingNav.animate().cancel();
            floatingNav.animate().scaleX(navScale).scaleY(navScale).alpha(chromeAlpha)
                    .setDuration(duration).setInterpolator(EASE_MOVE).start();
        }
    }

    private void animateWallpaperMotion(boolean expand, long duration) {
        float amount = expand ? 1f : 0f;
        float scale = 1f + .13f * amount;
        float parallaxY = -dp(4f) * amount;
        wallpaperBlurAmount = .18f * amount;
        if (wallpaperView != null) {
            wallpaperView.animate().cancel();
            wallpaperView.animate().scaleX(scale).scaleY(scale).translationY(parallaxY)
                    .setDuration(duration).setInterpolator(EASE_MOVE).start();
        }
        if (wallpaperBlurView != null) {
            wallpaperBlurView.animate().cancel();
            wallpaperBlurView.animate().scaleX(scale).scaleY(scale).translationY(parallaxY)
                    .alpha(wallpaperBlurAmount).setDuration(duration).setInterpolator(EASE_MOVE).start();
        }
        if (wallpaperTransitionShade != null) {
            wallpaperTransitionShade.animate().cancel();
            wallpaperTransitionShade.animate().alpha(.065f * amount)
                    .setDuration(duration).setInterpolator(EASE_MOVE).start();
        }
    }

    private int indexOfCard(List<GlassCard> cards, View focused) {
        View candidate = focused;
        while (candidate != null) {
            int index = cards.indexOf(candidate);
            if (index >= 0) return index;
            android.view.ViewParent parent = candidate.getParent();
            candidate = parent instanceof View ? (View) parent : null;
        }
        return -1;
    }

    private void rememberFavoriteColumnFromAllApps() {
        if (homeFavoriteCards.isEmpty()) return;
        int index = indexOfCard(homeAllAppCards, getCurrentFocus());
        if (index >= 0) lastFavoriteFocus = Math.min(index % HOME_APPS_COLUMNS, homeFavoriteCards.size() - 1);
    }

    /** Waits until the section has a real size (retrying), so the blur is never silently skipped. */
    private void scheduleAllAppsBlur(final FrameLayout section, final int attempt) {
        section.postDelayed(() -> {
            if (section != homeAllAppsSection) return; // page was rebuilt meanwhile
            if (section.getWidth() <= 0 || section.getHeight() <= 0 || !section.isAttachedToWindow()) {
                if (attempt < 25) scheduleAllAppsBlur(section, attempt + 1);
                return;
            }
            try { createAllAppsBlurPreview(); } catch (RuntimeException | OutOfMemoryError e) { if (attempt < 3) scheduleAllAppsBlur(section, attempt + 1); }
        }, attempt == 0 ? 60 : 120);
    }

    /** Pure-Java box blur fallback (3 passes) for devices where RenderScript fails. */
    private void boxBlur(Bitmap bmp, int r) {
        int w = bmp.getWidth(), h = bmp.getHeight(); int[] px = new int[w * h], tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int a=0,rr=0,g=0,b=0,n=0;
                for (int k = -r; k <= r; k++) { int xx = Math.min(w-1, Math.max(0, x+k)); int c = px[y*w+xx]; a+=c>>>24; rr+=(c>>16)&255; g+=(c>>8)&255; b+=c&255; n++; }
                tmp[y*w+x] = ((a/n)<<24)|((rr/n)<<16)|((g/n)<<8)|(b/n);
            }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int a=0,rr=0,g=0,b=0,n=0;
                for (int k = -r; k <= r; k++) { int yy = Math.min(h-1, Math.max(0, y+k)); int c = tmp[yy*w+x]; a+=c>>>24; rr+=(c>>16)&255; g+=(c>>8)&255; b+=c&255; n++; }
                px[y*w+x] = ((a/n)<<24)|((rr/n)<<16)|((g/n)<<8)|(b/n);
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private void createAllAppsBlurPreview() {
        if (homeAllAppsSection == null || homeAllAppsSection.getWidth() <= 0 || homeAllAppsSection.getHeight() <= 0) return;
        if (homeAllAppsBlur != null) { homeAllAppsSection.removeView(homeAllAppsBlur); homeAllAppsBlur = null; }
        int width = Math.max(1, homeAllAppsSection.getWidth() / 4);
        int height = Math.max(1, homeAllAppsSection.getHeight() / 4);
        Bitmap low = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(low);
        canvas.scale(width / (float) homeAllAppsSection.getWidth(), height / (float) homeAllAppsSection.getHeight());
        homeAllAppsSection.draw(canvas);
        Bitmap blurred = null;
        RenderScript rs = null;
        try {
            rs = RenderScript.create(this);
            Allocation input = Allocation.createFromBitmap(rs, low);
            Allocation output = Allocation.createTyped(rs, input.getType());
            ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(18f); blur.setInput(input); blur.forEach(output);
            output.copyTo(low);
            input.destroy(); output.destroy(); blur.destroy();
            blurred = low;
        } catch (RuntimeException | LinkageError ignored) {
            boxBlur(low, 3);
            blurred = low;
        } finally {
            if (rs != null) rs.destroy();
            if (low != blurred && !low.isRecycled()) low.recycle();
        }
        if (homeAllAppsSection == null || blurred == null) return;
        ImageView overlay = new ImageView(this); overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setImageBitmap(blurred); overlay.setFocusable(false); overlay.setClickable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        homeAllAppsSection.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        homeAllAppsBlur = overlay;
        boolean expanded = homeScroll != null && homeAllAppsTargetScroll > 0 && homeScroll.getScrollY() >= homeAllAppsTargetScroll;
        homeAllAppsBlur.setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
        homeAllAppsBlur.setAlpha(expanded ? 0f : 1f);
    }

    private void buildApps() {
        LinearLayout column = pageColumn(); column.addView(pageTitle("Apps", "Your streaming apps, together."));
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false); grid.setClipToPadding(false);
        ArrayList<GlassCard> appGridCards = new ArrayList<>();
        for (int start = 0; start < apps.size(); start += 4) {
            LinearLayout row = new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(MATCH, dp(104) + tileLabelBlock()); if (start > 0) rowLp.topMargin = dp(8);
            for (int col = 0; col < 4; col++) {
                int index = start + col;
                if (index < apps.size()) {
                    GlassCard card = appCard(apps.get(index), 0, dp(104));
                    card.setAppTile(false); // neon app outline is reserved for Home favourites and All Apps
                    card.setId(View.generateViewId()); appGridCards.add(card);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1);
                    lp.leftMargin = dp(7); lp.rightMargin = dp(7); row.addView(labeledTile(card, apps.get(index), dp(104)), lp);
                } else {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, MATCH, 1);
                    spacerLp.leftMargin = dp(7); spacerLp.rightMargin = dp(7); row.addView(spacer, spacerLp);
                }
            }
            grid.addView(row, rowLp);
        }
        for (int i = 0; i < appGridCards.size(); i++) {
            GlassCard card = appGridCards.get(i);
            int rowStart = (i / 4) * 4;
            int rowEnd = Math.min(rowStart + 3, appGridCards.size() - 1);
            card.setNextFocusLeftId(appGridCards.get(Math.max(rowStart, i - 1)).getId());
            card.setNextFocusRightId(appGridCards.get(Math.min(rowEnd, i + 1)).getId());
        }
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false); scroll.setClipToPadding(false); scroll.setPadding(0, dp(10), 0, 0);
        bindNavToScroll(scroll);
        scroll.addView(grid, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(MATCH, 0, 1); scrollLp.topMargin = dp(10);
        column.addView(scroll, scrollLp); pageHost.addView(column, new LinearLayout.LayoutParams(MATCH, MATCH));
    }

    private void buildSearch() {
        searchDetailOpen = false;
        LinearLayout column = pageColumn(); column.addView(pageTitle("Search", "Movies, TV shows and actors."));
        GlassCard panel = glass(18); panel.setFocusable(false); panel.setClickable(false); panel.setPadding(dp(18), dp(4), dp(18), dp(4));
        searchField = new EditText(this) {
            private boolean backHandledBeforeIme;
            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                        && (searchKeyboardOpenRequested || isSearchKeyboardVisible())) {
                    backHandledBeforeIme = true;
                    dismissSearchKeyboard();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && backHandledBeforeIme) {
                    backHandledBeforeIme = false;
                    return true;
                }
                return super.onKeyPreIme(keyCode, event);
            }
        }; searchField.setSingleLine(true); searchField.setTextSize(18);
        searchField.setTextColor(0xFFF3F3F5); searchField.setHintTextColor(0xAFFFFFFF); searchField.setHint("Search titles or people");
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setInputType(android.text.InputType.TYPE_CLASS_TEXT); searchField.setBackgroundColor(Color.TRANSPARENT);
        searchField.setFocusable(true); searchField.setFocusableInTouchMode(true);
        searchField.setShowSoftInputOnFocus(true);
        searchField.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus && !searchKeyboardSuppressed) showSearchKeyboard(); });
        searchField.setOnClickListener(v -> activateSearchInput());
        searchField.setOnKeyListener((v, keyCode, event) -> {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.getAction() == KeyEvent.ACTION_UP) { activateSearchInput(); return true; }
            return false;
        });
        panel.addView(searchField, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * .47f), dp(54)); p.topMargin = dp(20); column.addView(panel, p);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); searchResultsHost = results;
        LinearLayout.LayoutParams resultsLp = new LinearLayout.LayoutParams(MATCH, 0, 1); resultsLp.topMargin = dp(22);
        column.addView(results, resultsLp);
        renderSearch(results, "");
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderSearch(results, s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        addTmdbCreditsFooter(column);
        pageHost.addView(column, new LinearLayout.LayoutParams(MATCH, MATCH));
    }

    private void renderSearch(LinearLayout host, String query) {
        final int generation = ++searchGeneration;
        final String q = query.trim(); currentQuery = q; searchDetailOpen = false;
        if (searchPending != null) mainHandler.removeCallbacks(searchPending);
        host.removeAllViews();
        String token = TmdbKeyStore.load(this);
        if (q.isEmpty()) {
            addSearchPrompt(host, "Search TMDB for movies, television and people. Streaming availability is checked separately.");
            addAction(host, token == null ? "Set up TMDB" : "Change TMDB token",
                    token == null ? "Add your free non-commercial API Read Access Token" : "Token is encrypted on this device", "⌕", this::promptForTmdbToken);
            return;
        }
        if (token == null || token.trim().isEmpty()) {
            addSearchPrompt(host, "Add your free TMDB API Read Access Token to search movie, TV and actor catalogues.");
            addAction(host, "Set up TMDB", "Token is encrypted on this device", "⌕", this::promptForTmdbToken);
            return;
        }
        TextView loading = text("Searching TMDB…", 16, 0xCCFFFFFF, Typeface.create("sans-serif-light", 0)); host.addView(loading);
        searchPending = () -> SEARCH_EXECUTOR.execute(() -> TmdbClient.search(token, q, (items, error) -> mainHandler.post(() -> {
            if (generation != searchGeneration || isFinishing()) return;
            if (error != null) { host.removeAllViews(); addSearchPrompt(host, error); return; }
            currentSearchResults = items;
            renderSearchResults(items, q, host);
        })));
        mainHandler.postDelayed(searchPending, 350L);
    }

    private void addSearchPrompt(LinearLayout host, String message) {
        TextView prompt = text(message, 16, 0xCCFFFFFF, Typeface.create("sans-serif-light", 0));
        prompt.setLineSpacing(dp(3), 1f); host.addView(prompt, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void promptForTmdbToken() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextColor(0xFF18243B);
        input.setHintTextColor(0xFF667085); input.setHint("API Read Access Token"); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(dp(18), dp(8), dp(18), dp(4)); wrap.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
        new AlertDialog.Builder(this).setTitle("Connect TMDB")
                .setMessage("Create a free non-commercial developer API key at themoviedb.org/settings/api, then paste its API Read Access Token here or import a .txt file containing only that token. The token is encrypted with Android Keystore and is not included in the app source.")
                .setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                    String token = input.getText().toString().trim();
                    if (token.length() < 20) { Toast.makeText(this, "That does not look like a TMDB read token", Toast.LENGTH_LONG).show(); return; }
                    try { TmdbKeyStore.save(this, token); renderSearch(searchResultsHost, currentQuery); }
                    catch (Exception e) { Toast.makeText(this, "Could not securely save the token", Toast.LENGTH_LONG).show(); }
                }).setNeutralButton("Import .txt", (d, w) -> openTmdbTokenPicker()).show();
    }

    private void openTmdbTokenPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT); picker.addCategory(Intent.CATEGORY_OPENABLE); picker.setType("text/plain");
        try { startActivityForResult(picker, REQUEST_TMDB_TOKEN); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No file picker is available on this Fire TV", Toast.LENGTH_LONG).show(); }
    }

    private void importTmdbToken(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Cannot read selected token file");
            byte[] buffer = new byte[2048]; int count; while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            String token = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (token.length() < 20 || token.contains("\n") || token.contains("\r")) throw new java.io.IOException("The file must contain only one TMDB read token on one line");
            TmdbKeyStore.save(this, token);
            renderSearch(searchResultsHost, currentQuery);
            Toast.makeText(this, "TMDB token encrypted and saved", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Could not import token. Choose a plain text file containing only the API Read Access Token.", Toast.LENGTH_LONG).show(); }
    }

    private void renderSearchResults(List<TmdbClient.Item> items, String query, LinearLayout host) {
        if (host == null) return;
        host.removeAllViews();
        if (items == null || items.isEmpty()) { addSearchPrompt(host, query.isEmpty() ? "Search TMDB for movies, television and people." : "No matching movies, shows or people."); return; }
        TextView label = text("CATALOGUE RESULTS", 13, 0xBFFFFFFF, Typeface.create("sans-serif-medium", 0));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(MATCH, WRAP); labelLp.bottomMargin = dp(10); host.addView(label, labelLp);
        ScrollView vertical = new ScrollView(this); vertical.setVerticalScrollBarEnabled(false); vertical.setClipChildren(false); vertical.setClipToPadding(false); bindNavToScroll(vertical);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false); rows.setClipToPadding(false);
        for (TmdbClient.Item item : items) {
            String typeName = "movie".equals(item.type) ? "MOVIE" : "tv".equals(item.type) ? "TV SHOW" : "PERSON";
            GlassCard card = glass(14); card.setFocusable(true); card.setFocusableInTouchMode(true); card.setPadding(dp(9), dp(8), dp(9), dp(8));
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(dp(74), dp(102)); posterLp.rightMargin = dp(14); row.addView(poster, posterLp);
            loadTmdbImage(item.imagePath(), poster);
            LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
            String line = item.title + (item.year().isEmpty() ? "" : "  ·  " + item.year()) + "  ·  " + typeName;
            TextView title = text(line, 18, 0xFFFFFFFF, Typeface.create("sans-serif-medium", 0)); title.setMaxLines(2); info.addView(title);
            String synopsis = item.overview == null || item.overview.isEmpty() ? ("person".equals(item.type) ? "Open for profile and filmography." : "Open for details and genres.") : item.overview;
            TextView overview = text(synopsis, 13, 0xD5FFFFFF, Typeface.create("sans-serif-light", 0)); overview.setMaxLines(3); overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(MATCH, WRAP); overviewLp.topMargin = dp(5); info.addView(overview, overviewLp);
            if (item.rating > 0) { TextView rating = text("★ " + String.format(Locale.US, "%.1f", item.rating) + " / 10", 12, 0xFFFFD49A, Typeface.create("sans-serif-medium", 0)); info.addView(rating); }
            row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1)); card.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH));
            card.setTag(item.id);
            card.setOnClickListener(v -> { searchRestoreId = item.id; openTmdbDetail(item, host, () -> renderSearchResults(currentSearchResults, currentQuery, host)); });
            card.setOnFocusChangeListener((v, focus) -> card.setActive(focus));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * .74f), dp(122)); cp.bottomMargin = dp(9); rows.addView(card, cp);
        }
        vertical.addView(rows); host.addView(vertical, new LinearLayout.LayoutParams(MATCH, MATCH));
        int restoreId = searchRestoreId; searchRestoreId = -1;
        if (restoreId >= 0) host.post(() -> {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View v = rows.getChildAt(i);
                if (v instanceof GlassCard && v.getTag() instanceof Integer && ((Integer) v.getTag()) == restoreId) { v.requestFocus(); break; }
            }
        });
    }

    private void addTmdbCreditsFooter(LinearLayout column) {
        LinearLayout credits = new LinearLayout(this); credits.setGravity(Gravity.CENTER_VERTICAL); credits.setFocusable(true); credits.setFocusableInTouchMode(true);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.tmdb_logo); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(54), dp(38)); logoLp.rightMargin = dp(10); credits.addView(logo, logoLp);
        TextView notice = text("Credits · This product uses the TMDB API but is not endorsed or certified by TMDB.", 10, 0xBFFFFFFF, Typeface.create("sans-serif-light", 0));
        credits.addView(notice, new LinearLayout.LayoutParams(WRAP, WRAP));
        credits.setContentDescription("TMDB data credits"); credits.setOnClickListener(v -> showTmdbCredits());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(42)); lp.topMargin = dp(4); column.addView(credits, lp);
    }

    private void showTmdbCredits() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(10), dp(20), dp(8));
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.tmdb_logo); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(112), dp(80)); imageLp.gravity = Gravity.CENTER_HORIZONTAL; imageLp.bottomMargin = dp(10); content.addView(logo, imageLp);
        TextView notice = text("This product uses the TMDB API but is not endorsed or certified by TMDB. TMDB data and images are used under its non-commercial developer API terms.", 15, 0xFFF0F3F8, Typeface.create("sans-serif", 0));
        notice.setLineSpacing(dp(4), 1f); content.addView(notice);
        new AlertDialog.Builder(this).setTitle("Credits").setView(content).setNegativeButton("Close", null)
                .setPositiveButton("TMDB website", (dialog, which) -> openWebLink("https://www.themoviedb.org")).show();
    }

    private void loadTmdbImage(String path, ImageView target) {
        if (path == null || path.isEmpty()) return;
        final Object tag = new Object(); target.setTag(tag);
        SEARCH_EXECUTOR.execute(() -> {
            try { android.graphics.Bitmap bitmap = TmdbClient.image(path); mainHandler.post(() -> { if (target.getTag() == tag && bitmap != null) target.setImageBitmap(bitmap); }); }
            catch (Exception ignored) { }
        });
    }

    private void openTmdbDetail(TmdbClient.Item item, LinearLayout host) {
        openTmdbDetail(item, host, () -> renderSearchResults(currentSearchResults, currentQuery, host));
    }

    private void openTmdbDetail(TmdbClient.Item item, LinearLayout host, Runnable backAction) {
        String token = TmdbKeyStore.load(this); if (token == null) return;
        final int generation = ++searchDetailGeneration; searchDetailOpen = true; searchDetailBackAction = backAction;
        host.removeAllViews();
        ScrollView detailScroll = new ScrollView(this); detailScroll.setVerticalScrollBarEnabled(false); detailScroll.setClipChildren(false); detailScroll.setClipToPadding(false); bindNavToScroll(detailScroll);
        LinearLayout detailContent = new LinearLayout(this); detailContent.setOrientation(LinearLayout.VERTICAL); detailContent.setPadding(0, dp(2), dp(10), dp(12));
        detailScroll.addView(detailContent, new ScrollView.LayoutParams(MATCH, WRAP)); host.addView(detailScroll, new LinearLayout.LayoutParams(MATCH, MATCH));
        addSearchPrompt(detailContent, "Loading details…");
        SEARCH_EXECUTOR.execute(() -> {
            if ("person".equals(item.type)) {
                TmdbClient.personDetails(token, item.id, (data, error) -> mainHandler.post(() -> {
                    if (generation != searchDetailGeneration || !searchDetailOpen) return;
                    if (error != null || data == null) { detailContent.removeAllViews(); addSearchPrompt(detailContent, error == null ? "Details unavailable." : error); return; }
                    showPersonDetail(item, data, detailContent);
                }));
            } else {
                final JSONObjectHolder detail = new JSONObjectHolder();
                TmdbClient.movieDetails(token, item, (data, error) -> {
                    detail.data = data; detail.error = error;
                    TmdbClient.providers(token, item, (providerData, providerError) -> mainHandler.post(() -> {
                        if (generation != searchDetailGeneration || !searchDetailOpen) return;
                        if (detail.error != null || detail.data == null) { detailContent.removeAllViews(); addSearchPrompt(detailContent, detail.error == null ? "Details unavailable." : detail.error); return; }
                        showMediaDetail(item, detail.data, providerData, providerError, detailContent);
                    }));
                });
            }
        });
    }

    private void showMediaDetail(TmdbClient.Item item, org.json.JSONObject data, org.json.JSONObject providerData, String providerError, LinearLayout host) {
        host.removeAllViews();
        String title = data.optString("title", data.optString("name", item.title));
        String date = data.optString("release_date", data.optString("first_air_date", item.date));
        String year = date != null && date.length() >= 4 ? "  ·  " + date.substring(0, 4) : "";
        TextView heading = text(title + year, 24, 0xFFFFFFFF, Typeface.create("sans-serif-light", 0)); host.addView(heading);
        String genres = genreNames(data.optJSONArray("genres"));
        String rating = data.optDouble("vote_average", item.rating) > 0 ? "★ " + String.format(Locale.US, "%.1f", data.optDouble("vote_average", item.rating)) + " / 10" : "";
        addDetailText(host, (genres.isEmpty() ? "" : genres + "    ") + rating);
        String backdrop = data.optString("backdrop_path", item.backdrop);
        if (backdrop != null && !backdrop.isEmpty() && !"null".equals(backdrop)) { ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(520), dp(220)); ip.topMargin = dp(8); host.addView(image, ip); loadTmdbImage(backdrop, image); }
        addDetailText(host, data.optString("overview", item.overview));
        addSearchPrompt(host, "Streaming availability (India), when reported by TMDB / JustWatch:");
        JSONArrayHolder providers = providersInIndia(providerData);
        if (providers.names.isEmpty()) addDetailText(host, providerError == null ? "No India provider listing returned. This does not confirm availability." : "Availability could not be checked; metadata remains available.");
        else {
            addDetailText(host, android.text.TextUtils.join("  ·  ", providers.names));
            if (providers.link != null) addAction(host, "Open TMDB listing", "Availability source: JustWatch via TMDB", "↗", () -> openWebLink(providers.link));
        }
        addDetailText(host, "Provider listings are regional catalogue data, not a promise that an installed app can open this title. JustWatch attribution: availability data by JustWatch.");
    }

    private void showPersonDetail(TmdbClient.Item item, org.json.JSONObject data, LinearLayout host) {
        host.removeAllViews();
        String name = data.optString("name", item.title); TextView heading = text(name, 25, 0xFFFFFFFF, Typeface.create("sans-serif-light", 0)); host.addView(heading);
        String profile = data.optString("profile_path", item.profile);
        if (profile != null && !profile.isEmpty() && !"null".equals(profile)) { ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(150), dp(190)); ip.topMargin = dp(10); host.addView(image, ip); loadTmdbImage(profile, image); }
        addDetailText(host, data.optString("known_for_department", "Actor"));
        addDetailText(host, data.optString("biography", ""));
        addDetailText(host, "Filmography");
        org.json.JSONObject credits = data.optJSONObject("combined_credits");
        org.json.JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast == null || cast.length() == 0) { addDetailText(host, "No filmography items returned."); return; }
        ArrayList<org.json.JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < cast.length(); i++) { org.json.JSONObject c = cast.optJSONObject(i); if (c != null) ordered.add(c); }
        Collections.sort(ordered, (a,b) -> Double.compare(b.optDouble("popularity", 0), a.optDouble("popularity", 0)));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout strip = new LinearLayout(this); strip.setClipChildren(false); strip.setClipToPadding(false);
        int count = Math.min(16, ordered.size());
        for (int i = 0; i < count; i++) {
            org.json.JSONObject entry = ordered.get(i); TmdbClient.Item media = new TmdbClient.Item(); media.id = entry.optInt("id"); media.type = entry.optString("media_type", "movie"); media.title = entry.optString("title", entry.optString("name")); media.date = entry.optString("release_date", entry.optString("first_air_date", "")); media.poster = entry.optString("poster_path", null); media.backdrop = entry.optString("backdrop_path", null); media.overview = entry.optString("overview", ""); media.rating = entry.optDouble("vote_average", 0);
            GlassCard card = glass(12); card.setFocusable(true); card.setPadding(dp(6), dp(6), dp(6), dp(6)); LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP); box.addView(poster, new LinearLayout.LayoutParams(dp(108), dp(140))); loadTmdbImage(media.poster, poster);
            TextView label = text(media.title + (media.year().isEmpty() ? "" : "\n" + media.year()), 12, 0xFFFFFFFF, Typeface.create("sans-serif-medium", 0)); label.setMaxLines(2); box.addView(label);
            card.addView(box); card.setOnClickListener(v -> openTmdbDetail(media, host, () -> showPersonDetail(item, data, host))); card.setOnFocusChangeListener((v, f) -> card.setActive(f)); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(124), dp(190)); cp.rightMargin = dp(8); strip.addView(card, cp);
        }
        scroll.addView(strip); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(205)); lp.topMargin = dp(8); host.addView(scroll, lp);
    }

    private void addDetailText(LinearLayout host, String value) { if (value == null || value.trim().isEmpty()) return; TextView t = text(value, 14, 0xE6FFFFFF, Typeface.create("sans-serif-light", 0)); t.setLineSpacing(dp(3), 1f); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP); lp.topMargin = dp(7); host.addView(t, lp); }
    private String genreNames(org.json.JSONArray genres) { if (genres == null) return ""; ArrayList<String> names = new ArrayList<>(); for (int i=0;i<genres.length();i++) { org.json.JSONObject g=genres.optJSONObject(i); if(g!=null) names.add(g.optString("name")); } return android.text.TextUtils.join(" · ", names); }
    private JSONArrayHolder providersInIndia(org.json.JSONObject root) {
        JSONArrayHolder result = new JSONArrayHolder(); if (root == null) return result;
        org.json.JSONObject countries = root.optJSONObject("results"); org.json.JSONObject india = countries == null ? null : countries.optJSONObject("IN");
        if (india == null) return result; result.link = india.optString("link", null);
        for (String kind : new String[] {"flatrate", "free", "ads", "rent", "buy"}) { org.json.JSONArray a = india.optJSONArray(kind); if (a != null) for (int i=0;i<a.length();i++) { org.json.JSONObject p=a.optJSONObject(i); if(p!=null) { String name=p.optString("provider_name"); if(!result.names.contains(name)) result.names.add(name); } } }
        return result;
    }
    private void openWebLink(String link) { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))); } catch (Exception e) { Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show(); } }
    private static final class JSONObjectHolder { org.json.JSONObject data; String error; }
    private static final class JSONArrayHolder { final ArrayList<String> names = new ArrayList<>(); String link; }

    private String greetingText() {
        String name = prefs == null ? "" : prefs.getString(DISPLAY_NAME_KEY, "");
        name = name == null ? "" : name.trim();
        return name.isEmpty() ? "Hello" : "Hello, " + name;
    }

    private void promptForGreetingName(boolean firstRun) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Your name");
        input.setText(prefs.getString(DISPLAY_NAME_KEY, ""));
        input.setSelectAllOnFocus(true);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Personalise Liquid Home" : "Greeting name")
                .setMessage("What name would you like to see on your Fire TV home screen?")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    prefs.edit().putString(DISPLAY_NAME_KEY, name).putBoolean(NAME_SETUP_COMPLETE, true).apply();
                    if (homeGreetingView != null) homeGreetingView.setText(greetingText());
                    Toast.makeText(this, name.isEmpty() ? "Greeting cleared" : "Greeting name saved", Toast.LENGTH_SHORT).show();
                });
        if (firstRun) {
            builder.setNegativeButton("Later", (dialog, which) ->
                    prefs.edit().putBoolean(NAME_SETUP_COMPLETE, true).apply());
        } else {
            builder.setNegativeButton("Cancel", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> input.requestFocus());
        dialog.show();
    }

    private void buildSettings() {
        LinearLayout column = pageColumn(); column.addView(pageTitle("Settings", "A few thoughtful ways to make this home yours."));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(MATCH, WRAP); listLp.topMargin = dp(18); column.addView(list, listLp);
        column.post(() -> {
            ViewGroup.LayoutParams params = list.getLayoutParams();
            params.width = Math.round(column.getWidth() * .42f);
            list.setLayoutParams(params);
        });
        addSettingsAction(list, "Greeting name", "Choose the name shown on your home screen", "R", () -> promptForGreetingName(false));
        addSettingsAction(list, "Focus Effect Colour", "Current: " + FOCUS_EFFECT_COLOR_NAMES[getFocusEffectColorIndex()], "●", this::showFocusEffectColorPicker);
        addSettingsAction(list, "Background", "Choose a photo from your Fire TV folder", "◐", () -> showPage("background"));
        addSettingsAction(list, "Amazon Home", "Return to the original Fire TV home screen", "⌂", this::openAmazonHome);
        pageHost.addView(column, new LinearLayout.LayoutParams(MATCH, MATCH));
    }

    private int getFocusEffectColorIndex() {
        return Math.max(0, Math.min(FOCUS_EFFECT_COLORS.length - 1,
                prefs == null ? 0 : prefs.getInt(FOCUS_EFFECT_COLOR_KEY, 0)));
    }

    /** The colour a palette entry actually renders as on tiles (same lift/brightness as the focus stroke). */
    private static int focusDisplayColor(int c) {
        float lift = .14f, brightness = 1.30f;
        return Color.rgb(
                Math.min(255, Math.round((Color.red(c) + (255 - Color.red(c)) * lift) * brightness)),
                Math.min(255, Math.round((Color.green(c) + (255 - Color.green(c)) * lift) * brightness)),
                Math.min(255, Math.round((Color.blue(c) + (255 - Color.blue(c)) * lift) * brightness)));
    }

    private int getFocusEffectColor() { return FOCUS_EFFECT_COLORS[getFocusEffectColorIndex()]; }

    private void showFocusEffectColorPicker() {
        int selected = getFocusEffectColorIndex();
        CharSequence[] options = new CharSequence[FOCUS_EFFECT_COLOR_NAMES.length];
        for (int i = 0; i < FOCUS_EFFECT_COLOR_NAMES.length; i++) {
            SpannableString option = new SpannableString("●  " + FOCUS_EFFECT_COLOR_NAMES[i]);
            option.setSpan(new ForegroundColorSpan(focusDisplayColor(FOCUS_EFFECT_COLORS[i])), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            options[i] = option;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Focus Effect Colour")
                .setSingleChoiceItems(options, selected, (choiceDialog, index) -> {
                    prefs.edit().putInt(FOCUS_EFFECT_COLOR_KEY, index).apply();
                    for (GlassCard card : homeFavoriteCards) card.refreshFocusEffectColor();
                    for (GlassCard card : homeAllAppCards) card.refreshFocusEffectColor();
                    for (GlassCard card : navButtons) card.refreshFocusEffectColor();
                    Toast.makeText(this, FOCUS_EFFECT_COLOR_NAMES[index] + " focus colour applied", Toast.LENGTH_SHORT).show();
                    choiceDialog.dismiss();
                    mainHandler.post(() -> { if ("settings".equals(page)) showPage("settings"); });
                })
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
    }

    private void buildBackgrounds() {
        LinearLayout column = pageColumn(); column.addView(pageTitle("Background", "Choose a photo for your home screen."));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false); scroll.setClipToPadding(false); scroll.setPadding(0, dp(18), dp(24), dp(8));
        bindNavToScroll(scroll);
        LinearLayout strip = new LinearLayout(this); strip.setClipChildren(false);
        GlassCard defaultWallpaper = wallpaperCard(BUILTIN, "Default background", true);
        defaultWallpaper.setOnClickListener(v -> chooseWallpaper(BUILTIN)); strip.addView(defaultWallpaper, new LinearLayout.LayoutParams(dp(240), dp(132)));
        GlassCard transfer = glass(18); transfer.setFocusable(true); transfer.setContentDescription("Transfer from Phone/PC");
        LinearLayout transferContent = new LinearLayout(this); transferContent.setOrientation(LinearLayout.VERTICAL); transferContent.setGravity(Gravity.CENTER);
        TextView transferGlyph = text("⇧", 34, 0xFFFFD49A, Typeface.create("sans-serif-light", 0)); transferGlyph.setGravity(Gravity.CENTER);
        TextView transferLabel = text("Transfer from Phone/PC", 14, 0xFFF5F5F5, Typeface.create("sans-serif-medium", 0)); transferLabel.setGravity(Gravity.CENTER);
        transferLabel.setMaxLines(1); transferContent.addView(transferGlyph); transferContent.addView(transferLabel);
        transfer.addView(transferContent, new FrameLayout.LayoutParams(MATCH, MATCH));
        transfer.setOnClickListener(v -> startWallpaperTransfer());
        LinearLayout.LayoutParams transferLp = new LinearLayout.LayoutParams(dp(240), dp(132)); transferLp.leftMargin = dp(14); strip.addView(transfer, transferLp);
        transfer.setTag("__transfer_wallpaper__");
        File dir = wallpaperDir(); File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (isImage(f) && !BUILTIN.equalsIgnoreCase(f.getName())) {
            GlassCard card = wallpaperCard(f.getName(), "Your photo", false);
            card.setTag(f.getName());
            card.setOnClickListener(v -> chooseWallpaper(f.getName()));
            card.setOnLongClickListener(v -> { confirmDeleteWallpaper(f.getName()); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(132)); lp.leftMargin = dp(14); strip.addView(card, lp);
        }
        for (int i = 0; i < strip.getChildCount(); i++) strip.getChildAt(i).setId(View.generateViewId());
        for (int i = 0; i < strip.getChildCount(); i++) {
            View item = strip.getChildAt(i);
            int left = strip.getChildAt(Math.max(0, i - 1)).getId();
            int right = strip.getChildAt(Math.min(strip.getChildCount() - 1, i + 1)).getId();
            item.setNextFocusLeftId(left);
            item.setNextFocusRightId(right);
        }
        scroll.addView(strip); column.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        String selectedWallpaper = selectedWallpaper();
        if (!BUILTIN.equalsIgnoreCase(selectedWallpaper)
                && new File(wallpaperDir(), selectedWallpaper).isFile()) {
            addAction(column, "Delete selected wallpaper", "Remove " + selectedWallpaper + " from this Fire TV", "×",
                    () -> confirmDeleteWallpaper(selectedWallpaper));
        }
        TextView note = text("Choose a photo to apply it. Long-press a photo to delete it. Transfer images over Wi-Fi or copy them into the PC wallpapers folder and run sync-wallpapers.ps1.", 13, 0xDFFFFFFF, Typeface.create("sans-serif-light", 0));
        note.setLineSpacing(dp(4), 1); LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(dp(900), WRAP); noteLp.topMargin = dp(12); column.addView(note, noteLp);
        pageHost.addView(column, new LinearLayout.LayoutParams(MATCH, MATCH));
        defaultWallpaper.setTag(BUILTIN);
        String selected = selectedWallpaper();
        column.post(() -> {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View candidate = strip.getChildAt(i);
                if (selected.equals(candidate.getTag())) { candidate.requestFocus(); return; }
            }
            defaultWallpaper.requestFocus();
        });
    }

    private GlassCard wallpaperCard(String file, String subtitle, boolean bundled) {
        GlassCard card = glass(18); card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bundled) preview.setImageResource(R.drawable.doom);
        else preview.setImageBitmap(decodeImage(new File(wallpaperDir(), file), dp(360), dp(200)));
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(88), dp(88)); imageLp.rightMargin = dp(12); row.addView(preview, imageLp);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(bundled ? "Doom" : file, 15, 0xFFF5F5F5, Typeface.create("sans-serif-medium", 0)); name.setMaxLines(1);
        TextView detail = text(subtitle, 12, 0xCFFFFFFF, Typeface.create("sans-serif-light", 0));
        labels.addView(name); LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(WRAP, WRAP); dlp.topMargin = dp(5); labels.addView(detail, dlp);
        row.addView(labels, new LinearLayout.LayoutParams(0, WRAP, 1)); card.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH));
        card.setActive(file.equalsIgnoreCase(selectedWallpaper())); return card;
    }

    private void addAction(LinearLayout list, String title, String detail, String glyph, Runnable onClick) {
        addAction(list, title, detail, glyph, 68f, 42f, 12f, onClick);
    }

    private void addSettingsAction(LinearLayout list, String title, String detail, String glyph, Runnable onClick) {
        // Settings bars and their icon lane use 70% of their former dimensions.
        // Text sizes and the gap between rows remain unchanged for readability.
        addAction(list, title, detail, glyph, 47.6f, 29.4f, 8.4f, onClick);
    }

    private void addAction(LinearLayout list, String title, String detail, String glyph,
                          float heightDp, float iconWidthDp, float iconGapDp, Runnable onClick) {
        GlassCard card = glass(16); LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(glyph, 21, 0xFFFFD49A, Typeface.create("sans-serif-light", 0));
        icon.setGravity(Gravity.CENTER);
        if ("●".equals(glyph)) icon.setTextColor(getFocusEffectColor());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(iconWidthDp), MATCH); ilp.rightMargin = dp(iconGapDp); row.addView(icon, ilp);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, 0xFFF3F3F5, Typeface.create("sans-serif-medium", 0)));
        TextView sub = text(detail, 12, 0xCFFFFFFF, Typeface.create("sans-serif-light", 0)); LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP); slp.topMargin = dp(3); labels.addView(sub, slp);
        row.addView(labels, new LinearLayout.LayoutParams(0, WRAP, 1));
        card.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH)); card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(heightDp)); lp.bottomMargin = dp(9); list.addView(card, lp);
    }

    /** Extra height under each tile for the app name that sits outside the tile. */
    private int tileLabelBlock() { return dp(30); }
    private View labeledTile(GlassCard card, AppItem item, int tileHeight) {
        return labeledTile(card, item, tileHeight, tileHeight);
    }

    private View labeledTile(GlassCard card, AppItem item, int tileHeight, int rowTileHeight) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false); col.setClipToPadding(false);
        FrameLayout tileSlot = new FrameLayout(this); tileSlot.setClipChildren(false); tileSlot.setClipToPadding(false);
        tileSlot.addView(card, new FrameLayout.LayoutParams(MATCH, tileHeight, Gravity.CENTER));
        col.addView(tileSlot, new LinearLayout.LayoutParams(MATCH, rowTileHeight));
        TextView label = text(item.label, 13, 0xFFF4F7FF, Typeface.create("sans-serif-medium", 0));
        label.setGravity(Gravity.CENTER); label.setMaxLines(1); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH, WRAP); llp.topMargin = dp(8);
        col.addView(label, llp);
        return col;
    }

    private int averageColor(Drawable drawable) {
        if (drawable == null) return 0xFF1A2438;
        try {
            Drawable.ConstantState state = drawable.getConstantState();
            Drawable copy = state == null ? drawable : state.newDrawable().mutate();
            Bitmap sample = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 8, 8);
            copy.draw(new Canvas(sample));
            long red = 0, green = 0, blue = 0, count = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int pixel = sample.getPixel(x, y);
                if ((pixel >>> 24) < 128) continue;
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++;
            }
            sample.recycle();
            return count == 0 ? 0xFF1A2438 : Color.rgb((int)(red / count), (int)(green / count), (int)(blue / count));
        } catch (RuntimeException ignored) { return 0xFF1A2438; }
    }

    private final java.util.HashMap<String, Object[]> tileArtCache = new java.util.HashMap<>();

    /** {trimmed art, left edge strip, right edge strip, edge color, aspect} for an app's banner or icon. */
    private Object[] tileArt(AppItem item) {
        String key = item.pkg + (item.banner != null ? "#b" : "#i");
        Object[] hit = tileArtCache.get(key);
        if (hit != null) return hit;
        Drawable d = item.banner != null ? item.banner : item.icon;
        Bitmap art, left, right; int edge = 0xFF1A2438;
        try {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = 256; ih = 256; }
            float k = 256f / Math.max(iw, ih);
            int w = Math.max(8, Math.round(iw * k)), h = Math.max(8, Math.round(ih * k));
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Drawable.ConstantState cs = d.getConstantState();
            Drawable copy = cs == null ? d : cs.newDrawable().mutate();
            copy.setBounds(0, 0, w, h); copy.draw(new Canvas(full));
            int[] px = new int[w * h]; full.getPixels(px, 0, w, 0, 0, w, h);
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 24) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; }
            }
            art = full;
            if (maxX >= minX && maxY >= minY) {
                int mx = Math.round(w * 0.22f), my = Math.round(h * 0.22f); // never trim more than 22% per side
                int l = Math.min(minX, mx), t = Math.min(minY, my);
                int r = Math.max(maxX, w - 1 - mx), b = Math.max(maxY, h - 1 - my);
                if (l > 0 || t > 0 || r < w - 1 || b < h - 1) art = Bitmap.createBitmap(full, l, t, r - l + 1, b - t + 1);
            }
            int aw = art.getWidth(), ah = art.getHeight();
            int sw = Math.max(2, aw / 24);
            left = Bitmap.createBitmap(art, 0, 0, sw, ah);
            right = Bitmap.createBitmap(art, aw - sw, 0, sw, ah);
            long r0 = 0, g0 = 0, b0 = 0, n = 0;
            int[] ap = new int[aw * ah]; art.getPixels(ap, 0, aw, 0, 0, aw, ah);
            for (int y = 0; y < ah; y += 2) for (int x = 0; x < aw; x++) {
                if (x > 2 && x < aw - 3 && y > 2 && y < ah - 3) { x = aw - 4; continue; } // border ring only
                int c = ap[y * aw + x]; if ((c >>> 24) < 128) continue;
                r0 += Color.red(c); g0 += Color.green(c); b0 += Color.blue(c); n++;
            }
            if (n > 0) edge = Color.rgb((int) (r0 / n), (int) (g0 / n), (int) (b0 / n));
            if (art != full) full.recycle();
        } catch (RuntimeException | OutOfMemoryError e) {
            art = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); art.eraseColor(edge);
            left = art; right = art;
        }
        Object[] out = new Object[] { art, left, right, edge, art.getWidth() / (float) art.getHeight() };
        tileArtCache.put(key, out);
        return out;
    }

    private GlassCard appCard(AppItem item, int width, int height) {
        int cornerRadiusDp = Math.min(20, Math.max(8,
                Math.round(height / (2f * getResources().getDisplayMetrics().density))));
        GlassCard card = glass(cornerRadiusDp); card.setAppTile(true); card.setPadding(0, 0, 0, 0);
        card.setAmbientColor(0xFF198BFF);
        // Artwork keeps its own colors and logo. Transparent padding is trimmed so the icon fills the tile,
        // and the tile's left/right areas are extended from the icon's own edge pixels so it reads as one surface.
        Object[] tileArt = tileArt(item);
        Bitmap artBmp = (Bitmap) tileArt[0], leftStrip = (Bitmap) tileArt[1], rightStrip = (Bitmap) tileArt[2];
        int edgeColor = (Integer) tileArt[3]; float artAspect = (Float) tileArt[4];
        final float corner = dp(cornerRadiusDp);
        FrameLayout art = new FrameLayout(this);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        art.setClipToOutline(true);
        View backdrop = new View(this); backdrop.setBackgroundColor(edgeColor);
        art.addView(backdrop, new FrameLayout.LayoutParams(MATCH, MATCH));
        boolean fillCrop = artAspect >= 1.45f;
        if (!fillCrop) {
            LinearLayout sides = new LinearLayout(this); sides.setOrientation(LinearLayout.HORIZONTAL);
            ImageView left = new ImageView(this); left.setScaleType(ImageView.ScaleType.FIT_XY); left.setImageBitmap(leftStrip);
            ImageView right = new ImageView(this); right.setScaleType(ImageView.ScaleType.FIT_XY); right.setImageBitmap(rightStrip);
            sides.addView(left, new LinearLayout.LayoutParams(0, MATCH, 1));
            sides.addView(right, new LinearLayout.LayoutParams(0, MATCH, 1));
            art.addView(sides, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        ImageView img = new ImageView(this);
        img.setScaleType(fillCrop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(artBmp);
        art.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
        // Subtle finish: soft top highlight and gentle bottom shading for depth.
        View finish = new View(this);
        finish.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x1CFFFFFF, 0x00FFFFFF, 0x00000000, 0x2E000000 }));
        art.addView(finish, new FrameLayout.LayoutParams(MATCH, MATCH));
        card.addView(art, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView position = text("", 13, Color.WHITE, Typeface.create("sans-serif-medium", 0));
        position.setTag("main-row-position"); position.setVisibility(View.GONE);
        position.setGravity(Gravity.CENTER); position.setPadding(dp(8), dp(4), dp(8), dp(4));
        position.setBackground(round(0xE6000000, dp(10)));
        FrameLayout.LayoutParams positionLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT);
        positionLp.topMargin = dp(6); positionLp.rightMargin = dp(6); card.addView(position, positionLp);
        card.setContentDescription(item.label);
        card.setOnClickListener(v -> openApp(item));
        if (width > 0 && height > 0) card.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return card;
    }

    private void openApp(AppItem item) {
        try { startActivity(item.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); }
        catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(this, "Could not open " + item.label, Toast.LENGTH_SHORT).show(); }
    }

    /** Opens the first of the given system settings actions this device can handle, else Fire TV Settings. */
    private void openSystemSettings(String[] actions, String[] activityKeywords) {
        for (String action : actions) {
            Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) == null) continue;
            try { startActivity(i); return; } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        if (openFireTvSettingsActivity(activityKeywords)) return;
        openFireSettings();
    }

    /** Opens Fire TV's Remotes & Bluetooth Devices screen rather than Android's generic Bluetooth page. */
    private void openRemoteBluetoothSettings() {
        Intent fireTvIntent = new Intent("com.amazon.device.settings.action.HURLEY")
                .setPackage("com.amazon.tv.settings.v2")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (fireTvIntent.resolveActivity(getPackageManager()) != null) {
            try { startActivity(fireTvIntent); return; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        if (openFireTvSettingsActivity(new String[] { "controllers_bluetooth_devices", "controllersandbluetooth" })) return;
        openSystemSettings(new String[] { Settings.ACTION_BLUETOOTH_SETTINGS }, new String[] { "bluetooth", "controller" });
    }

    /** Finds a Fire TV Settings screen by name (Fire OS has no public action for these) and opens it. */
    private boolean openFireTvSettingsActivity(String[] keywords) {
        PackageManager pm = getPackageManager();
        for (String pkg : new String[] { "com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.tv.launcher" }) {
            android.content.pm.PackageInfo info;
            try { info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES); } catch (PackageManager.NameNotFoundException | RuntimeException e) { continue; }
            if (info.activities == null) continue;
            for (String key : keywords) {
                for (android.content.pm.ActivityInfo ai : info.activities) {
                    String name = ai.name.toLowerCase(java.util.Locale.US);
                    if (!name.contains(key) || name.contains("pair") || name.contains("dialog") || name.contains("receiver")) continue;
                    try {
                        startActivity(new Intent().setComponent(new ComponentName(ai.packageName, ai.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    } catch (ActivityNotFoundException | SecurityException ignored) { }
                }
            }
        }
        return false;
    }

    private void openFireSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (i.resolveActivity(getPackageManager()) == null) i = new Intent(Settings.ACTION_SETTINGS);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAmazonHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("com.amazon.tv.launcher");
        Keeper.pause();
        try { startActivity(i); } catch (ActivityNotFoundException e) { Keeper.resume(); Toast.makeText(this, "Amazon Home unavailable", Toast.LENGTH_SHORT).show(); }
    }

    private void chooseWallpaper(String file) {
        prefs.edit().putString("wallpaper", file).apply(); setWallpaper(); showPage("background");
    }

    private void openWallpaperPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        try {
            startActivityForResult(picker, REQUEST_ADD_WALLPAPER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker is available. Connect a USB drive and try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void startWallpaperTransfer() {
        if (wallpaperTransferServer != null && wallpaperTransferServer.isRunning()) {
            Toast.makeText(this, "A wallpaper transfer session is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        final WallpaperTransferServer server = new WallpaperTransferServer(wallpaperDir(), new WallpaperTransferServer.Listener() {
            @Override public void onUpload(String filename, boolean success, String message) {
                mainHandler.post(() -> {
                    if (wallpaperTransferStatus != null) {
                        wallpaperTransferStatus.setText(success
                                ? "Saved " + filename + ". Close this session to select it."
                                : "Upload failed: " + message);
                    }
                });
            }
            @Override public void onServerStopped(String message) {
                mainHandler.post(() -> {
                    if (wallpaperTransferStatus != null) wallpaperTransferStatus.setText(message);
                });
            }
        }, null, (image, filename) -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        });
        try {
            server.start();
            String address = WallpaperTransferServer.findLanAddress();
            String url = "http://" + address + ":" + server.getPort() + "/upload/" + server.getToken();
            Bitmap qr = createQrBitmap(url);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(dp(20), dp(8), dp(20), dp(8));
            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(qr);
            qrView.setContentDescription("QR code for the temporary Fire TV wallpaper upload page");
            qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(220), dp(220));
            qrParams.rightMargin = dp(24);
            content.addView(qrView, qrParams);

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView instructions = text("Scan with your phone camera, or open this address in a browser on the same Wi-Fi network. Choose a JPG, JPEG, PNG or WebP image. Uploads are saved to your wallpaper collection and are not applied automatically.", 15, 0xFFF3F5FA, Typeface.create("sans-serif", 0));
            instructions.setLineSpacing(dp(5), 1f);
            details.addView(instructions);
            TextView addressView = text(url, 13, 0xFFFFD49A, Typeface.create("sans-serif-medium", 0));
            addressView.setTextIsSelectable(true);
            LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(MATCH, WRAP); urlParams.topMargin = dp(12); details.addView(addressView, urlParams);
            wallpaperTransferStatus = text("Waiting for an image… The server stops when you close this session or after inactivity.", 13, 0xDFFFFFFF, Typeface.create("sans-serif-light", 0));
            wallpaperTransferStatus.setLineSpacing(dp(4), 1f);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(MATCH, WRAP); statusParams.topMargin = dp(12); details.addView(wallpaperTransferStatus, statusParams);
            content.addView(details, new LinearLayout.LayoutParams(0, WRAP, 1f));

            wallpaperTransferServer = server;
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Transfer from Phone/PC")
                    .setView(content)
                    .setPositiveButton("Stop session", null)
                    .create();
            wallpaperTransferDialog = dialog;
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored -> {
                stopWallpaperTransfer();
                wallpaperTransferDialog = null;
                wallpaperTransferStatus = null;
                if ("background".equals(page)) showPage("background");
            });
            dialog.show();
            dialog.getWindow().setLayout(dp(900), WRAP);
        } catch (Exception e) {
            server.close();
            wallpaperTransferServer = null;
            Toast.makeText(this, "Could not start photo transfer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap createQrBitmap(String value) {
        boolean[][] matrix = QrMatrix.encode(value);
        int quiet = 4, scale = 5;
        int side = (matrix.length + quiet * 2) * scale;
        int[] pixels = new int[side * side];
        Arrays.fill(pixels, Color.WHITE);
        for (int y = 0; y < matrix.length; y++) for (int x = 0; x < matrix.length; x++) if (matrix[y][x]) {
            int left = (x + quiet) * scale, top = (y + quiet) * scale;
            for (int py = 0; py < scale; py++) Arrays.fill(pixels, (top + py) * side + left, (top + py) * side + left + scale, Color.BLACK);
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }

    private void stopWallpaperTransfer() {
        WallpaperTransferServer server = wallpaperTransferServer;
        wallpaperTransferServer = null;
        if (server != null) server.close();
    }

    private void confirmDeleteWallpaper(String filename) {
        new AlertDialog.Builder(this)
                .setTitle("Delete wallpaper?")
                .setMessage("Remove " + filename + " from your Fire TV wallpaper collection?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    File image = new File(wallpaperDir(), filename);
                    try {
                        if (!image.getCanonicalFile().getParentFile().equals(wallpaperDir().getCanonicalFile()) || !isImage(image) || !image.delete()) {
                            Toast.makeText(this, "Could not delete that wallpaper", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (filename.equals(selectedWallpaper())) {
                            prefs.edit().putString("wallpaper", BUILTIN).apply();
                            setWallpaper();
                        }
                        showPage("background");
                        Toast.makeText(this, "Wallpaper deleted", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not delete that wallpaper", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void importWallpaper(Uri uri) {
        String name = wallpaperDisplayName(uri);
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!isImageName(safe)) safe = safe + ".jpg";
        File target = new File(wallpaperDir(), safe);
        String base = safe;
        int suffix = 2;
        while (target.exists()) {
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : ".jpg";
            target = new File(wallpaperDir(), stem + "_" + suffix++ + ext);
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new java.io.IOException("The selected image could not be opened.");
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            prefs.edit().putString("wallpaper", target.getName()).apply();
            setWallpaper();
            showPage("background");
            Toast.makeText(this, "Wallpaper added", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (target.exists()) target.delete();
            Toast.makeText(this, "Could not add that image", Toast.LENGTH_LONG).show();
        }
    }

    private String wallpaperDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "wallpaper.jpg";
    }

    private boolean isImageName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private void setWallpaper() {
        if (wallpaperView == null) return;
        String file = selectedWallpaper();
        File chosen = new File(wallpaperDir(), file);
        boolean custom = !BUILTIN.equalsIgnoreCase(file) && chosen.isFile();
        String renderKey = BUILTIN;
        if (custom) {
            try { renderKey = chosen.getCanonicalPath() + ":" + chosen.lastModified() + ":" + chosen.length(); }
            catch (java.io.IOException ignored) { renderKey = chosen.getAbsolutePath(); }
        }
        if (renderKey.equals(wallpaperRenderKey) && wallpaperView.getDrawable() != null) return;
        Bitmap selected = custom ? decodeImage(chosen, 1920, 1080) : null;
        if (custom && selected == null) renderKey = BUILTIN;
        if (renderKey.equals(wallpaperRenderKey) && wallpaperView.getDrawable() != null) return;
        wallpaperRenderKey = renderKey;
        if (selected != null) wallpaperView.setImageBitmap(selected);
        else wallpaperView.setImageResource(R.drawable.doom);
        Drawable drawable = wallpaperView.getDrawable();
        Bitmap source = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap() : null;
        prepareWallpaperBlur(source);
    }

    private void prepareWallpaperBlur(Bitmap source) {
        final int generation = ++wallpaperBlurGeneration;
        wallpaperBlurAmount = 0f;
        if (wallpaperBlurView == null) return;
        wallpaperBlurView.setImageDrawable(null);
        wallpaperBlurView.setAlpha(0f);
        if (source == null) return;
        WALLPAPER_BLUR_EXECUTOR.execute(() -> {
            Bitmap soft = null;
            try {
                int width = Math.min(360, source.getWidth());
                int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
                soft = Bitmap.createScaledBitmap(source, width, height, true);
                if (soft == source) soft = source.copy(Bitmap.Config.ARGB_8888, true);
                boxBlur(soft, 2);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                if (soft != null && soft != source && !soft.isRecycled()) soft.recycle();
                soft = null;
            }
            final Bitmap result = soft;
            if (result == null) return;
            mainHandler.post(() -> {
                if (generation != wallpaperBlurGeneration || wallpaperBlurView == null) {
                    if (!result.isRecycled()) result.recycle();
                    return;
                }
                wallpaperBlurView.setImageBitmap(result);
                wallpaperBlurView.setAlpha(wallpaperBlurAmount);
            });
        });
    }

    private void applyWallpaperMotion(float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        wallpaperBlurAmount = .18f * f;
        float scale = 1f + .13f * f;
        float parallaxY = -dp(4f) * f;
        if (wallpaperView != null) {
            wallpaperView.setScaleX(scale); wallpaperView.setScaleY(scale);
            wallpaperView.setTranslationY(parallaxY);
        }
        if (wallpaperBlurView != null) {
            wallpaperBlurView.setScaleX(scale); wallpaperBlurView.setScaleY(scale);
            wallpaperBlurView.setTranslationY(parallaxY);
            wallpaperBlurView.setAlpha(wallpaperBlurAmount);
        }
        if (wallpaperTransitionShade != null) wallpaperTransitionShade.setAlpha(.065f * f);
    }

    private Bitmap decodeImage(File file, int targetWidth, int targetHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / (options.inSampleSize * 2) >= targetWidth
                && options.outHeight / (options.inSampleSize * 2) >= targetHeight) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private File wallpaperDir() {
        File dir = getExternalFilesDir("wallpapers");
        if (dir == null) dir = new File(getFilesDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String selectedWallpaper() {
        if (prefs == null) return BUILTIN;
        String selected = prefs.getString("wallpaper", BUILTIN);
        if (LEGACY_BUILTIN_WALLPAPER.equalsIgnoreCase(selected)) {
            prefs.edit().putString("wallpaper", BUILTIN).apply();
            return BUILTIN;
        }
        return selected;
    }

    private boolean isImage(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"));
    }

    private void bindNavToScroll(View scrollable) {
        scrollable.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (scrollable == homeScroll && homeViewportAnimator == null) updateHomeHeroForScroll(y);
            if (scrollable != homeScroll) setNavCompact(x > dp(20) || y > dp(20));
        });
    }

    private void updateHomeHeroForScroll(int scrollY) {
        if (homeScroll == null || homeScroll.getChildCount() == 0) return;
        View content = homeScroll.getChildAt(0);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        View hero = ((ViewGroup) content).getChildAt(0);
        if (!(hero instanceof LinearLayout)) return;
        float scale = 1f - (1f - HOME_HERO_COMPACT_SCALE) * homeHeroCollapseFraction;
        hero.setTranslationY(scrollY);
        if (homeViewportAnimator == null) {
            hero.setScaleX(scale); hero.setScaleY(scale);
            hero.setAlpha(homeChromeAutoHide ? 1f - homeHeroCollapseFraction : 1f);
            applyNavScale(homeHeroCollapseFraction);
        }
    }

    /** Top icons shrink and grow with the same value as the clock and greeting (no separate timer). */
    private void applyNavScale(float fraction) {
        if (floatingNav == null) return;
        float s = 1f - (1f - navCompactSize / (float) navExpandedSize) * fraction;
        setFloatingNavPivot();
        floatingNav.setScaleX(s); floatingNav.setScaleY(s);
        navCompact = fraction > .5f;
    }

    private void setFloatingNavPivot() {
        if (floatingNav == null) return;
        float x = floatingNav.getWidth(), y = floatingNav.getHeight() / 2f;
        if (floatingNav.getPivotX() != x) floatingNav.setPivotX(x);
        if (floatingNav.getPivotY() != y) floatingNav.setPivotY(y);
    }

    private void setNavCompact(boolean compact) {
        if (floatingNav == null || navCompact == compact) return;
        navCompact = compact;
        float target = compact ? navCompactSize / (float) navExpandedSize : 1f;
        setFloatingNavPivot();
        floatingNav.animate().cancel();
        floatingNav.animate().scaleX(target).scaleY(target).setDuration(MOTION_NAV_MS).setInterpolator(EASE_MOVE).start();
    }

    private void hideSearchKeyboard() {
        searchKeyboardSuppressed = true;
        searchKeyboardOpenRequested = false;
        EditText target = searchField;
        if (target == null || target.getWindowToken() == null) return;
        target.setShowSoftInputOnFocus(false);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
    }

    private boolean isSearchKeyboardVisible() {
        if (searchImeVisible) return true;
        Rect visibleFrame = new Rect(); getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        return getResources().getDisplayMetrics().heightPixels - visibleFrame.bottom > dp(150);
    }

    private void dismissSearchKeyboard() {
        EditText target = searchField;
        hideSearchKeyboard();
        searchKeyboardOpenRequested = false;
        searchImeVisible = false;
        if (target != null) target.requestFocus();
    }

    private void activateSearchInput() {
        if (searchField == null) return;
        searchKeyboardSuppressed = false;
        searchField.setShowSoftInputOnFocus(true);
        searchField.requestFocus();
        showSearchKeyboard();
    }

    private void showSearchKeyboard() {
        final EditText target = searchField;
        if (target == null || searchKeyboardSuppressed) return;
        target.setShowSoftInputOnFocus(true);
        target.postDelayed(() -> {
            if (searchField != target || searchKeyboardSuppressed || !target.hasFocus() || !target.hasWindowFocus()) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                boolean shown = imm.showSoftInput(target, InputMethodManager.SHOW_FORCED);
                if (shown) searchKeyboardOpenRequested = true;
            }
        }, 220);
    }

    private LinearLayout pageColumn() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false); col.setClipToPadding(false); col.setPadding(dp(42), dp(23), dp(42), dp(16)); return col;
    }

    private View pageTitle(String title, String subtitle) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        b.addView(text(title, 34, 0xFFF5F5F5, Typeface.create("sans-serif-light", 0)));
        TextView sub = text(subtitle, 15, 0xD9FFFFFF, Typeface.create("sans-serif-light", 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP); lp.topMargin = dp(7); b.addView(sub, lp); return b;
    }

    private void setWallpaperWithPreview() { setWallpaper(); root.invalidate(); }
    private int dp(float x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private float dpF(float x) { return x * getResources().getDisplayMetrics().density; }
    private TextView text(String s, float size, int color, Typeface typeface) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(typeface); t.setIncludeFontPadding(false); return t;
    }
    private GradientDrawable round(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private GlassCard glass(int radius) { return new GlassCard(this, dp(radius)); }

    private static final class AppItem {
        final String pkg, label; final Intent intent; final Drawable icon, banner;
        AppItem(String pkg, String label, Intent intent, Drawable icon, Drawable banner) {
            this.pkg=pkg; this.label=label; this.intent=intent; this.icon=icon; this.banner=banner;
        }
    }

    private static final class NavGlyph {
        static final int SEARCH = 0, APPS = 1, SETTINGS = 2, FIRE_SETTINGS = 3, WIFI = 4, BLUETOOTH = 5;
    }

    /** Crisp vector icons stay lightweight on Fire OS 6. */
    private final class NavGlyphView extends View {
        private final int kind;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        NavGlyphView(Context context, int kind) { super(context); this.kind = kind; setFocusable(false); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, u = Math.min(getWidth(), getHeight()) / 24f;
            paint.setColor(0xFFEAF4FF); paint.setStrokeWidth(2.0f * u); paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND); paint.setStyle(android.graphics.Paint.Style.STROKE);
            if (kind == NavGlyph.SEARCH) {
                canvas.drawCircle(cx - 2*u, cy - 2*u, 6.5f*u, paint);
                canvas.drawLine(cx + 3*u, cy + 3*u, cx + 9*u, cy + 9*u, paint);
            } else if (kind == NavGlyph.APPS) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    float r = 2.1f*u; canvas.drawRoundRect(cx + x*6.2f*u-r, cy + y*6.2f*u-r,
                            cx + x*6.2f*u+r, cy + y*6.2f*u+r, 1.2f*u, 1.2f*u, paint);
                }
            } else if (kind == NavGlyph.SETTINGS) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i*45, cx, cy);
                    canvas.drawRoundRect(cx-1.8f*u, cy-10.5f*u, cx+1.8f*u, cy-6.7f*u, 1*u, 1*u, paint);
                    canvas.restore();
                }
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, 7.2f*u, paint); canvas.drawCircle(cx, cy, 2.6f*u, paint);
            } else if (kind == NavGlyph.WIFI) {
                float wy = cy + 6.5f * u;
                for (float r : new float[] { 4.8f, 9.2f, 13.6f }) {
                    canvas.drawArc(new android.graphics.RectF(cx - r * u, wy - r * u, cx + r * u, wy + r * u), 225, 90, false, paint);
                }
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx, wy, 1.6f * u, paint);
            } else if (kind == NavGlyph.BLUETOOTH) {
                android.graphics.Path bt = new android.graphics.Path();
                bt.moveTo(cx - 5.5f * u, cy - 4.5f * u); bt.lineTo(cx + 5f * u, cy + 4.5f * u); bt.lineTo(cx, cy + 9f * u);
                bt.lineTo(cx, cy - 9f * u); bt.lineTo(cx + 5f * u, cy - 4.5f * u); bt.lineTo(cx - 5.5f * u, cy + 4.5f * u);
                canvas.drawPath(bt, paint);
            } else if (kind == NavGlyph.FIRE_SETTINGS) {
                canvas.drawRoundRect(cx-10*u, cy-7*u, cx+10*u, cy+7*u, 1.6f*u, 1.6f*u, paint);
                canvas.drawLine(cx-3*u, cy+10*u, cx+3*u, cy+10*u, paint);
                canvas.drawLine(cx, cy+7*u, cx, cy+10*u, paint);
                canvas.save(); canvas.scale(.62f, .62f, cx+6*u, cy+5*u);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i*45, cx+6*u, cy+5*u);
                    canvas.drawRoundRect(cx+4.4f*u, cy-5.5f*u, cx+7.6f*u, cy-2.4f*u, .8f*u, .8f*u, paint);
                    canvas.restore();
                }
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawCircle(cx+6*u, cy+5*u, 4.5f*u, paint); canvas.drawCircle(cx+6*u, cy+5*u, 1.6f*u, paint);
                canvas.restore();
            }
        }
    }

    private final class HomeScrollView extends ScrollView {
        private boolean suppressFocusAutoScroll;
        private View deferredChild;
        private View deferredFocus;
        HomeScrollView(Context context) { super(context); }
        private ValueAnimator focusGlide;
        private boolean inFocusScroll;
        boolean isGliding() { return focusGlide != null; }
        void cancelGlide() { if (focusGlide != null) { ValueAnimator g = focusGlide; focusGlide = null; g.cancel(); } }
        /** Eased scroll that re-targets cleanly when the remote is pressed repeatedly. */
        void glideBy(int dy) {
            int from = getScrollY();
            int max = Math.max(0, getChildAt(0).getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
            int target = Math.max(0, Math.min(max, from + dy));
            if (target == from) return;
            cancelGlide();
            final ValueAnimator a = ValueAnimator.ofInt(from, target);
            focusGlide = a;
            a.setDuration(Math.min(240L, 130L + Math.round(Math.abs(target - from) * .12f)));
            a.setInterpolator(EASE_MOVE);
            a.addUpdateListener(v -> scrollTo(0, (Integer) v.getAnimatedValue()));
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { if (focusGlide == a) focusGlide = null; }
            });
            a.start();
        }
        @Override public void scrollBy(int x, int y) {
            if (inFocusScroll) glideBy(y); else super.scrollBy(x, y);
        }
        void setSuppressFocusAutoScroll(boolean suppress) {
            if (suppress) cancelGlide();
            suppressFocusAutoScroll = suppress;
            if (!suppress) { deferredChild = null; deferredFocus = null; }
        }
        void finishFocusTransition() {
            suppressFocusAutoScroll = false;
            deferredChild = null; deferredFocus = null;
        }
        @Override public void requestChildFocus(View child, View focused) {
            if (suppressFocusAutoScroll) {
                deferredChild = child; deferredFocus = focused;
                if (getParent() != null) getParent().requestChildFocus(this, focused);
                return;
            }
            inFocusScroll = true; // ScrollView jumps with scrollBy() here; route it through the eased glide
            try { super.requestChildFocus(child, focused); } finally { inFocusScroll = false; }
        }
        @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            if (suppressFocusAutoScroll) return false;
            rectangle.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
            int delta = computeScrollDeltaToGetChildRectOnScreen(rectangle);
            if (delta == 0) return false;
            glideBy(delta);
            return true;
        }
    }

    /** Small GPU shader glow; it redraws only when D-pad focus changes. */
    private final class AmbientGlowView extends View {
        private final android.graphics.Paint glowPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int glowColor;
        private float glowX, glowY;
        private boolean hasGlowPosition;
        private ValueAnimator glowAnimator;
        private android.graphics.RadialGradient glowShader; private int shaderColor; private float shaderRadius;
        AmbientGlowView(Context context) { super(context); }
        /** Trails the tile smoothly while it moves with the scroll (the focus glide is cancelled so only one writer moves the glow). */
        void follow(View anchor) {
            if (glowColor == 0 || anchor == null) return;
            if (glowAnimator != null) { glowAnimator.cancel(); glowAnimator = null; }
            anchor.getLocationOnScreen(screenLocationScratch);
            float tx = screenLocationScratch[0] + anchor.getWidth() * .5f;
            float ty = screenLocationScratch[1] + anchor.getHeight() * .5f;
            if (!hasGlowPosition) { glowX = tx; glowY = ty; }
            else { glowX = tx; glowY = ty; }
            hasGlowPosition = true;
            postInvalidateOnAnimation();
        }
        void settle(View anchor) {
            if (glowColor == 0 || anchor == null) return;
            anchor.getLocationOnScreen(screenLocationScratch);
            glowX = screenLocationScratch[0] + anchor.getWidth() * .5f;
            glowY = screenLocationScratch[1] + anchor.getHeight() * .5f;
            hasGlowPosition = true;
            invalidate();
        }
        void setFocus(int color, View anchor) {
            if (glowAnimator != null) { glowAnimator.cancel(); glowAnimator = null; }
            if (color == 0 || anchor == null) {
                glowColor = 0;
                invalidate();
                return;
            }
            int[] point = new int[2]; anchor.getLocationOnScreen(point);
            float targetX = point[0] + anchor.getWidth() * .5f;
            float targetY = point[1] + anchor.getHeight() * .5f;
            glowColor = color;
            if (!hasGlowPosition || Math.abs(targetX - glowX) + Math.abs(targetY - glowY) < dp(2)) {
                glowX = targetX; glowY = targetY; hasGlowPosition = true; invalidate(); return;
            }
            final float fromX = glowX, fromY = glowY;
            ValueAnimator glide = ValueAnimator.ofFloat(0f, 1f);
            glowAnimator = glide;
            glide.setDuration(MOTION_GLOW_MS);
            glide.setInterpolator(EASE_MOVE);
            glide.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                glowX = fromX + (targetX - fromX) * t;
                glowY = fromY + (targetY - fromY) * t;
                postInvalidateOnAnimation();
            });
            glide.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (glowAnimator == glide) glowAnimator = null;
                }
            });
            glide.start();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (glowColor == 0) return;
            float radius = Math.max(dp(220), getWidth() * .28f);
            if (glowShader == null || shaderColor != glowColor || shaderRadius != radius) {
                int soft = Color.argb(28, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor));
                glowShader = new android.graphics.RadialGradient(0f, 0f, radius, soft, 0x00000000, android.graphics.Shader.TileMode.CLAMP);
                shaderColor = glowColor; shaderRadius = radius;
                glowPaint.setShader(glowShader);
            }
            canvas.save(); canvas.translate(glowX, glowY);
            canvas.drawCircle(0f, 0f, radius, glowPaint);
            canvas.restore();
        }
    }

    private final class GlassCard extends FrameLayout {
        private final GradientDrawable idle = round(0x50101F3A, dp(18));
        private final GradientDrawable activeBg = round(0x74335D97, dp(18));
        private final GradientDrawable focusedBg = round(0x403F6FB0, dp(18));
        private final GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x32FFFFFF, 0x00FFFFFF});
        private final GradientDrawable blueIdle = round(0xB5182B46, dp(25));
        private final GradientDrawable blueActive = round(0xD3224568, dp(25));
        private final GradientDrawable blueFocus = round(0x553F6FA8, dp(25));
        private boolean floatingStyle;
        private boolean appTile;
        private final int radius;
        private boolean focused, active;
        private int ambientColor = 0xFF258BFF;
        private int focusEffectColor = 0xFF00651E;
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgeHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path edgePath;
        private final Path edgeCoreSweepPath = new Path();
        private final Path edgeOutlineSweepPath = new Path();
        private final Path edgeHaloSweepPath = new Path();
        private final android.graphics.PathMeasure edgeMeasure = new android.graphics.PathMeasure();
        private final float[] edgeLeftContour = new float[130];
        private final float[] edgeRightContour = new float[130];
        private final float[] edgeMeasurePosition = new float[2];
        private final float[] edgeMeasureTangent = new float[2];
        private float edgePathLength;
        private float edgePhase;
        private float edgePhaseShift, edgeSkipFrom;
        private boolean edgeSkipping;
        private float edgeAlpha;
        private ValueAnimator edgeAnimator;
        void setAppTile(boolean value) {
            appTile = value;
            if (value) {
                setBackground(null); setForeground(null); setElevation(0f); setStateListAnimator(null);
                refreshFocusEffectColor();
            }
        }
        void refreshFocusEffectColor() { focusEffectColor = getFocusEffectColor(); invalidate(); }
        private int getBrighterFocusEffectColor() {
            final float lift = .14f;
            final float brightness = 1.30f;
            return Color.rgb(
                    Math.min(255, Math.round((Color.red(focusEffectColor) + (255 - Color.red(focusEffectColor)) * lift) * brightness)),
                    Math.min(255, Math.round((Color.green(focusEffectColor) + (255 - Color.green(focusEffectColor)) * lift) * brightness)),
                    Math.min(255, Math.round((Color.blue(focusEffectColor) + (255 - Color.blue(focusEffectColor)) * lift) * brightness)));
        }
        private int getContrastShadowColor() {
            float luminance = .2126f * Color.red(focusEffectColor)
                    + .7152f * Color.green(focusEffectColor) + .0722f * Color.blue(focusEffectColor);
            int alpha = Math.round((180f - Math.min(255f, luminance) * .18f) * .70f);
            return Color.argb(alpha, 0, 0, 0);
        }
        void setAmbientColor(int color) { ambientColor = color; if (focused && ambientGlow != null) ambientGlow.setFocus(color, this); }
        void setFloatingStyle(boolean enabled) { floatingStyle = enabled; if (enabled) { blueIdle.setCornerRadius(radius); blueActive.setCornerRadius(radius); blueFocus.setCornerRadius(radius); blueIdle.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); blueIdle.setColors(new int[] { 0x40F2F8FF, 0x1CD6E6FF });
            blueActive.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); blueActive.setColors(new int[] { 0x5CF2F8FF, 0x30D6E6FF });
            blueFocus.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); blueFocus.setColors(new int[] { 0x6AFFFFFF, 0x38E0EDFF });
            setBackground(blueIdle); setElevation(0);
            setForeground(null); setStateListAnimator(null); setClipToOutline(false); setWillNotDraw(false);
            refreshFocusEffectColor();
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try { View.class.getMethod("setDefaultFocusHighlightEnabled", boolean.class).invoke(this, false); }
                catch (Exception ignored) { }
            }
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline outline) { outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(v.getWidth(), v.getHeight()) / 2f); }
            });
        } }
        GlassCard(Context context, int radius) {
            super(context); this.radius = radius; setFocusable(true); setClickable(true); setClipChildren(false);
            idle.setCornerRadius(radius); activeBg.setCornerRadius(radius); focusedBg.setCornerRadius(radius); sheen.setCornerRadius(radius);
            setBackground(idle); setElevation(dp(4));
            setOnFocusChangeListener((v, hasFocus) -> {
                focused = hasFocus; setBackground(appTile ? null : floatingStyle
                        ? (active ? blueActive : blueIdle)
                        : (focused ? focusedBg : active ? activeBg : idle));
                animate().cancel();
                float z = appTile ? (hasFocus ? dp(5) : 0f) : floatingStyle ? 0f : dp(hasFocus ? 14 : 3);
                long focusDuration = appTile
                        ? (hasFocus ? MOTION_APP_TILE_FOCUS_IN_MS : MOTION_APP_TILE_FOCUS_OUT_MS)
                        : (hasFocus ? MOTION_FOCUS_IN_MS : MOTION_FOCUS_OUT_MS);
                android.view.animation.Interpolator focusCurve = appTile
                        ? EASE_MOVE : (hasFocus ? EASE_OUT : EASE_MOVE);
                animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f).translationZ(z)
                        .setInterpolator(focusCurve).setDuration(focusDuration).start();
                if (!appTile && ambientGlow != null) ambientGlow.setFocus(hasFocus ? ambientColor : 0, hasFocus ? this : null);
                if (floatingStyle) animateFocusMix(hasFocus);
                if (appTile || floatingStyle) setEdgeFocused(hasFocus);
                invalidate();
            });
        }
        void setActive(boolean value) { active=value; if (!focused || floatingStyle) setBackground(appTile ? null : floatingStyle ? (active ? blueActive : blueIdle) : (active ? activeBg : idle)); }
        private float focusMix;
        private ValueAnimator mixAnimator;
        private final Paint focusFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private void animateFocusMix(boolean on) {
            if (mixAnimator != null) mixAnimator.cancel();
            final ValueAnimator a = ValueAnimator.ofFloat(focusMix, on ? 1f : 0f);
            mixAnimator = a;
            a.setDuration(on ? MOTION_FOCUS_IN_MS : MOTION_FOCUS_OUT_MS);
            a.setInterpolator(on ? EASE_OUT : EASE_MOVE);
            a.addUpdateListener(v -> { focusMix = (Float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!floatingStyle || focusMix <= 0f) return;
            if (focusFillPaint.getShader() == null) {
                focusFillPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(), 0x38FFFFFF, 0x1EE0EDFF, android.graphics.Shader.TileMode.CLAMP));
            }
            focusFillPaint.setAlpha(Math.round(255 * focusMix));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, focusFillPaint);
        }
        private final Paint drawPaint = new Paint(3);
        private final RectF drawRect = new RectF();
        private final RectF ringRect = new RectF();
        private final Paint lowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rimRect = new RectF();
        private android.graphics.LinearGradient sheenShader;
        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            android.graphics.Paint p = drawPaint;
            super.dispatchDraw(canvas);
            if (sheenShader == null) sheenShader = new android.graphics.LinearGradient(0, 0, 0, getHeight() * .62f, 0x36FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
            p.setStyle(Paint.Style.FILL); p.setShader(sheenShader);
            android.graphics.RectF r = drawRect; r.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(r, radius, radius, p); p.setShader(null);
            if (floatingStyle) drawGlassRim(canvas, r);
            if (!appTile && !floatingStyle) {
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(dp(floatingStyle ? (focused ? 2.2f : active ? 1.5f : .9f) : (focused ? 2.4f : active ? 1.2f : .8f)));
                p.setColor(floatingStyle ? (focused ? 0x81C9E7FF : active ? 0xB477C5FF : 0x664E83B0)
                        : focused ? 0x76C8CFFF : active ? 0x9A73B5EF : 0x626A8FB8);
                canvas.drawRoundRect(r, radius, radius, p);
            }
            if (focused && !appTile && !floatingStyle) { // preserve non-app tile focus styling
                p.setStrokeWidth(dp(3));
                int base = floatingStyle ? 53 : 38;
                for (int i = 1; i <= 5; i++) {
                    p.setColor(Color.argb(base - i * (base / 6), 120, 185, 255));
                    float g = dp(3) * i;
                    ringRect.set(-g, -g, getWidth() + g, getHeight() + g); canvas.drawRoundRect(ringRect, radius + g, radius + g, p);
                }
            }
            drawEdgeSweep(canvas);
        }

        /** Liquid-glass finish: a specular rim that is bright top-left and bottom-right, plus soft light along the lower edge. */
        private void drawGlassRim(Canvas canvas, RectF r) {
            float w = getWidth(), h = getHeight();
            if (lowerPaint.getShader() == null) {
                lowerPaint.setShader(new android.graphics.LinearGradient(0, h * .5f, 0, h, 0x00FFFFFF, 0x2CFFFFFF, android.graphics.Shader.TileMode.CLAMP));
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setShader(new android.graphics.LinearGradient(0, 0, w, h, new int[] { 0xE6FFFFFF, 0x30FFFFFF, 0x16FFFFFF, 0x80FFFFFF },
                        new float[] { 0f, .38f, .62f, 1f }, android.graphics.Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(r, radius, radius, lowerPaint);
            Paint rim = rimPaint; rim.setStrokeWidth(dpF(1.1f + .4f * focusMix));
            RectF inner = rimRect; inner.set(r); inner.inset(dpF(.55f), dpF(.55f));
            float cr = Math.max(0f, radius - dpF(.55f));
            canvas.drawRoundRect(inner, cr, cr, rim);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            sheenShader = null; lowerPaint.setShader(null); focusFillPaint.setShader(null);
            if (w <= 0 || h <= 0) { edgePath = null; edgePathLength = 0f; return; }
            float inset = dpF(.5f);
            RectF bounds = new RectF(inset, inset, w - inset, h - inset);
            float corner = Math.max(0, radius - inset);
            edgePath = new Path();
            edgePath.addRoundRect(bounds, corner, corner, Path.Direction.CW);
            edgeMeasure.setPath(edgePath, false);
            edgePathLength = edgeMeasure.getLength();
            edgePaint.setStyle(Paint.Style.STROKE); edgePaint.setStrokeCap(Paint.Cap.ROUND); edgePaint.setStrokeJoin(Paint.Join.ROUND);
            edgeHaloPaint.setStyle(Paint.Style.STROKE); edgeHaloPaint.setStrokeCap(Paint.Cap.ROUND); edgeHaloPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        private boolean reduceMotionEnabled() {
            try {
                return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
                        || Settings.Global.getFloat(getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) <= 0f;
            } catch (RuntimeException ignored) { return false; }
        }

        private void setEdgeFocused(boolean hasFocus) {
            if (edgeAnimator != null) { edgeAnimator.cancel(); edgeAnimator = null; }
            if (hasFocus) {
                edgePhase = 0f; edgePhaseShift = 0f; edgeSkipping = false;
                edgeAlpha = 1f;
                if (!reduceMotionEnabled()) {
                    ValueAnimator travel = ValueAnimator.ofFloat(0f, 1f);
                    edgeAnimator = travel;
                    travel.setDuration(6000L); travel.setRepeatCount(ValueAnimator.INFINITE);
                    travel.setInterpolator(new LinearInterpolator());
                    travel.addUpdateListener(a -> {
                        float f = (Float) a.getAnimatedValue();
                        if (viewportMotionBusy()) { // path tessellation each frame is the costliest thing on Fire TV: hold the dashes still while scrolling
                            if (!edgeSkipping) { edgeSkipping = true; edgeSkipFrom = f; }
                            return;
                        }
                        if (edgeSkipping) { edgePhaseShift -= f - edgeSkipFrom; edgeSkipping = false; }
                        float phase = f + edgePhaseShift;
                        edgePhase = phase - (float) Math.floor(phase);
                        postInvalidateOnAnimation();
                    });
                    travel.start();
                }
                invalidate();
                return;
            }
            // Clear synchronously on focus loss. No previous-tile fade or trailing glow.
            edgeAlpha = 0f;
            invalidate();
        }

        private void drawEdgeSweep(Canvas canvas) {
            if (!(appTile || floatingStyle) || edgePath == null || edgePathLength <= 0f || edgeAlpha <= 0f) return;
            int glowColor = getBrighterFocusEffectColor();
            // Keep the full rounded rectangle connected with a thin, faded blue stroke.
            edgePaint.setShader(null); edgePaint.setPathEffect(null); edgePaint.setAlpha(255);
            edgePaint.clearShadowLayer();
            // Faint, thin connecting line so the two travelling dashes stay distinct with faded ends.
            edgePaint.setStrokeWidth(dpF(1.0f));
            edgePaint.setColor(Color.argb(Math.round(120 * edgeAlpha), Color.red(glowColor),
                    Color.green(glowColor), Color.blue(glowColor)));
            canvas.drawPath(edgePath, edgePaint);

            // Two bright dashes travel counter-clockwise around the rounded path.
            // Layered strokes create a soft neon halo without covering app artwork.
            float dashLength = edgePathLength * .308f; // 40% longer than the previous 22% segment
            float travel = (1f - edgePhase) * edgePathLength;
            for (int segment = 0; segment < 2; segment++) {
                float start = (travel + segment * edgePathLength * .5f) % edgePathLength;
                drawNeonSegment(canvas, start, dashLength, glowColor);
            }
            edgePaint.setAlpha(255); edgeHaloPaint.setAlpha(255);
        }

        private int deepOutlineColor(int color) {
            return Color.rgb(Math.round(Color.red(color) * .36f), Math.round(Color.green(color) * .36f), Math.round(Color.blue(color) * .36f));
        }

        private void drawNeonSegment(Canvas canvas, float start, float length, int glowColor) {
            // Build a single filled contour with smoothly narrowing ends. This
            // keeps the fade continuous without the dotted appearance of many strokes.
            buildTaperedSweep(edgeHaloSweepPath, start, length, dpF(4.2000f));
            buildTaperedSweep(edgeCoreSweepPath, start, length, dpF(2.2f));
            edgeHaloPaint.setStyle(Paint.Style.FILL);
            edgeHaloPaint.clearShadowLayer();
            edgeHaloPaint.setColor(glowColor);
            edgeHaloPaint.setAlpha(Math.round(84 * edgeAlpha));
            canvas.drawPath(edgeHaloSweepPath, edgeHaloPaint);
            buildTaperedSweep(edgeOutlineSweepPath, start, length, dpF(3.4f));
            edgePaint.setStyle(Paint.Style.FILL);
            edgePaint.setColor(deepOutlineColor(glowColor));
            edgePaint.setAlpha(Math.round(230 * edgeAlpha));
            canvas.drawPath(edgeOutlineSweepPath, edgePaint);
            edgePaint.setColor(glowColor);
            edgePaint.setAlpha(Math.round(255 * edgeAlpha));
            canvas.drawPath(edgeCoreSweepPath, edgePaint);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgeHaloPaint.setStyle(Paint.Style.STROKE);
        }

        private void buildTaperedSweep(Path out, float start, float length, float fullWidth) {
            final int samples = edgeLeftContour.length / 2 - 1;
            out.reset();
            for (int i = 0; i <= samples; i++) {
                float progress = i / (float) samples;
                float distance = (start + length * progress) % edgePathLength;
                if (!edgeMeasure.getPosTan(distance, edgeMeasurePosition, edgeMeasureTangent)) continue;
                float fade = Math.min(1f, Math.min(progress / .16f, (1f - progress) / .16f));
                fade = fade * fade * (3f - 2f * fade);
                float halfWidth = fullWidth * .5f * fade;
                float offsetX = -edgeMeasureTangent[1] * halfWidth;
                float offsetY = edgeMeasureTangent[0] * halfWidth;
                int index = i * 2;
                edgeLeftContour[index] = edgeMeasurePosition[0] + offsetX;
                edgeLeftContour[index + 1] = edgeMeasurePosition[1] + offsetY;
                edgeRightContour[index] = edgeMeasurePosition[0] - offsetX;
                edgeRightContour[index + 1] = edgeMeasurePosition[1] - offsetY;
            }
            out.moveTo(edgeLeftContour[0], edgeLeftContour[1]);
            for (int i = 1; i <= samples; i++) out.lineTo(edgeLeftContour[i * 2], edgeLeftContour[i * 2 + 1]);
            for (int i = samples; i >= 0; i--) out.lineTo(edgeRightContour[i * 2], edgeRightContour[i * 2 + 1]);
            out.close();
        }

        @Override protected void onDetachedFromWindow() {
            if (edgeAnimator != null) { edgeAnimator.cancel(); edgeAnimator = null; }
            super.onDetachedFromWindow();
        }
    }
}
