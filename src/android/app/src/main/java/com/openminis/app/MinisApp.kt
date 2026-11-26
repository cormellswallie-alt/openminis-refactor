package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.DatabaseVersionGuard
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import com.openminis.app.sandbox.offload.ShizukuOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.launch

class MinisApp : Application(), ImageLoaderFactory {
    /**
     * T-android-safemode-lateinit-crash: true once the heavy subsystem
     * block in [onCreate] has fully run (DB + every repository assigned).
     *
     * Safe-mode makes [onCreate] early-return BEFORE those assignments,
     * and that early return is permanent for the life of the process —
     * the Application object is never re-created just because the user
     * dismissed the crash dialog. Callers must therefore not use
     * `CrashFrequencyDetector.isSafeMode()` as a proxy for "are the
     * repositories usable": that flag flips back to false on dismiss
     * while the repositories stay unassigned forever.
     *
     * This flag is the authoritative signal instead — it only ever goes
     * false → true, and only after every lateinit below is assigned.
     */
    @Volatile
    var subsystemsInitialized: Boolean = false
        private set

    /**
     * [T-android-downgrade-compat] Why init was skipped, when it was.
     *
     * Distinguishes "the database is from a newer build" (recoverable: the
     * user just needs to reinstall the newer version, and nothing on disk has
     * been touched) from a genuine init failure, so MainActivity can show
     * accurate guidance instead of a generic crash-report prompt.
     */
    @Volatile
    var dbVersionDecision: DatabaseVersionGuard.Decision =
        DatabaseVersionGuard.Decision.PROCEED
        private set

    /**
     * [T-android-safemode-lateinit-crash-147] Null-safe view of
     * [chatRepository] for code that can run BEFORE (or entirely without)
     * MainActivity.
     *
     * GH#147: a user installed a third-party skill and the app then crashed on
     * every launch with `lateinit property chatRepository has not been
     * initialized`. MainActivity has guarded this since b72dc591, but that
     * guard only covers the UI entry point. Alarms, notification taps, the
     * notification-listener service and the scheduled-task runner can all
     * start the process with NO Activity at all: Android creates the
     * Application, `onCreate` early-returns under safe-mode, and the component
     * then reads a lateinit that will never be assigned for the life of the
     * process.
     *
     * Reading this property instead of the lateinit turns a hard crash into a
     * null the caller can handle — the process stays alive, the crash-burst
     * detector is not re-tripped, and the user is not locked out.
     *
     * Deliberately checks [subsystemsInitialized] rather than
     * `::chatRepository.isInitialized`: the latter would report true midway
     * through onCreate, when chatRepository is assigned but the repositories
     * assigned after it are not — callers would then trip over the NEXT
     * uninitialized field instead. One flag, one meaning: "everything the app
     * layer needs is ready".
     */
    val chatRepositoryOrNull: ChatRepository?
        get() = if (subsystemsInitialized) chatRepository else null

    /**
     * [T-android-share-launch-crash] Same contract as [chatRepositoryOrNull],
     * for the share-import path.
     *
     * ShareReceiverActivity is reachable from the system share sheet at any
     * time, including in a process whose [onCreate] early-returned under
     * safe-mode. Reading the `providerRepository` lateinit there would throw
     * UninitializedPropertyAccessException from a dialog callback and crash the
     * app — writing another crash log and feeding the very burst detector that
     * put the process in safe-mode. Returning null instead lets the caller show
     * "import failed", which it already does for the missing-Application case.
     */
    val providerRepositoryOrNull: ProviderRepository?
        get() = if (subsystemsInitialized) providerRepository else null

    /**
     * [T-android-safemode-lateinit-crash-147] True when the app-layer
     * dependencies are usable. Prefer this over
     * `CrashFrequencyDetector.isSafeMode()`, which flips back to false the
     * moment the user dismisses the crash dialog while the repositories stay
     * unassigned forever (that mismatch was the b72dc591 crash loop).
     */
    fun subsystemsReady(): Boolean = subsystemsInitialized

