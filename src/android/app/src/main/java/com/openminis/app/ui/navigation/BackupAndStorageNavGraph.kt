package com.openminis.app.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.navArgument
import com.openminis.app.R
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FilePreviewScreen
import com.openminis.app.ui.sandbox.MirrorCatalog
import com.openminis.app.ui.sandbox.MirrorCategoryDetailScreen
import com.openminis.app.ui.sandbox.RootfsManagementScreen
import com.openminis.app.ui.settings.MountDetailScreen
import com.openminis.app.ui.settings.MountedFoldersScreen
import com.openminis.app.ui.settings.SessionStorageDetailScreen
import com.openminis.app.ui.settings.SharedFolderDetailScreen
import com.openminis.app.ui.settings.SharedFoldersScreen
import com.openminis.app.ui.settings.StorageManagementScreen
import com.openminis.app.ui.settings.StorageUsageViewModel
import com.openminis.app.ui.settings.backup.BackupAndRestoreScreen
import com.openminis.app.ui.settings.backup.BackupDestinationBrowseScreen
import com.openminis.app.ui.settings.backup.BackupHistoryDetailScreen
import com.openminis.app.ui.settings.backup.BackupViewModel
import com.openminis.app.ui.settings.backup.RcloneDestinationsScreen
import com.openminis.app.ui.settings.backup.RestoreBrowseScreen
import com.openminis.app.ui.settings.backup.RestoreServersScreen
import java.io.File

