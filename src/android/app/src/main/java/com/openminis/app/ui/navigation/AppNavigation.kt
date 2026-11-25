package com.openminis.app.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openminis.app.MinisApp
import com.openminis.app.crash.CrashFrequencyDetector
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.diagnostics.HangDetector
import com.openminis.app.diagnostics.LaunchCycleBeacon
import com.openminis.app.logging.AppLogger
import com.openminis.app.share.ShareCoordinator
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.getAppearancePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.UUID

// T342: Material 3 motion easing curves (EmphasizedDecelerate / EmphasizedAccelerate).
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    const val SHADOW_VOICE_DETAIL = "voice_service/{instanceId}"
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val BACKUP = "backup"
    const val BACKUP_INITIAL_TAB_KEY = "backup_initial_tab"
    const val BACKUP_DESTINATIONS = "backup_destinations"
    const val BACKUP_HISTORY_DETAIL = "backup_history_detail"
    const val BACKUP_DESTINATION_BROWSE = "backup_destination_browse"
    const val RESTORE_BROWSE = "restore_browse"
    const val RESTORE_SERVERS = "restore_servers"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryKey}"
    fun mirrorCategoryDetail(categoryKey: String) = "mirror_category/$categoryKey"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val ENV_VARS = "env_vars"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        val encoded = URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }

    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            if (initCommand != null) add("initCommand=${enc(initCommand)}")
            if (sessionId != null) add("sessionId=${enc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }

    const val CHAT_FILES = "chat_files/{sessionId}?path={path}"
    fun chatFiles(sessionId: String, path: String? = null) =
        if (path == null) "chat_files/$sessionId" else "chat_files/$sessionId?path=${Uri.encode(path)}"

    const val AGENT_TRANSCRIPT = "agent_transcript/{sessionId}"
    fun agentTranscript(sessionId: String) = "agent_transcript/$sessionId"
    const val MEMORY = "memory"
    const val AGENTS = "agents"
    const val SUB_AGENTS = "sub_agents"
    const val SUB_AGENT_DETAIL = "sub_agent/{agentId}"
    const val AGENT_TOOLS = "agent_tools"
    const val MCP = "mcp"
    const val SOUL = "soul"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    const val SHIZUKU = "shizuku"
    const val SYSTEM_PERMISSIONS = "system_permissions"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val APPEARANCE = "appearance"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}"
    fun scheduledTaskEdit(taskId: String? = null): String =
        if (taskId == null) "scheduled_tasks/edit" else "scheduled_tasks/edit?taskId=$taskId"
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"
    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun shadowVoiceDetail(instanceId: String) = "voice_service/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] Encode composite "<instanceId>/<modelId>" ids.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${Uri.encode(instanceId)}/${Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

/** Holder for file preview navigation state (not serializable via nav args). */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: MCPRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
) {
    val context = LocalContext.current
    val mountedFoldersStore = remember {
        (context.applicationContext as MinisApp).mountedFoldersStore
    }

    HandleInitialDeepLinkEffect(initialDeepLink, navController)
    ResolveLaunchSessionEffect(initialDeepLink, chatRepository, providerRepository, navController)
    HandleWarmShareEffect(navController)

    val startDestination = resolveStartDestination(initialDeepLink)

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
    ) {
        composable(Routes.SESSION_LIST) {
            ChatSplitScaffoldRoute(
                initialSessionId = null,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatSplitScaffoldRoute(
                initialSessionId = sessionId,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        addProviderRoutes(navController = navController, providerRepository = providerRepository)
        addBackupAndStorageRoutes(
            navController = navController,
            chatRepository = chatRepository,
            mountedFoldersStore = mountedFoldersStore,
        )
        addSettingsAndToolsRoutes(
            navController = navController,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            envVarRepository = envVarRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
            memoryRepository = memoryRepository,
        )
    }
}

@Composable
private fun HandleInitialDeepLinkEffect(
    initialDeepLink: DeepLinkAction?,
    navController: NavHostController,
) {
    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenTerminal -> navController.safeNavigate(Routes.terminal(initialDeepLink.initCommand))
            is DeepLinkAction.OpenSession -> navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                popUpTo(Routes.SESSION_LIST) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(
                    initialDeepLink.key,
                    initialDeepLink.value,
                    initialDeepLink.note,
                )
                navController.safeNavigate(Routes.ENV_VARS)
            }
            is DeepLinkAction.OpenSettingsScreen -> navController.safeNavigate(initialDeepLink.route)
            is DeepLinkAction.OpenPermissionSettings -> navController.safeNavigate(Routes.PERMISSIONS)
            else -> {}
        }
    }
}