    lateinit var database: AppDatabase
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var envVarRepository: EnvVarRepository
        private set
    lateinit var skillRepository: SkillRepository
        private set
    lateinit var mcpRepository: MCPRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var webAppShortcutRepository: WebAppShortcutRepository
        private set
    lateinit var backgroundSettingsRepository: BackgroundSettingsRepository
        private set
    lateinit var backgroundTaskNotifier: BackgroundTaskNotifier
        private set
    lateinit var mountedFoldersStore: MountedFoldersStore
        private set

    /**
     * T180-bg-notif: foreground-Activity counter, mutated by the
     * ActivityLifecycleCallbacks registered in [onCreate]. Read by
     * [BackgroundTaskNotifier] to decide whether to suppress completion
     * notifications (no notification while the user is already looking
     * at the app).
     */
    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    /**
     * T-bg-overlay phase 2: live "is the app foreground?" stream so the
     * AgentForegroundService can react to background ↔ foreground
     * transitions and toggle the floating tool-status overlay. Same
     * source as [isAppForeground] (started/stopped balanced count) —
     * just exposed as a StateFlow for collectors.
     */
    // Initial true: prevents overlay flash before first Activity onStart emits foreground=true
    // (T-overlay-startup-flash). ActivityLifecycleCallbacks below will flip to the real value
    // on the next lifecycle tick.
    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    /**
     * App-wide network monitor. Mirrors iOS NetworkMonitor.shared — observes
     * connectivity transitions, evicts shared OkHttp connection pools, and
     * refreshes the sandbox's /etc/resolv.conf when DNS servers change.
     */
    val networkMonitor: NetworkMonitor = NetworkMonitor()

