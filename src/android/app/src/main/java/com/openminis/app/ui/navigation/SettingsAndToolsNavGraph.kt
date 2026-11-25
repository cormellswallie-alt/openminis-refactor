package com.openminis.app.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.chat.AgentTranscriptScreen
import com.openminis.app.ui.scheduled.ScheduledTaskEditScreen
import com.openminis.app.ui.scheduled.ScheduledTaskRunsScreen
import com.openminis.app.ui.scheduled.ScheduledTasksScreen
import com.openminis.app.ui.settings.AboutScreen
import com.openminis.app.ui.settings.AgentToolsSettingsScreen
import com.openminis.app.ui.settings.AppearanceScreen
import com.openminis.app.ui.settings.BackgroundSettingsScreen
import com.openminis.app.ui.settings.EnvironmentVariablesScreen
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.MCPIntegrationsScreen
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.MinisSkillsBrowserScreen
import com.openminis.app.ui.settings.OffloadPermissionScreen
import com.openminis.app.ui.settings.SettingsScreen
import com.openminis.app.ui.settings.ShizukuPermissionScreen
import com.openminis.app.ui.settings.SkillDetailScreen
import com.openminis.app.ui.settings.SkillFileViewerScreen
import com.openminis.app.ui.settings.SkillsManagementScreen
import com.openminis.app.ui.settings.SoulSettingsScreen
import com.openminis.app.ui.settings.SubAgentDetailScreen
import com.openminis.app.ui.settings.SubAgentsScreen
import com.openminis.app.ui.settings.SystemPermissionsScreen
import com.openminis.app.ui.settings.UsageStatsScreen
import com.openminis.app.ui.terminal.TerminalScreen
import java.net.URLDecoder