internal fun NavGraphBuilder.addBackupAndStorageRoutes(
    navController: NavHostController,
    chatRepository: ChatRepository,
    mountedFoldersStore: MountedFoldersStore,
) {
    composable(Routes.BACKUP) { entry ->
        val initialTab = remember(entry) {
            navController.previousBackStackEntry?.savedStateHandle
                ?.remove<Int>(Routes.BACKUP_INITIAL_TAB_KEY) ?: 0
        }
        BackupAndRestoreScreen(
            initialTab = initialTab,
            onBack = { navController.safePopBackStack() },
            onManageDestinations = { navController.safeNavigate(Routes.BACKUP_DESTINATIONS) },
            onChooseRestoreServer = { navController.safeNavigate(Routes.RESTORE_SERVERS) },
            onOpenHistoryRecord = { id -> navController.safeNavigate("${Routes.BACKUP_HISTORY_DETAIL}/$id") },
            onBrowseDestination = { name ->
                navController.safeNavigate("${Routes.RESTORE_BROWSE}/${Uri.encode(name)}")
            },
        )
    }

    composable(
        "${Routes.BACKUP_HISTORY_DETAIL}/{recordId}",
        arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("recordId").orEmpty()
        val ctx = LocalContext.current
        val history = remember { BackupHistory.get(ctx) }
        val record = remember(id) { history.records().firstOrNull { it.id == id } }
        if (record == null) {
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else {
            val vm: BackupViewModel = viewModel()
            BackupHistoryDetailScreen(
                record = record,
                onBack = { navController.safePopBackStack() },
                onRemove = {
                    history.remove(id)
                    navController.safePopBackStack()
                },
                onRemoveWithFiles = { vm.removeHistoryRecordWithFiles(id) },
                onRemoved = { navController.safePopBackStack() },
                onOpenDestination = { name ->
                    navController.safeNavigate("${Routes.BACKUP_DESTINATION_BROWSE}/${Uri.encode(name)}")
                },
            )
        }
    }

    composable(
        "${Routes.BACKUP_DESTINATION_BROWSE}/{remoteName}",
        arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
    ) { entry ->
        val name = entry.arguments?.getString("remoteName").orEmpty()
        val ctx = LocalContext.current
        val remote = remember(name) { RcloneRemoteStore(ctx).remote(name) }
        if (remote == null) {
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else {
            val vm: BackupViewModel = viewModel()
            BackupDestinationBrowseScreen(
                remote = remote,
                vm = vm,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    composable(Routes.RESTORE_SERVERS) {
        RestoreServersScreen(
            onBack = { navController.safePopBackStack() },
            onPickServer = { name ->
                navController.safeNavigate("${Routes.RESTORE_BROWSE}/${Uri.encode(name)}")
            },
        )
    }

    composable(
        "${Routes.RESTORE_BROWSE}/{remoteName}",
        arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
    ) { entry ->
        val name = entry.arguments?.getString("remoteName").orEmpty()
        val ctx = LocalContext.current
        val remote = remember(name) { RcloneRemoteStore(ctx).remote(name) }
        if (remote == null) {
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else {
            val parent = remember(entry) { navController.getBackStackEntry(Routes.BACKUP) }
            val vm: BackupViewModel = viewModel(parent)
            RestoreBrowseScreen(
                remote = remote,
                vm = vm,
                onBack = { navController.safePopBackStack() },
                onPicked = { navController.safePopBackStack(route = Routes.BACKUP, inclusive = false) },
            )
        }
    }

    composable(Routes.BACKUP_DESTINATIONS) {
        RcloneDestinationsScreen(onBack = { navController.safePopBackStack() })
    }

    composable(Routes.SHARED_FOLDERS) {
        SharedFoldersScreen(
            onBack = { navController.safePopBackStack() },
            onFolderClick = { folderId -> navController.safeNavigate(Routes.sharedFoldersDetail(folderId)) },
        )
    }

    composable(
        route = Routes.SHARED_FOLDERS_DETAIL,
        arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
        val ctx = LocalContext.current
        SharedFolderDetailScreen(
            folderId = folderId,
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val rootfs = RootfsManager.getInstance(ctx.applicationContext)
                val hostPath = File(rootfs.rootfsDir, "var/minis/$folderId")
                val label = when (folderId) {
                    "shared" -> ctx.getString(R.string.shared_folder_name_shared)
                    "skills" -> ctx.getString(R.string.shared_folder_name_skills)
                    "memory" -> ctx.getString(R.string.shared_folder_name_memory)
                    else -> folderId
                }
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = hostPath,
                    rootLabel = label,
                    linuxRootPath = "/var/minis/$folderId",
                    appContext = ctx.applicationContext,
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
        )
    }

    composable(Routes.MOUNTED_FOLDERS) {
        MountedFoldersScreen(
            store = mountedFoldersStore,
            onBack = { navController.safePopBackStack() },
            onMountClick = { mountId -> navController.safeNavigate(Routes.mountedFoldersDetail(mountId)) },
        )
    }

    composable(
        route = Routes.MOUNTED_FOLDERS_DETAIL,
        arguments = listOf(navArgument("mountId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val mountId = backStackEntry.arguments?.getString("mountId") ?: return@composable
        val context = LocalContext.current
        MountDetailScreen(
            store = mountedFoldersStore,
            mountId = mountId,
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val entry = mountedFoldersStore.entries.value.firstOrNull { it.id == mountId }
                val hostPath = entry?.resolvedHostPath
                if (hostPath != null) {
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = File(hostPath),
                        rootLabel = entry.name,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                } else {
                    Toast.makeText(context, "Mount path unavailable", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    composable(Routes.STORAGE) {
        val storageContext = LocalContext.current
        val storageVm: StorageUsageViewModel = viewModel(
            factory = StorageUsageViewModel.factory(storageContext, chatRepository),
        )
        StorageManagementScreen(
            vm = storageVm,
            onBack = { navController.safePopBackStack() },
            onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
            onSessionClick = { sessionId -> navController.safeNavigate(Routes.sessionStorageDetail(sessionId)) },
        )
    }

    composable(
        route = Routes.SESSION_STORAGE_DETAIL,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        val storageVm = remember(backStackEntry) {
            runCatching { navController.getBackStackEntry(Routes.STORAGE) }.getOrNull()
        }?.let { parent ->
            viewModel<StorageUsageViewModel>(
                viewModelStoreOwner = parent,
                factory = StorageUsageViewModel.factory(LocalContext.current, chatRepository),
            )
        }
        SessionStorageDetailScreen(
            sessionId = sessionId,
            chatDao = chatRepository.dao,
            onBack = { navController.safePopBackStack() },
            onMeasured = { id, minis, media -> storageVm?.onSessionMeasured(id, minis, media) },
            onBrowseFiles = { rootPath ->
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = File(rootPath),
                    rootLabel = "Session Files",
                    displayLinuxPrefix = "/var/minis",
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
        )
    }

    composable(Routes.ROOTFS_MANAGEMENT) {
        val context = LocalContext.current
        RootfsManagementScreen(
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val rootfs = RootfsManager.getInstance(context.applicationContext)
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    rootLabel = "/",
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
            onMirrorCategoryClick = { category ->
                navController.safeNavigate(Routes.mirrorCategoryDetail(category.key))
            },
        )
    }

    composable(
        route = Routes.MIRROR_CATEGORY_DETAIL,
        arguments = listOf(navArgument("categoryKey") { type = NavType.StringType }),
    ) { backStackEntry ->
        val categoryKey = backStackEntry.arguments?.getString("categoryKey") ?: return@composable
        val category = MirrorCatalog.categoryFromKey(categoryKey) ?: return@composable
        MirrorCategoryDetailScreen(
            category = category,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.FILE_BROWSER) {
        val vm = FilePreviewHolder.fileBrowserViewModel ?: return@composable
        FileBrowserScreen(
            viewModel = vm,
            onBack = { navController.safePopBackStack() },
            onPreviewFile = { item ->
                FilePreviewHolder.currentItem = item
                navController.safeNavigate(Routes.FILE_PREVIEW)
            },
        )
    }

    composable(
        route = Routes.CHAT_FILES,
        arguments = listOf(
            navArgument("sessionId") { type = NavType.StringType },
            navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        val context = LocalContext.current
        val rootfs = RootfsManager.getInstance(context.applicationContext)
        val varMinis = File(rootfs.rootfsDir, "var/minis")
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        val folderPath = backStackEntry.arguments?.getString("path")?.trim('/')?.takeIf { it.isNotEmpty() }
        val vm = remember(rootfs.rootfsDir.absolutePath, varMinis.absolutePath, sessionId, folderPath) {
            FileBrowserViewModel(
                rootPath = rootfs.rootfsDir,
                initialPath = folderPath?.let { File(rootfs.rootfsDir, it) } ?: varMinis.takeIf { it.exists() },
                rootLabel = "/",
                linuxRootPath = "/",
                sessionId = sessionId,
                appContext = context.applicationContext,
            )
        }
        FileBrowserScreen(
            viewModel = vm,
            onBack = { navController.safePopBackStack() },
            onPreviewFile = { item ->
                FilePreviewHolder.currentItem = item
                navController.safeNavigate(Routes.FILE_PREVIEW)
            },
        )
    }

    // Dialog destination keeps underlying ChatScreen in composition so LazyListState survives preview.
    dialog(
        route = Routes.FILE_PREVIEW,
        dialogProperties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val item = FilePreviewHolder.currentItem ?: return@dialog
        FilePreviewScreen(
            item = item,
            onBack = { navController.safePopBackStack() },
        )
    }
}
