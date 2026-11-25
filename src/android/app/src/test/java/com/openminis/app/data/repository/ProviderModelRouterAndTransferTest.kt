package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelRouterAndTransferTest {

    private fun instance(
        id: String,
        type: ProviderType = ProviderType.openAI,
        enabled: Boolean = true,
        createdAt: Long = 100L,
        customBaseURL: String? = null,
    ) = ProviderInstance(
        id = id,
        label = id,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
        createdAt = createdAt,
        customBaseURL = customBaseURL,
    )

    private fun entry(
        instanceId: String,
        modelId: String,
        inputs: List<String>? = listOf("text"),
        outputs: List<String>? = listOf("text"),
        hidden: Boolean = false,
        absentSince: Long? = null,
        voiceRole: String? = null,
    ) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(
            id = modelId,
            displayName = modelId,
            provider = instanceId,
            inputModalities = inputs,
            outputModalities = outputs,
            voiceRole = voiceRole,
        ),
        isHidden = hidden,
        absentSince = absentSince,
    )

    @Test
    fun router_resolvesNewestTextEntryAndAvailableGroupMembers() {
        val oldProv = instance("p-old", createdAt = 100L)
        val newProv = instance("p-new", createdAt = 200L)
        val disabledProv = instance("p-off", enabled = false, createdAt = 300L)

        val eOld = entry("p-old", "m-old")
        val eNewImageOnly = entry("p-new", "m-img", outputs = listOf("image"))
        val eNewText = entry("p-new", "m-text")
        val eAbsent = entry("p-new", "m-absent", absentSince = 1234L)
        val eHidden = entry("p-new", "m-hidden", hidden = true)
        val eDisabled = entry("p-off", "m-disabled")

        val group = ModelGroup(
            id = "g1",
            name = "Primary",
            memberEntryIds = mutableListOf(eDisabled.id, eAbsent.id, eHidden.id, eNewText.id, eOld.id),
        )
        val cfg = ProviderConfig(
            instances = mutableListOf(oldProv, newProv, disabledProv),
            modelEntries = mutableListOf(eOld, eAbsent, eNewText, eNewImageOnly, eHidden, eDisabled),
            modelGroups = mutableListOf(group),
            defaultSubGroupId = "g1",
        )

        val router = ProviderModelRouter(
            configProvider = { cfg },
            ensureConfigLoaded = {},
            visibleEntriesProvider = { cfg.modelEntries.filterNot { it.isHidden } },
            lastUsedEntryIdProvider = { eOld.id },
            voiceInputOverrideEntryIdProvider = { null },
            voiceOutputOverrideEntryIdProvider = { null },
            hasAnyCredential = { it.id == "p-new" },
        )

        assertEquals(eOld.id, router.lastUsedVisibleEntry()?.id)
        assertEquals(eNewText.id, router.newestProviderNewestTextEntry()?.id)
        // enabledMemberEntries filters disabled providers and provider-absent entries.
        assertEquals(
            listOf(eHidden.id, eNewText.id, eOld.id),
            router.enabledMemberEntries(group).map { it.id },
        )
        // availableMemberEntries additionally requires non-hidden & credential present.
        assertEquals(listOf(eNewText.id), router.availableMemberEntries(group).map { it.id })
        assertEquals(eNewText.id, router.resolveTitleSubEntry()?.id)
        assertFalse(router.isEntryProviderEnabled(eDisabled.id))
        assertTrue(router.isEntryProviderEnabled(eNewText.id))
    }

    @Test
    fun router_dedupesAgentLoopEntriesAndRotatesLoadBalancedVisionCandidates() {
        val prov = instance("p1")
        val v1 = entry("p1", "vision-1", inputs = listOf("text", "image"))
        val v2 = entry("p1", "vision-2", inputs = listOf("text", "image"))
        val textOnly = entry("p1", "text-only", inputs = listOf("text"))

        val visionGroup = ModelGroup(
            id = "gv",
            name = "Vision",
            memberEntryIds = mutableListOf(textOnly.id, v1.id, v2.id, v1.id),
            strategy = RoutingStrategy.loadBalance,
        )
        val cfg = ProviderConfig(
            instances = mutableListOf(prov),
            modelEntries = mutableListOf(v1, v2, textOnly),
            modelGroups = mutableListOf(visionGroup),
            visionGroupId = "gv",
            agentLoopModelEntryIds = mutableListOf(v1.id),
            agentLoopGroupIds = mutableListOf("gv"),
        )
        val router = ProviderModelRouter(
            configProvider = { cfg },
            ensureConfigLoaded = {},
            visibleEntriesProvider = { cfg.modelEntries },
            lastUsedEntryIdProvider = { null },
            voiceInputOverrideEntryIdProvider = { null },
            voiceOutputOverrideEntryIdProvider = { null },
            hasAnyCredential = { true },
        )

        assertEquals(listOf(v1.id, textOnly.id, v2.id), router.resolvedAgentLoopEntries().map { it.id })
        assertEquals(listOf(v1.id, v2.id), router.resolveVisionCandidates(loadBalanceSeed = 0).map { it.second.id })
        assertEquals(listOf(v2.id, v1.id), router.resolveVisionCandidates(loadBalanceSeed = 1).map { it.second.id })
    }

    @Test
    fun router_resolvesVoiceOverridesAndSystemFallbacks() {
        val prov = instance("p1")
        val stt = entry("p1", "whisper-1", inputs = listOf("audio"), outputs = listOf("text"), voiceRole = "stt")
        val tts = entry("p1", "tts-1", inputs = listOf("text"), outputs = listOf("audio"), voiceRole = "tts")
        val inGroup = ModelGroup(
            id = "gin",
            name = "VoiceIn",
            memberEntryIds = mutableListOf(stt.id),
        )
        val outGroup = ModelGroup(
            id = "gout",
            name = "VoiceOut",
            memberEntryIds = mutableListOf(tts.id),
        )
        val cfg = ProviderConfig(
            instances = mutableListOf(prov),
            modelEntries = mutableListOf(stt, tts),
            modelGroups = mutableListOf(inGroup, outGroup),
            voiceInputGroupId = "gin",
            voiceOutputGroupId = "gout",
        )

        var inOverride: String? = null
        var outOverride: String? = null
        val router = ProviderModelRouter(
            configProvider = { cfg },
            ensureConfigLoaded = {},
            visibleEntriesProvider = { cfg.modelEntries },
            lastUsedEntryIdProvider = { null },
            voiceInputOverrideEntryIdProvider = { inOverride },
            voiceOutputOverrideEntryIdProvider = { outOverride },
            hasAnyCredential = { true },
        )

        assertEquals(stt.id, router.resolveVoiceInputChoice().entry?.second?.id)
        assertEquals(tts.id, router.resolveVoiceOutputEntry()?.second?.id)
        assertEquals(stt.id, router.activeVoiceGroupMemberId(output = false))
        assertEquals(tts.id, router.activeVoiceGroupMemberId(output = true))

        // Builtin system offline ASR override short-circuits provider candidates.
        inOverride = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"
        val offlineChoice = router.resolveVoiceInputChoice()
        assertEquals(true, offlineChoice.systemPreferOffline)
        assertNull(offlineChoice.entry)
        assertTrue(router.resolveVoiceInputCandidates().isEmpty())

        // Builtin system TTS override falls back to system engine.
        outOverride = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/system-tts"
        val ttsChoice = router.resolveVoiceOutputChoice()
        assertTrue(ttsChoice.isSystemEngine)
        assertNull(ttsChoice.entry)
    }

    @Test
    fun instanceStore_distinguishesOfficialAndThirdPartyCompatEndpoints() {
        assertFalse(ProviderInstanceStore.isThirdPartyOpenAICompat(instance("1", ProviderType.openAI, customBaseURL = null)))
        assertFalse(ProviderInstanceStore.isThirdPartyOpenAICompat(instance("2", ProviderType.openAI, customBaseURL = "https://api.openai.com/v1")))
        assertTrue(ProviderInstanceStore.isThirdPartyOpenAICompat(instance("3", ProviderType.openAI, customBaseURL = "https://relay.example.com/v1")))

        assertFalse(ProviderInstanceStore.isThirdPartyGeminiCompat(instance("4", ProviderType.gemini, customBaseURL = "https://generativelanguage.googleapis.com")))
        assertTrue(ProviderInstanceStore.isThirdPartyGeminiCompat(instance("5", ProviderType.gemini, customBaseURL = "https://gemini-proxy.example.com")))
    }

    @Test
    fun transferService_roundTripsModalityBitfieldsAndThinkingWireTokens() {
        val bits = ProviderConfigTransferService.modalityBitfieldFromLists(
            inputs = listOf("text", "image", "pdf", "audio"),
            outputs = listOf("text", "audio"),
        )
        val (inList, outList) = ProviderConfigTransferService.modalityListsFromBitfield(bits)
        assertEquals(listOf("text", "image", "pdf", "audio"), inList)
        assertEquals(listOf("text", "audio"), outList)
        assertEquals(null to null, ProviderConfigTransferService.modalityListsFromBitfield(0))

        // Native arrays take precedence over bitfield fallback.
        val nativeObj = JSONObject().apply {
            put("inputModalities", JSONArray(listOf("text", "video")))
            put("modalityOverride", bits)
        }
        val (natIn, natOut) = ProviderConfigTransferService.readModalitiesWithBitfieldFallback(nativeObj)
        assertEquals(listOf("text", "video"), natIn)
        assertNull(natOut)

        // Bitfield fallback decodes iOS modalityOverride cleanly.
        val fallbackObj = JSONObject().put("modalityOverride", bits)
        assertEquals(inList to outList, ProviderConfigTransferService.readModalitiesWithBitfieldFallback(fallbackObj))

        // Case-insensitive wire decode; unknown tokens return null (do NOT clamp).
        assertEquals(ThinkingLevel.XHIGH, ProviderConfigTransferService.thinkingLevelFromWire("xhigh"))
        assertEquals(ThinkingLevel.HIGH, ProviderConfigTransferService.thinkingLevelFromWire("HIGH"))
        assertNull(ProviderConfigTransferService.thinkingLevelFromWire(""))
        assertNull(ProviderConfigTransferService.thinkingLevelFromWire("future_tier_9"))
    }
}