@Composable
private fun ResolveLaunchSessionEffect(
    initialDeepLink: DeepLinkAction?,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    navController: NavHostController,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect

        val hasPendingShare = ShareCoordinator.bufferVersion.value > 0
        val rawMode = getAppearancePrefs(context).getInt(KEY_LAUNCH_SESSION, 0)
        val forceHome = HangDetector.shouldForceHomeOnLaunch(context) ||
            CrashFrequencyDetector.shouldForceHomeOnLaunch(context) ||
            LaunchCycleBeacon.shouldForceHomeOnLaunch()
        val mode = if (forceHome) 3 else rawMode
        val autoThresholdMs = 15L * 60 * 1000

        val launchSessions = withTimeoutOrNull(1_500L) {
            withContext(Dispatchers.IO) {
                runCatching { chatRepository.dao.listSessions() }.getOrElse { emptyList() }
            }
        } ?: emptyList()
        val hasAnySession = launchSessions.isNotEmpty()
        withTimeoutOrNull(1_200L) { providerRepository.awaitConfigLoaded() }
        val hasAnyProvider = providerRepository.instances.isNotEmpty()

        AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] launch/mode: rawMode=$rawMode effectiveMode=$mode " +
                "hasPendingShare=$hasPendingShare hasAnySession=$hasAnySession hasAnyProvider=$hasAnyProvider",
        )
        val target: String? = when {
            hasPendingShare && mode == 3 -> Routes.chat("__new__${UUID.randomUUID()}")
            !hasAnySession && !hasAnyProvider -> null
            mode == 1 -> launchSessions.firstOrNull { !it.isChild }?.let { Routes.chat(it.id) }
            mode == 2 -> Routes.chat("__new__${UUID.randomUUID()}")
            mode == 3 -> null
            else -> {
                val latest = launchSessions.firstOrNull { !it.isChild } ?: return@LaunchedEffect
                val now = System.currentTimeMillis()
                val fresh = now - latest.updatedAt < autoThresholdMs
                AppLogger.info(
                    "XSessionDiag",
                    "[XSessionDiag] launch/auto: candidate=${latest.id.take(8)} title=${latest.title?.take(24)} " +
                        "updatedAt=${latest.updatedAt} now=$now ageMs=${now - latest.updatedAt} thresholdMs=$autoThresholdMs " +
                        "fresh=$fresh -> ${if (fresh) "RESUME_EXISTING" else "NEW_DRAFT"}",
                )
                if (fresh) Routes.chat(latest.id) else Routes.chat("__new__${UUID.randomUUID()}")
            }
        }
        if (target != null) {
            // T314: navigate directly at cold-start before start destination reaches RESUMED.
            navController.navigate(target) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }
}

@Composable
private fun HandleWarmShareEffect(navController: NavHostController) {
    val shareBufferVersion by ShareCoordinator.bufferVersion.collectAsStateWithLifecycle()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        if (navController.currentDestination?.route == Routes.SESSION_LIST) {
            navController.safeNavigate(Routes.chat("__new__${UUID.randomUUID()}")) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }
}

private fun resolveStartDestination(initialDeepLink: DeepLinkAction?): String {
    val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
    if (htmlShortcut != null) {
        DeepLinkCoordinator.setPendingHtmlPreview(
            htmlShortcut.sessionId,
            htmlShortcut.resourcePath,
            htmlShortcut.title,
        )
        return Routes.chat(htmlShortcut.sessionId)
    }
    return when (initialDeepLink) {
        is DeepLinkAction.NewVoiceChat -> {
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE)
            Routes.chat("__new__${UUID.randomUUID()}")
        }
        is DeepLinkAction.NewCameraChat -> {
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
            Routes.chat("__new__${UUID.randomUUID()}")
        }
        is DeepLinkAction.NewChat -> Routes.chat("__new__${UUID.randomUUID()}")
        else -> Routes.SESSION_LIST
    }
}
