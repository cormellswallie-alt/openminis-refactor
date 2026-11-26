package com.openminis.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen
import com.openminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.openminis.app.ui.settings.AddAgentLoopModelsScreen
import com.openminis.app.ui.settings.AddCustomModelScreen
import com.openminis.app.ui.settings.AddModelsToGroupScreen
import com.openminis.app.ui.settings.AddProviderScreen
import com.openminis.app.ui.settings.ModelEntryDetailScreen
import com.openminis.app.ui.settings.ModelGroupDetailScreen
import com.openminis.app.ui.settings.ModelGroupsScreen
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.ProviderListScreen
import com.openminis.app.ui.settings.ShadowVoiceDetailScreen

internal fun NavGraphBuilder.addProviderRoutes(
    navController: NavHostController,
    providerRepository: ProviderRepository,
) {
    composable(Routes.PROVIDER_LIST) {
        ProviderListScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
            onProviderClick = { instanceId -> navController.safeNavigate(Routes.providerDetail(instanceId)) },
            onVoiceServiceClick = { instanceId -> navController.safeNavigate(Routes.shadowVoiceDetail(instanceId)) },
        )
    }

    composable(
        route = Routes.SHADOW_VOICE_DETAIL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        ShadowVoiceDetailScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ADD_PROVIDER) {
        AddProviderScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onSaved = { navController.safePopBackStack() },
        )
    }

    composable(
        route = Routes.PROVIDER_DETAIL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        ProviderDetailScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onModelEntryClick = { entryId ->
                navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
            },
            onAddCustomModel = { navController.safeNavigate(Routes.addCustomModel(instanceId)) },
            onVoiceServiceClick = { id -> navController.safeNavigate(Routes.shadowVoiceDetail(id)) },
        )
    }

    composable(Routes.MODEL_GROUPS) {
        ModelGroupsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onGroupClick = { groupId -> navController.safeNavigate(Routes.modelGroupDetail(groupId)) },
            onAddAgentLoopModels = { navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP) },
            onAddAgentLoopGroups = { navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP) },
        )
    }

    composable(
        route = Routes.MODEL_GROUP_DETAIL,
        arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
        ModelGroupDetailScreen(
            groupId = groupId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onAddModels = { navController.safeNavigate(Routes.addModelsToGroup(groupId)) },
        )
    }

    composable(
        route = Routes.ADD_MODELS_TO_GROUP,
        arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
        AddModelsToGroupScreen(
            groupId = groupId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
        AddAgentLoopModelsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
        AddAgentLoopGroupsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.AGENT_LOOP_MODELS) {
        LaunchedEffect(Unit) { navController.safePopBackStack() }
    }

    composable(
        route = Routes.MODEL_ENTRY_DETAIL,
        arguments = listOf(
            navArgument("instanceId") { type = NavType.StringType },
            navArgument("entryId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId")?.let { Uri.decode(it) } ?: return@composable
        val entryId = backStackEntry.arguments?.getString("entryId")?.let { Uri.decode(it) } ?: return@composable
        ModelEntryDetailScreen(
            instanceId = instanceId,
            entryId = entryId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(
        route = Routes.ADD_CUSTOM_MODEL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        AddCustomModelScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ONBOARDING_MODELS) {
        OnboardingModelSelectionScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onAddCustomModel = { instanceId -> navController.safeNavigate(Routes.addCustomModel(instanceId)) },
        )
    }
}
