package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.data.model.ModelOverrides
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton

/**
 * Add Custom Model — adopts the SettingsScaffold/SettingsSection toolkit
 * (T78). Single Identity section grouping the Model ID + Display Name
 * fields, with a footer caption explaining what the fields are for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomModelScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()
    val instance = config.instances.find { it.id == instanceId }

    if (instance == null) {
        LaunchedEffect(instanceId) { onBack() }
        return
    }

    var modelId by remember(instanceId) { mutableStateOf("") }
    var displayName by remember(instanceId) { mutableStateOf("") }
    var supportsThinking by remember(instanceId) { mutableStateOf(false) }
    var supportsVision by remember(instanceId) { mutableStateOf(false) }

    val trimmedId = modelId.trim()
    val isDuplicateId = trimmedId.isNotEmpty() && config.modelEntries.any {
        it.providerInstanceId == instanceId && it.baseModel.id.equals(trimmedId, ignoreCase = true)
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_detail_add_custom_model),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_custom_model_id_footer, instance.providerType.displayName),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    placeholder = stringResource(R.string.add_custom_model_eg_id_placeholder),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                if (isDuplicateId) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.add_custom_model_duplicate_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.add_custom_model_display_name_optional))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = supportsThinking,
                onCheckedChange = { supportsThinking = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = supportsVision,
                onCheckedChange = { supportsVision = it },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                val name = displayName.trim().ifBlank { trimmedId }
                val model = LLMModel(
                    id = trimmedId,
                    displayName = name,
                    provider = instance.providerType.displayName,
                    supportsReasoning = if (supportsThinking) true else null,
                    inputModalities = if (supportsVision) listOf("image") else null,
                )
                val overrides = ModelOverrides(
                    supportsReasoning = if (supportsThinking) true else null,
                    inputModalities = if (supportsVision) listOf("image") else null,
                )
                val entry = ModelEntry(
                    providerInstanceId = instanceId,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = true,
                )
                providerRepository.addEntry(entry)
                onBack()
            },
            enabled = trimmedId.isNotEmpty() && !isDuplicateId,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.common_save))
        }
        Spacer(Modifier.height(20.dp))
    }
}
