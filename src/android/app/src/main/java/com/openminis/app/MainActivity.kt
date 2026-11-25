package com.openminis.app

import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate
import com.openminis.app.ui.NewerDatabaseGuidanceScreen
import com.openminis.app.ui.settings.KEY_DYNAMIC_COLOR
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import com.openminis.app.ui.theme.MinisTheme

private const val KEY_CURRENT_CHAT_SESSION_ID = "minis.current_chat_session_id"

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    /**
     * T166: id of the chat the user is currently inside, mirrored from
     * the nav back-stack so [onSaveInstanceState] can persist it across
     * process death. Restored value is fed into [AppNavigation] as the
     * initial deep-link so the user lands back where they were.
     */
    private var currentChatSessionId: String? = null

    /**
     * T166: id of the chat to re-open on first composition after a
     * process-death restart. Set in [onCreate] from the saved state
     * bundle, consumed exactly once by [AppNavigation] via the
     * synthesised deep-link.
     */
    private var restoredChatSessionId: String? = null

    /**
     * T293 / issue #10: tracks whether [onStart] has fired at least once, so
     * that the very first ON_START (the cold-start one — already covered by
     * [com.openminis.app.ui.navigation.AppNavigation]'s LaunchedEffect that
     * resolves [KEY_LAUNCH_SESSION]) is skipped here. Subsequent ON_STARTs
     * are background → foreground transitions, where the launch-session
     * preference must be honoured again so "New Chat on launch" actually
     * does what it says when the user resumes from the recents tray.
     */
    private var hasResumedFromBackground = false

    /**
     * Bridges [OffloadPermissionManager.requestAndroidPermission] to the system
     * runtime-permission dialog. Background offload handlers suspend while
     * this launcher's callback resolves the request.
     */
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    /**
     * Used by the "go to settings" gate to open e.g. the app-details or
     * Notification Access settings page. We don't rely on the result —
     * [OffloadPermissionManager.requestSettingsGate] polls until the
     * permission actually changes (or times out).
     */
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    /**
     * State shuttled between the "Save to..." path of the safe-mode
     * crash-share dialog and the `crashSaveLauncher` registered in
     * [onCreate]. The lambda fires after the launcher's result callback
     * completes so the caller (which passes `finish()` for safe-mode)
     * can tear down the Activity once the file is on disk and the
     * follow-on ACTION_VIEW has been dispatched.
     */
    private var pendingCrashZip: java.io.File? = null
    private var pendingCrashSaveOnClosed: (() -> Unit)? = null

    // T-n01-andmenu-l10n: on Android 12 and below `LocaleManager.applicationLocales`
    // does not exist, so the saved language pick has no effect on framework
    // strings — including the labels of the system text-selection ActionMode
    // ("Cut" / "Copy" / "Paste"). Override the base Configuration so the
    // framework picks the right locale on those devices. No-op on 13+.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    /**
     * T-android-safemode-lateinit-crash: escape hatch for a process whose
     * [MinisApp.onCreate] early-returned under safe-mode.
     *
     * That early return is irreversible within the process — the
     * repositories stay unassigned no matter what the safe-mode flag says
     * afterwards — so there is nothing this Activity can do to become
     * usable. Killing the process is the recovery: the next launch runs
     * Application.onCreate from the top, and by then the burst detector's
     * 24h suppress window (set by the dismiss the user just performed)
     * keeps safe-mode from tripping again, so init completes normally.
     *
     * We deliberately do NOT auto-relaunch an Activity here. Doing so from
     * a dying process races the system's own task restart on some OEM
     * builds (MIUI included) and can produce two tasks. Exiting cleanly
     * and letting the user's next tap start the app is predictable.
     */
    private fun finishAndRestartProcess() {
        try {
            android.widget.Toast.makeText(
                this,
                getString(R.string.crash_safe_mode_restart_needed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "restart toast failed: ${t.message}")
        }
        finish()
        // Let the toast render before tearing the process down. The delay
        // runs on the main looper of a process we are about to kill, which
        // is fine — nothing else is scheduled on it at this point.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1200L)
    }

    // [refresh-rate] Some ROMs drop the window's preferred mode when the app
    // returns from the background or the display configuration changes.
    override fun onResume() {
        super.onResume()
        com.openminis.app.ui.RefreshRateController.apply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register SAF launcher before STARTED (needed by the safe-mode crash dialog).
        val crashSaveLauncher = registerCrashSaveLauncher()
        val minisApp = application as? MinisApp
        if (handleDegradedOrSafeModeStartup(minisApp, crashSaveLauncher)) return

        restoredChatSessionId = savedInstanceState?.getString(KEY_CURRENT_CHAT_SESSION_ID)
            ?.takeUnless { com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(this) }

        registerPermissionAndRecoveryBridges()
        applySavedLanguage()
        com.openminis.app.share.ShareCoordinator.processPendingShare(this)

        lifecycleScope.launch {
            SessionActivityTracker.activeSessions.collect { active ->
                applyKeepScreenAwakeFlag(active.isNotEmpty())
            }
        }

        if (BuildConfig.DEV_TOOLS) {
            com.openminis.app.debug.DebugRPCHandler.currentActivity = java.lang.ref.WeakReference(this)
        }

        val app = requireNotNull(minisApp)
        val launchDeepLink = resolveLaunchDeepLink()
        com.openminis.app.ui.RefreshRateController.apply(this)

        setContent {
            MainActivityRoot(app = app, launchDeepLink = launchDeepLink)
        }
    }

    private fun registerCrashSaveLauncher() = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val targetUri = result.data?.data
        val zip = pendingCrashZip
        pendingCrashZip = null
        if (result.resultCode != android.app.Activity.RESULT_OK || targetUri == null || zip == null) {
            pendingCrashSaveOnClosed?.invoke()
            pendingCrashSaveOnClosed = null
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(targetUri)?.use { out ->
                java.io.FileInputStream(zip).use { input -> input.copyTo(out) }
            } ?: throw java.io.IOException("openOutputStream returned null")
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(targetUri, "application/zip")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure { android.util.Log.w("MainActivity", "ACTION_VIEW after save failed: ${it.message}") }
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "crash zip save failed: ${t.message}")
        } finally {
            pendingCrashSaveOnClosed?.invoke()
            pendingCrashSaveOnClosed = null
        }
    }

    /**
     * Handles newer-schema downgrade guidance and uninitialized/safe-mode startup.
     * Returns true when normal Activity setup must be skipped.
     */
    private fun handleDegradedOrSafeModeStartup(
        minisApp: MinisApp?,
        crashSaveLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    ): Boolean {
        if (minisApp != null &&
            minisApp.dbVersionDecision == com.openminis.app.data.db.DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE
        ) {
            android.util.Log.w("MainActivity", "showing newer-database guidance screen")
            setContent { NewerDatabaseGuidanceScreen(onExit = { finishAndRemoveTask() }) }
            return true
        }
        if (minisApp == null || !minisApp.subsystemsInitialized) {
            android.util.Log.w(
                "MainActivity",
                "app subsystems not initialized (safeMode=${com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()}) — showing crash share dialog and finishing",
            )
            com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(
                activity = this,
                onClosed = { finishAndRestartProcess() },
                saveLauncher = { zip, onSaveDone ->
                    pendingCrashZip = zip
                    pendingCrashSaveOnClosed = onSaveDone
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, zip.name)
                    }
                    try {
                        crashSaveLauncher.launch(intent)
                    } catch (t: Throwable) {
                        android.util.Log.w("MainActivity", "CREATE_DOCUMENT launch failed: ${t.message}")
                        pendingCrashZip = null
                        pendingCrashSaveOnClosed = null
                        onSaveDone()
                    }
                },
            )
            return true
        }
        return false
    }

    private fun registerPermissionAndRecoveryBridges() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            OffloadPermissionManager.respondToAndroidPermission(results.isNotEmpty() && results.values.all { it })
        }
        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { /* polled by OffloadPermissionManager.requestSettingsGate */ }

        lifecycleScope.launch {
            OffloadPermissionManager.pendingAndroidPermission.filterNotNull().collect { req ->
                val permanentlyDenied = req.permissions.any { permission ->
                    ContextCompat.checkSelfPermission(this@MainActivity, permission) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, permission) &&
                        OffloadPermissionManager.hasAskedForPermission(this@MainActivity, permission)
                }
                if (permanentlyDenied) {
                    OffloadPermissionManager.respondToAndroidPermission(
                        OffloadPermissionManager.AndroidPermissionResult.DENIED,
                    )
                } else {
                    req.permissions.forEach { OffloadPermissionManager.markPermissionAsked(this@MainActivity, it) }
                    permissionLauncher.launch(req.permissions.toTypedArray())
                }
            }
        }

        lifecycleScope.launch {
            OffloadPermissionManager.pendingSettingsGate.filterNotNull().collect { gate ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(gate.title)
                    .setMessage(gate.message)
                    .setCancelable(false)
                    .setPositiveButton(gate.positiveLabel) { d, _ ->
                        d.dismiss()
                        val intent = Intent(gate.settingsAction).apply {
                            if (gate.requiresPackageUri) data = Uri.fromParts("package", packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { settingsLauncher.launch(intent) }
                        OffloadPermissionManager.respondToSettingsGate(
                            OffloadPermissionManager.SettingsGateDecision.OPEN,
                        )
                    }
                    .setNegativeButton(gate.negativeLabel) { d, _ ->
                        d.dismiss()
                        OffloadPermissionManager.respondToSettingsGate(
                            OffloadPermissionManager.SettingsGateDecision.CANCEL,
                        )
                    }
                    .show()
            }
        }

        lifecycleScope.launch {
            AccessibilityRecoveryManager.pendingPrompt.filterNotNull().collect { prompt ->
                val builder = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.a11y_repair_dialog_title))
                    .setCancelable(true)
                    .setOnCancelListener {
                        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.CANCEL)
                    }
                    .setNegativeButton(R.string.a11y_repair_cancel) { d, _ ->
                        d.dismiss()
                        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.CANCEL)
                    }
                if (prompt.shizukuAvailable) {
                    builder.setMessage(getString(R.string.a11y_repair_dialog_message_shizuku))
                        .setPositiveButton(R.string.a11y_repair_action_repair) { d, _ ->
                            d.dismiss()
                            AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.REPAIR)
                        }
                } else {
                    builder.setMessage(getString(R.string.a11y_repair_dialog_message_manual))
                        .setPositiveButton(R.string.a11y_repair_action_open_settings) { d, _ ->
                            d.dismiss()
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                            AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.OPEN_SETTINGS)
                        }
                }
                builder.show()
            }
        }
    }

    private fun resolveLaunchDeepLink(): DeepLinkAction {
        val explicitDeepLink = DeepLinkHandler.parse(intent?.data)
        return if (explicitDeepLink !is DeepLinkAction.Unknown) {
            explicitDeepLink
        } else {
            restoredChatSessionId?.let { DeepLinkAction.OpenSession(it) } ?: DeepLinkAction.Unknown
        }
    }

    @androidx.compose.runtime.Composable
    private fun MainActivityRoot(app: MinisApp, launchDeepLink: DeepLinkAction) {
        val prefs = remember { getAppearancePrefs(this) }
        var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
        var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
        var dynamicColor by remember { mutableStateOf(prefs.getBoolean(KEY_DYNAMIC_COLOR, false)) }

        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                when (key) {
                    KEY_THEME_MODE -> themeMode = sp.getInt(KEY_THEME_MODE, 0)
                    KEY_FONT_APP_BASE -> appBaseLevel = sp.getInt(KEY_FONT_APP_BASE, 0)
                    KEY_DYNAMIC_COLOR -> dynamicColor = sp.getBoolean(KEY_DYNAMIC_COLOR, false)
                    KEY_KEEP_SCREEN_AWAKE -> applyKeepScreenAwakeFlag(
                        SessionActivityTracker.activeSessions.value.isNotEmpty(),
                    )
                    com.openminis.app.ui.RefreshRateController.KEY_REFRESH_RATE_MODE ->
                        com.openminis.app.ui.RefreshRateController.apply(this@MainActivity)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        val darkTheme = when (themeMode) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
        val fontScale = fontScaleForLevel(appBaseLevel)

        SideEffect {
            val barStyle = if (darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        }

        MinisTheme(darkTheme = darkTheme, fontScale = fontScale, dynamicColor = dynamicColor) {
            val navController = rememberNavController().also { this.navController = it }
            DisposableEffect(navController) {
                val job = lifecycleScope.launch {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        com.openminis.app.diagnostics.HangDetector.noteScreen(entry.destination.route)
                        val isChatRoute = entry.destination.route == Routes.CHAT
                        val sid = entry.arguments?.getString("sessionId").takeIf { isChatRoute }
                        val previous = currentChatSessionId
                        if (sid != previous) {
                            if (previous != null) SessionActivityTracker.setAbsent(previous)
                            if (sid != null) SessionActivityTracker.setPresent(sid)
                            currentChatSessionId = sid
                        }
                    }
                }
                onDispose { job.cancel() }
            }

            AppNavigation(
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                envVarRepository = app.envVarRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
                memoryRepository = app.memoryRepository,
                navController = navController,
                initialDeepLink = launchDeepLink,
            )
            com.openminis.app.ui.settings.ConfigConfirmDialogHost()
        }
    }

    /**
     * T166: persist the current chat sessionId so an LMK kill while in
     * chat restarts back to the same session (see `restoredChatSessionId`
     * in [onCreate]). Called by the OS in the same lifecycle phase that
     * Activity Recreation uses, so a configuration change also goes
     * through this path — which is exactly what we want; the state is
     * cheap and re-read is idempotent.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentChatSessionId?.let {
            outState.putString(KEY_CURRENT_CHAT_SESSION_ID, it)
        }
    }

    /**
     * T293 / issue #10: re-honour the "Launch Session" preference when the
     * app comes back to the foreground from the recents tray. Cold start
     * is already covered by [com.openminis.app.ui.navigation.AppNavigation]
     * resolving [KEY_LAUNCH_SESSION] in its `LaunchedEffect(Unit)` —
     * skipped here via [hasResumedFromBackground] so we don't double-fire
     * on the first start.
     *
     * Scoped to mode 2 (New Chat). Modes 0 (Auto) / 1 (Last Session) /
     * 3 (Home) are intentionally NOT re-evaluated on resume — bouncing
     * the user out of whatever screen they were on every time they
     * returned from the recents tray would be jarring. "New Chat" is the
     * only mode whose semantics genuinely demand a fresh chat on every
     * activation (cold or warm) — that's exactly the user expectation
     * the issue is reporting on.
     */
    override fun onStart() {
        super.onStart()
        if (!hasResumedFromBackground) {
            hasResumedFromBackground = true
            return
        }
        val mode = getAppearancePrefs(this).getInt(KEY_LAUNCH_SESSION, 0)
        if (mode != 2) return
        val nav = navController ?: return
        val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
        AppLogger.info("LaunchSession", "resume → mode=NewChat, navigating to $newRoute")
        nav.safeNavigate(newRoute) {
            popUpTo(Routes.SESSION_LIST) { inclusive = false }
        }
    }

    /**
     * T166: when the Activity is genuinely torn down (not just paused),
     * release any presence we held. Pause / Home / lock-screen do NOT
     * trigger this — those are exactly the cases the FG service exists
     * to bias OOM through.
     */
    override fun onDestroy() {
        currentChatSessionId?.let { SessionActivityTracker.setAbsent(it) }
        currentChatSessionId = null
        super.onDestroy()
    }

    /**
     * Apply (or release) the activity window's `FLAG_KEEP_SCREEN_ON` based on
     * the user's "Keep Screen Awake" toggle and whether any session has an
     * active task right now.
     *
     * [T-android-orientation-user] (OpenMinis#401) Touch the window only when
     * the flag actually changes. `Window.addFlags` / `clearFlags` are NOT
     * no-ops when the flag is already in that state: both go through
     * `setFlags`, which always dispatches a window-attributes change — a
     * relayout round trip to the WindowManager. This runs on every
     * activeSessions emission (each task start and stop, per session), and
     * each of those redundant updates was a chance for the system to
     * re-evaluate rotation; on OnePlus / Android 16 that rotated the screen
     * against a portrait lock.
     */
    private fun applyKeepScreenAwakeFlag(hasActiveSession: Boolean) {
        val want = keepScreenAwakeEnabled(this) && hasActiveSession
        if (!keepScreenOnNeedsChange(window.attributes.flags, want)) return
        if (want) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info("KeepScreenAwake", "screen-on lock acquired (active sessions present)")
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info(
                "KeepScreenAwake",
                "screen-on lock released (toggle=${keepScreenAwakeEnabled(this)}, active=$hasActiveSession)",
            )
        }
    }

    private fun applySavedLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val code = getSharedPreferences(PREF_APPEARANCE, MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        val desired = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        if (localeManager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
            localeManager.applicationLocales = desired
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // T51: warm-start share — ShareReceiverActivity re-launches with
        // FLAG_ACTIVITY_CLEAR_TOP, which delivers the new intent here when
        // MainActivity is already alive. Process the buffered share before
        // touching the deep-link path so the share coordinator sees it.
        if (intent.getBooleanExtra("shared_content", false)) {
            com.openminis.app.share.ShareCoordinator.processPendingShare(this)
        }
        handleDeepLink(intent.data)
    }

    private fun handleDeepLink(uri: Uri?) {
        val action = DeepLinkHandler.parse(uri)
        val nav = navController ?: return
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                nav.navigate(Routes.terminal(action.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix (secondary): mirror AppNavigation's
                // OpenSession options so a runtime deep-link (notification /
                // shortcut / onNewIntent) can't stack a duplicate chat on
                // top of the same chat that's already showing.
                nav.navigate(Routes.chat(action.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(action.key, action.value, action.note)
                nav.navigate(Routes.ENV_VARS)
            }
            // T183: any settings screen reachable by route string.
            is DeepLinkAction.OpenSettingsScreen -> {
                nav.navigate(action.route)
            }
            is DeepLinkAction.OpenPermissionSettings -> {
                nav.navigate(Routes.PERMISSIONS)
            }
            is DeepLinkAction.OpenHtmlPreview -> {
                DeepLinkCoordinator.setPendingHtmlPreview(
                    action.sessionId,
                    action.resourcePath,
                    action.title,
                )
                nav.navigate(Routes.chat(action.sessionId))
            }
            // App-icon quick actions (mirrors iOS QuickActionRouter). All
            // three open a fresh draft chat; voice/camera additionally seed
            // DeepLinkCoordinator.pendingChatAction so ChatScreen auto-fires
            // the corresponding UI on first compose.
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewVoiceChat,
            is DeepLinkAction.NewCameraChat -> {
                when (action) {
                    is DeepLinkAction.NewVoiceChat -> DeepLinkCoordinator
                        .setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE)
                    is DeepLinkAction.NewCameraChat -> DeepLinkCoordinator
                        .setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
                    else -> {}
                }
                val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
                nav.navigate(newRoute) {
                    popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    launchSingleTop = true
                }
            }
            is DeepLinkAction.OpenAlarmList -> {
                // T297: minis://views/alarm now opens the system Clock app
                // directly via AlarmClock.ACTION_SHOW_ALARMS — the in-app
                // AlarmListScreen was a one-button passthrough that did the
                // exact same thing. The android-alarm tool envelope still
                // emits this view_url so existing chat cards keep working.
                val showAlarms = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(showAlarms)
                } catch (_: android.content.ActivityNotFoundException) {
                    AppLogger.warning("DeepLink", "OpenAlarmList: no Clock app handles SHOW_ALARMS")
                }
            }
            else -> {}
        }
    }
}

/**
 * [T-android-orientation-user] (OpenMinis#401) Whether the window's current
 * [flags] differ from the wanted `FLAG_KEEP_SCREEN_ON` state, i.e. whether
 * applyKeepScreenAwakeFlag has anything to do.
 */
internal fun keepScreenOnNeedsChange(flags: Int, want: Boolean): Boolean {
    val on = (flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    return on != want
}