internal fun NavGraphBuilder.addSettingsAndToolsRoutes(
    navController: NavHostController,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    memoryRepository: MemoryRepository?,
) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.safePopBackStack() },
            onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
            onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
            onRootfsClick = { navController.safeNavigate(Routes.STORAGE) },
            onBackupClick = { navController.safeNavigate(Routes.BACKUP) },
            onEnvVarsClick = { navController.safeNavigate(Routes.ENV_VARS) },
            onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
            onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
            onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
            onAgentsClick = { navController.safeNavigate(Routes.AGENTS) },
            onAgentToolsClick = { navController.safeNavigate(Routes.AGENT_TOOLS) },
            onMcpClick = { navController.safeNavigate(Routes.MCP) },
            onSoulClick = { navController.safeNavigate(Routes.SOUL) },
            onPermissionsClick = { navController.safeNavigate(Routes.PERMISSIONS) },
            onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
            onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
            onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
            onLogsClick = { navController.safeNavigate(Routes.LOGS) },
            onAboutClick = { navController.safeNavigate(Routes.ABOUT) },
            onMountedFoldersClick = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
            onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
        )
    }

    composable(Routes.ENV_VARS) {
        if (envVarRepository != null) {
            EnvironmentVariablesScreen(
                envVarRepository = envVarRepository,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    composable(Routes.SKILLS) {
        if (skillRepository != null) {
            SkillsManagementScreen(
                skillRepository = skillRepository,
                onBack = { navController.safePopBackStack() },
                onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                onMinisSkillsClick = { navController.safeNavigate(Routes.MINIS_SKILLS_BROWSER) },
            )
        }
    }

    composable(
        route = Routes.SKILL_DETAIL,
        arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
        if (skillRepository != null) {
            SkillDetailScreen(
                skillId = skillId,
                skillRepository = skillRepository,
                onBack = { navController.safePopBackStack() },
                onFileClick = { id, relativePath -> navController.safeNavigate(Routes.skillFile(id, relativePath)) },
            )
        }
    }

    composable(
        route = Routes.SKILL_FILE,
        arguments = listOf(
            navArgument("skillId") { type = NavType.StringType },
            navArgument("relativePath") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
        val rawPath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
        val relativePath = URLDecoder.decode(rawPath, "UTF-8")
        if (skillRepository != null) {
            SkillFileViewerScreen(
                skillId = skillId,
                relativePath = relativePath,
                skillRepository = skillRepository,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    composable(Routes.MINIS_SKILLS_BROWSER) {
        if (skillRepository != null) {
            MinisSkillsBrowserScreen(
                skillRepository = skillRepository,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    composable(
        Routes.TERMINAL,
        arguments = listOf(
            navArgument("initCommand") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("sessionId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        val context = LocalContext.current
        val initCommand = backStackEntry.arguments?.getString("initCommand")
        val sessionId = backStackEntry.arguments?.getString("sessionId")
        val session = remember { TerminalSession(context.applicationContext) }
        TerminalScreen(
            terminalSession = session,
            onBack = { navController.safePopBackStack() },
            initCommand = initCommand,
            sessionId = sessionId,
        )
    }

    composable(Routes.MEMORY) {
        if (memoryRepository != null) {
            MemoryManagementScreen(
                memoryRepository = memoryRepository,
                onBack = { navController.safePopBackStack() },
                onFileClick = { fileName, isGlobal ->
                    navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                },
            )
        }
    }

    composable(
        route = Routes.MEMORY_FILE_EDIT,
        arguments = listOf(
            navArgument("fileName") { type = NavType.StringType },
            navArgument("isGlobal") { type = NavType.BoolType },
        ),
    ) { backStackEntry ->
        val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
        val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
        if (memoryRepository != null) {
            MemoryFileEditScreen(
                fileName = fileName,
                isGlobal = isGlobal,
                memoryRepository = memoryRepository,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    composable(Routes.MCP) {
        if (mcpRepository != null) {
            MCPIntegrationsScreen(
                mcpRepository = mcpRepository,
                onBack = { navController.safePopBackStack() },
                envVarRepository = envVarRepository,
            )
        }
    }

    composable(Routes.SOUL) {
        SoulSettingsScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.PERMISSIONS) {
        OffloadPermissionScreen(
            onBack = { navController.safePopBackStack() },
            onOpenPrivilegedBackend = { navController.safeNavigate(Routes.SHIZUKU) },
        )
    }

    composable(Routes.SHIZUKU) {
        ShizukuPermissionScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.SYSTEM_PERMISSIONS) {
        SystemPermissionsScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.USAGE_STATS) {
        UsageStatsScreen(
            chatDao = chatRepository.dao,
            providerConfig = providerRepository.config.value,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.APPEARANCE) {
        AppearanceScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.BACKGROUND) {
        BackgroundSettingsScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.AGENTS) {
        SubAgentsScreen(
            onBack = { navController.safePopBackStack() },
            onOpen = { id -> navController.navigate("sub_agent/$id") },
        )
    }

    composable(Routes.SUB_AGENTS) {
        SubAgentsScreen(
            onBack = { navController.safePopBackStack() },
            onOpen = { id -> navController.navigate("sub_agent/$id") },
        )
    }

    composable(
        Routes.SUB_AGENT_DETAIL,
        arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
    ) { entry ->
        SubAgentDetailScreen(
            agentId = entry.arguments?.getString("agentId").orEmpty(),
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.AGENT_TOOLS) {
        AgentToolsSettingsScreen(onBack = { navController.safePopBackStack() })
    }

    composable(
        route = Routes.AGENT_TRANSCRIPT,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        AgentTranscriptScreen(
            sessionId = sessionId,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ABOUT) {
        AboutScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.LOGS) {
        LogManagementScreen(
            onBack = { navController.safePopBackStack() },
            onLogFileClick = { fileName -> navController.safeNavigate(Routes.logDetail(fileName)) },
        )
    }

    composable(
        route = Routes.LOG_DETAIL,
        arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
    ) { backStackEntry ->
        val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
        LogDetailScreen(
            fileName = fileName,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.SCHEDULED_TASKS) {
        ScheduledTasksScreen(
            onBack = { navController.safePopBackStack() },
            onEditTask = { taskId -> navController.safeNavigate(Routes.scheduledTaskEdit(taskId)) },
            onViewRuns = { taskId -> navController.safeNavigate(Routes.scheduledTaskRuns(taskId)) },
            onOpenSession = { sessionId -> navController.safeNavigate(Routes.chat(sessionId)) },
        )
    }

    composable(
        route = Routes.SCHEDULED_TASK_RUNS,
        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
        ScheduledTaskRunsScreen(
            taskId = taskId,
            onBack = { navController.safePopBackStack() },
            onOpenSession = { sessionId -> navController.safeNavigate(Routes.chat(sessionId)) },
        )
    }

    composable(
        route = Routes.SCHEDULED_TASK_EDIT,
        arguments = listOf(
            navArgument("taskId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        val taskId = backStackEntry.arguments?.getString("taskId")
        ScheduledTaskEditScreen(
            taskId = taskId,
            onBack = { navController.safePopBackStack() },
            onOpenSession = { sessionId -> navController.safeNavigate(Routes.chat(sessionId)) },
        )
    }
}