    /**
     * Application-scoped BrowserTabPool for shell-invoked `minis-browser-use`.
     * Separate from the per-ChatViewModel pool so browser state driven from
     * within an ish shell doesn't collide with the agent's own tabs.
     */
    val sharedBrowserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(this).also { it.setSession("minis-browser-use") }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // T283: install ACRA before any app-level singleton runs so a crash
        // anywhere from onCreate forward is captured. CrashFileSender
        // (registered via META-INF/services/org.acra.sender.ReportSenderFactory)
        // writes filesDir/logs/crash-<stamp>.log — same dir + .log extension
        // that AppLogger.listLogFiles already filters for, so reports surface
        // in LogManagementScreen with no extra UI.
        ACRA.init(
            this,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // [T-android-content-capture-off] Opt Compose out of content capture before any window exists.
        androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false
        com.openminis.app.tools.AgentToolSwitch.migrateLegacyIfNeeded(this)

        // T287: :acra reporter runs in a separate process and must not boot app subsystems.
        if (ACRA.isACRASenderServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        if (!installCrashGuardsAndCheckSafeMode()) return
        if (!initializeCoreRepositories()) return
        initializeConfigAndSandbox()
        registerAndStartOffloadServer()
        initializeSessionTrackingAndNotifiers()
        registerLifecycleAndSystemHooks()
        runCatching { migrateGhostAlarms() }
            .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
    }

    /**
     * Primes lightweight crash/pref hooks, installs native + FGS-timeout crash
     * handlers, and checks safe-mode. Returns false if safe-mode short-circuits startup.
     */
    private fun installCrashGuardsAndCheckSafeMode(): Boolean {
        AppLogger.primeContext(this)
        com.openminis.app.data.FastModePrefs.prime(this)
        com.openminis.app.data.AutoCompactPrefs.prime(this)

        try {
            com.openminis.app.crash.NativeCrashHandler.install(java.io.File(filesDir, "logs"))
        } catch (t: Throwable) {
            Log.w("MinisApp", "NativeCrashHandler install failed: ${t.message}")
        }

        // [T-android-alarm-reschedule-on-restart] Re-register alarms after force-stop / OEM cleanup.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2500L)
            runCatching {
                com.openminis.app.scheduled.ScheduledTaskManager(this@MinisApp).rescheduleAll()
            }.onFailure {
                Log.w("MinisApp", "startup rescheduleAll failed: ${it.message}")
            }
        }

        installForegroundServiceTimeoutHandler()
        com.openminis.app.crash.CrashFrequencyDetector.checkAtLaunch(this)

        // [OpenMinis#363] Recover previous process death exit info off the main thread.
        Thread {
            com.openminis.app.crash.ExitInfoCollector.collect(this)
        }.apply { isDaemon = true; name = "exit-info-collect" }.start()

        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return false
        }
        return true
    }

    private fun installForegroundServiceTimeoutHandler() {
        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val isFgsTimeout = throwable.javaClass.name.endsWith(
                        "RemoteServiceException\$ForegroundServiceDidNotStopInTimeException",
                    ) || (throwable.message?.contains("foreground service of type") == true &&
                        throwable.message?.contains("did not stop within its timeout") == true)
                    if (isFgsTimeout) {
                        Log.w("MinisApp", "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}")
                        runCatching {
                            stopService(Intent(this, com.openminis.app.service.AgentForegroundService::class.java))
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }
    }

    /**
     * Initializes logging, hang detection, database version guard, and all core
     * repositories. Returns false to enter degraded mode if initialization throws.
     */
    private fun initializeCoreRepositories(): Boolean {
        AppLogger.init(this)
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${t.message}")
        }
        com.openminis.app.diagnostics.HangDetector.start(this)

        // [T-android-downgrade-compat] Probe schema version read-only before Room opens the DB.
        dbVersionDecision = DatabaseVersionGuard.evaluate(this)
        return try {
            if (dbVersionDecision == DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE) {
                error(
                    "on-disk chat schema is newer than this build " +
                        "(code=${DatabaseVersionGuard.CODE_DB_VERSION}); refusing to open it. " +
                        "The database file is left completely untouched — upgrading restores everything.",
                )
            }
            database = AppDatabase.getInstance(this)
            chatRepository = ChatRepository(database.chatDao())
            com.openminis.app.agent.jobs.AgentSettings.init(this)
            wireSubAgentFollowUpDispatcher()
            com.openminis.app.agent.jobs.AgentCallbackLabels.install(this)
            providerRepository = ProviderRepository(this)
            envVarRepository = EnvVarRepository(this)
            skillRepository = SkillRepository(this)
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        skillRepository.requestReload("foreground")
                    }
                },
            )
            mcpRepository = MCPRepository(this)
            memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))
            webAppShortcutRepository = WebAppShortcutRepository(database.webAppShortcutDao())
            subsystemsInitialized = true
            true
        } catch (t: Throwable) {
            // [T-android-safemode-lateinit-crash-147] Leave subsystemsInitialized=false so MainActivity shows recovery UI.
            Log.e("MinisApp", "subsystem init failed — app will start in degraded mode", t)
            false
        }
    }

    private fun wireSubAgentFollowUpDispatcher() {
        com.openminis.app.agent.jobs.AgentJobRegistry.followUpDispatcher = { parentId, text, jobId ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    if (chatRepository.getSession(parentId) == null) {
                        AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} dropped — parent ${parentId.take(8)} no longer exists")
                        return@launch
                    }
                    val r = com.openminis.app.debug.HeadlessChatRunner.prompt(
                        context = this@MinisApp,
                        sessionId = parentId,
                        text = text,
                        attachments = emptyList(),
                        thinkingLevel = null,
                        wait = false,
                        timeoutMs = 0L,
                    )
                    AppLogger.info("AgentJobRegistry", "followUp ${jobId.take(8)} → parent ${parentId.take(8)} outcome=${r.status}")
                }.onFailure {
                    AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} failed: ${it.message}")
                }
            }
        }
    }

    private fun initializeConfigAndSandbox() {
        com.openminis.app.agent.SoulStore.ensureExists(this)
        com.openminis.app.agent.SoulStore.refreshCache(this)

        com.openminis.app.config.MinisConfigPermissionStore.init(this)
        com.openminis.app.config.audit.ConfigAuditLog.init(this)
        com.openminis.app.config.ConfigRegistry.init(this, providerRepository, envVarRepository, chatRepository)

        ModelsDevApi.init(this)
        // [T-android-model-sort-anr] Warm release ranking index off-main AFTER ModelsDevApi.init.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { com.openminis.app.provider.ModelReleaseIndex.warmUp() }
                .onFailure { AppLogger.warning("MinisApp", "release-index warm-up failed: ${it.message}") }
        }

        RootfsManager.getInstance(this)
        ExecutionCoordinator.init(this)
        ExecutionCoordinator.envVarRepository = envVarRepository
        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        com.openminis.app.data.EnvVarRedactor.envVarRepository = envVarRepository

        networkMonitor.start(this)
        PRootKernel.registerGlobalBindMounts(this)

        mountedFoldersStore = MountedFoldersStore(this)
        PRootKernel.mountedFoldersStore = mountedFoldersStore
        mountedFoldersStore.onChange = {
            PRootKernel.applyMountedFoldersSnapshot(this)
            ExecutionCoordinator.stopCurrentCommand()
        }
        PRootKernel.applyMountedFoldersSnapshot(this)
    }

    private fun registerAndStartOffloadServer() {
        NativeOffloadServer.register("android-alarm", AlarmOffloadHandler(this))
        NativeOffloadServer.register("android-calendar", CalendarOffloadHandler(this))
        NativeOffloadServer.register("android-clipboard", ClipboardOffloadHandler(this))
        NativeOffloadServer.register("android-contacts", ContactsOffloadHandler(this))
        NativeOffloadServer.register("android-device", DeviceOffloadHandler(this))
        NativeOffloadServer.register("android-location", LocationOffloadHandler(this))
        NativeOffloadServer.register("android-notification", NotificationOffloadHandler(this))
        NativeOffloadServer.register("android-open", OpenOffloadHandler(this))
        NativeOffloadServer.register("android-photos", PhotosOffloadHandler(this))
        NativeOffloadServer.register("android-player", PlayerOffloadHandler())
        NativeOffloadServer.register("android-speak", SpeakOffloadHandler(this))
        NativeOffloadServer.register("android-speech", SpeechOffloadHandler(this))
        NativeOffloadServer.register("android-weather", WeatherOffloadHandler(this))
        NativeOffloadServer.register("android-a11y-cli", AccessibilityOffloadHandler(this))
        NativeOffloadServer.register("minis-model-use", ModelUseOffloadHandler(this, providerRepository))
        NativeOffloadServer.register("minis-config", com.openminis.app.sandbox.offload.ConfigOffloadHandler())
        NativeOffloadServer.register("minis-browser-use", BrowserUseOffloadHandler(this))
        NativeOffloadServer.register("minis-sessions-cli", SessionsOffloadHandler(chatRepository))
        NativeOffloadServer.register("minis-scheduled", com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler(this))
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)

        if (BuildConfig.DEV_TOOLS) {
            NativeOffloadServer.register("minis-debug", com.openminis.app.sandbox.offload.DebugOffloadHandler(this))
        }

        try {
            NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir, packageName)
        } catch (e: Exception) {
            AppLogger.error("NativeOffloadServer", "start failed — continuing without shell offload: ${e.message}")
        }
    }

    private fun initializeSessionTrackingAndNotifiers() {
        SessionActivityTracker.init(this)
        com.openminis.app.service.SessionBadgeStore.init(this)

        // Defer DB-heavy cold-start scans past first frame paint.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2500L)
            val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
            val active = SessionActivityTracker.activeSessions.value
            com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000L)
            runCatching {
                com.openminis.app.data.session.OrphanChildSweeper.sweep(this@MinisApp, chatRepository)
            }.onFailure {
                AppLogger.warning("OrphanSweep", "sweep threw: ${it.message}")
            }
        }

        backgroundSettingsRepository = BackgroundSettingsRepository(this)
        backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, isError ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, isError)
        }

        val configConfirmNotifier = com.openminis.app.notification.ConfigConfirmNotifier(
            context = this,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        com.openminis.app.config.confirm.ConfigConfirmationGate.backgroundNotifier = {
            configConfirmNotifier.notifyIfBackgrounded(it)
        }
        com.openminis.app.config.confirm.ConfigConfirmationGate.cancelNotification = {
            configConfirmNotifier.cancel(it)
        }
    }

    private fun registerLifecycleAndSystemHooks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) _isAppForegroundFlow.value = true
                if (wasBackgrounded && ::backgroundTaskNotifier.isInitialized) {
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                }
                if (wasBackgrounded) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
                        val active = SessionActivityTracker.activeSessions.value
                        com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { mountedFoldersStore.refreshWritability() }
                }
                providerRepository.refreshAllModelsIfNeeded(
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                )
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    com.openminis.app.config.confirm.ConfigConfirmationGate.notifyPending()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        OffloadPermissionManager.init(this)
        com.openminis.app.speech.SpeechRecognitionManager.init(this)
        providerRepository.refreshAllModelsIfNeeded(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
        )

        val sandboxSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> scope.launch {
                        runCatching { ExecutionCoordinator.broadcastTimezoneChange() }
                            .onFailure { Log.w("MinisApp", "broadcastTimezoneChange failed: ${it.message}") }
                    }
                    android.net.Proxy.PROXY_CHANGE_ACTION -> scope.launch {
                        runCatching { ExecutionCoordinator.broadcastProxyChange() }
                            .onFailure { Log.w("MinisApp", "broadcastProxyChange failed: ${it.message}") }
                    }
                }
            }
        }
        registerReceiver(
            sandboxSystemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.net.Proxy.PROXY_CHANGE_ACTION)
            },
        )

        if (BuildConfig.DEV_TOOLS) {
            try {
                com.openminis.app.debug.DebugServer(this).start()
            } catch (e: Exception) {
                Log.w("MinisApp", "Failed to start debug server: ${e.message}")
            }
        }
    }

    /**
     * T268: replay any pre-T266 internal alarm/timer entries from
     * minis_alarms_prefs through SET_ALARM / SET_TIMER, then clear the
     * prefs blob so subsequent launches no-op. Past-dated entries are
     * dropped (the OS never re-fires them anyway). Idempotent: if the
     * blob is missing or empty the function returns immediately.
     *
     * Silent migration rather than an in-app dialog — Application has no
     * Activity context to host one, and the user-visible outcome (alarms
     * reappear in their Clock app) is what they want regardless of any
     * prompt. AlarmOffloadManager's PendingIntents are left in place; the
     * OS will fire them once more if scheduled, but T268 also clears the
     * prefs blob that AlarmOffloadHandler previously read, so list/cancel
     * commands will no longer surface them.
     */
    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue  // Past timer — nothing to recover.
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue  // Past one-shot alarm.
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    /**
     * Coil global ImageLoader — registers [MinisImageFetcher] so `minis://`
     * URIs in Markdown images (e.g. `![alt](minis://attachments/x.png)`)
     * resolve to local files under /var/minis/.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                // T-image-cache-mtime-35133: include File.lastModified() in
                // memory + disk cache key so Grok-style in-place rewrites of
                // minis://attachments/foo.jpg invalidate Coil's cached bitmap.
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    /**
     * [GH#206] Respond to system memory pressure.
     *
     * The app previously implemented NOTHING here (no `onTrimMemory`, no
     * `onLowMemory` anywhere in the codebase), so every warning the system sent
     * on the way to an OOM was ignored and the only remaining lever was killing
     * the process. The reported failure had the app pinned at a ~1.94 GB native
     * heap while ART ran 478 concurrent-copying GCs in 30 minutes without
     * recovering — expected, because the memory sat in NATIVE bitmap pixels that
     * Java GC cannot reclaim. These caches are exactly that memory, and every
     * entry is reconstructible by re-rendering, so dropping them is free apart
     * from a re-render.
     *
     * Deliberately staged rather than "clear everything on any signal":
     *   - RUNNING_LOW and above (and any BACKGROUND-class level, which means we
     *     are on the LRU list and cheap to kill): drop the formula bitmaps.
     *   - COMPLETE: additionally tear down the offscreen KaTeX WebView, which
     *     is itself a large native allocation. It is recreated on next render.
     *
     * RUNNING_MODERATE is intentionally NOT acted on: it fires routinely on
     * healthy devices, and re-rendering formulas on every mild dip would trade a
     * real user-visible cost for little memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val dropFormulaCaches = when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (!dropFormulaCaches) return

        Log.i("MinisApp", "onTrimMemory(level=$level): releasing formula bitmap caches")
        runCatching { com.openminis.app.ui.chat.KatexWebViewPool.evictAll() }
            .onFailure { Log.w("MinisApp", "KatexWebViewPool.evictAll failed: ${it.message}") }
        runCatching { com.openminis.app.ui.markdown.KaTeXRendererCache.evictAll() }
            .onFailure { Log.w("MinisApp", "KaTeXRendererCache.evictAll failed: ${it.message}") }

        // [T-android-browser-global-tab-cap] Browser tabs are the largest
        // reclaimable allocation in the process — each is an out-of-process
        // WebView renderer — so they go at the same staged threshold as the
        // formula bitmaps above rather than waiting for COMPLETE. Only IDLE
        // tabs are released; a tab an agent is mid-action on stays, because
        // failing a running task is a worse outcome than the memory it holds,
        // and the registry can still preempt it later if pressure persists.
        runCatching {
            com.openminis.app.browser.BrowserTabPoolRegistry.handleMemoryPressure()
        }.onFailure { Log.w("MinisApp", "BrowserTabPoolRegistry.handleMemoryPressure failed: ${it.message}") }

        // [T-android-trimmemory-vmstore] Cached ChatViewModels are the other
        // large reclaimable allocation: each holds a session's messages,
        // agentHistory and flattened render rows. ChatViewModelStore's own cap
        // is a soft, count-based LRU that only trims when a NEW session is
        // added, so four legitimately-cached heavy sessions survive any amount
        // of external memory pressure. This is the path that gives them back.
        //
        // RUNNING_CRITICAL and above only — deliberately one step stricter than
        // the formula/browser caches above. Dropping a cached session costs a
        // full DB reload + re-flatten when the user navigates back to it, which
        // is far more visible than re-rendering a formula bitmap, so it is not
        // worth doing on RUNNING_LOW. Sessions running an agent loop and the
        // on-screen session are exempt inside the store.
        // NOT a `level >= RUNNING_CRITICAL` test. The constants are not a
        // severity ladder: UI_HIDDEN (20) outranks RUNNING_CRITICAL (15) but
        // means only "the UI went away", which happens on every home-button
        // press. Treating that as pressure would throw away every cached
        // session on a routine app switch and make coming back slow for no
        // memory reason. Enumerate the levels that actually mean "reclaim".
        val dropCachedSessions = when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,   // still foreground, genuinely low
            TRIM_MEMORY_BACKGROUND,         // on the LRU list, cheap to kill
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (dropCachedSessions) {
            runCatching {
                val freed = com.openminis.app.ui.chat.ChatViewModelStore.handleMemoryPressure()
                Log.i("MinisApp", "onTrimMemory(level=$level): released $freed cached session store(s)")
            }.onFailure { Log.w("MinisApp", "ChatViewModelStore.handleMemoryPressure failed: ${it.message}") }
        }

        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.i("MinisApp", "onTrimMemory(level=$level): tearing down the offscreen KaTeX WebView")
            runCatching { com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView() }
                .onFailure { Log.w("MinisApp", "KatexWebViewPool.releaseWebView failed: ${it.message}") }
        }
    }

    override fun onTerminate() {
        // onTerminate is called only on emulators or when the system
        // explicitly tears down — real devices usually skip it. Still
        // worth marking the beacon: a present clean_exit on a real
        // device proves we shut down voluntarily; its absence is the
        // signal we care about for [LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${t.message}")
        }
        super.onTerminate()
    }

}
