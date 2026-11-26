package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftAndModelStoresTest {

    private class FakeProvider(
        override val name: String,
        override var model: LLMModel,
    ) : LLMProvider {
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("Not used in unit test")

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = emptyFlow()
    }

    @Test
    fun draftStore_joinsSnippetsPreservesMonotonicPasteIdsAndClearsOnReset() {
        val store = ChatDraftStateStore()

        store.appendToInputText("   ")
        assertEquals("", store.inputText.value)

        store.appendToInputText("hello")
        assertEquals("hello ", store.inputText.value)

        store.appendToInputText("```kotlin\nval x = 1\n```")
        assertEquals("hello \n```kotlin\nval x = 1\n```\n", store.inputText.value)

        store.setPendingCaret(5)
        assertEquals(5, store.consumePendingCaret())
        assertNull(store.consumePendingCaret())

        store.setEditingMessageId("msg-1")
        assertEquals("msg-1", store.takeEditingMessageId())
        assertNull(store.takeEditingMessageId())

        val p1 = store.stashPastedText("block 1")
        val p2 = store.stashPastedText("block 2")
        assertEquals(1, p1.id)
        assertEquals(2, p2.id)
        store.removePastedText(p1.id)
        val p3 = store.stashPastedText("block 3")
        assertEquals(3, p3.id) // never reuses deleted id 1

        store.consumePastedIds(setOf(p2.id))
        assertEquals(listOf(3), store.pastedTexts.value.map { it.id })

        store.markShareInjected()
        assertTrue(store.hasInjectedShareContent.value)
        store.clearForChatReset()
        assertFalse(store.hasInjectedShareContent.value)
        assertTrue(store.pastedTexts.value.isEmpty())
    }

    @Test
    fun modelStore_resolvesContextWindowWithUserCapAndBuildsAttributionSnapshot() {
        val store = ChatModelStateStore()
        val inst = ProviderInstance(
            id = "inst-1",
            label = "Anthropic Direct",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
        )
        val model = LLMModel(
            id = "claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6",
            provider = "Anthropic",
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            supportsReasoning = true,
        )
        val entry = ModelEntry(providerInstanceId = inst.id, baseModel = model)
        val cappedGroup = ModelGroup(
            id = "grp-capped",
            name = "Capped",
            memberEntryIds = mutableListOf(entry.id),
            contextLimitTokens = 64_000,
        )
        val config = ProviderConfig(
            instances = mutableListOf(inst),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(cappedGroup),
        )

        val provider = FakeProvider("Anthropic", model)
        store.bindStandaloneEntry(entry, inst, provider)
        assertEquals(200_000 to false, store.resolvedContextWindow(config))
        assertEquals(ContextPolicy.forContextWindow(200_000), store.currentContextPolicy(config)?.first)

        val snap = store.currentModelSnapshot(config)
        assertNotNull(snap)
        assertEquals("claude-sonnet-4-6", snap!!.modelId)
        assertEquals("anthropic", snap.providerTypeRaw)
        assertEquals(inst.id, snap.providerInstanceId)

        // Binding to a group with contextLimitTokens < model window activates user cap.
        store.setSelectedGroup(cappedGroup.id, cappedGroup.name)
        assertEquals(64_000 to true, store.resolvedContextWindow(config))
        assertEquals(ContextPolicy.forUserCap(64_000), store.currentContextPolicy(config)?.first)

        // Group cap >= model window leaves window uncapped.
        cappedGroup.contextLimitTokens = 300_000
        assertEquals(200_000 to false, store.resolvedContextWindow(config))
    }

    @Test
    fun modelStore_selectsGroupMembersWithFallbackAvoidanceAndAdoptsFallback() {
        val store = ChatModelStateStore()
        val inst1 = ProviderInstance("i1", "Primary", ProviderType.openAI, ProviderCredential.apiKey)
        val inst2 = ProviderInstance("i2", "Backup", ProviderType.anthropic, ProviderCredential.apiKey)
        val instDisabled = ProviderInstance("i3", "Off", ProviderType.gemini, ProviderCredential.apiKey, isEnabled = false)

        val m1 = LLMModel("gpt-5", "GPT-5", "OpenAI", contextWindow = 128_000)
        val m2 = LLMModel("claude-sonnet", "Claude Sonnet", "Anthropic", contextWindow = 200_000)
        val mHidden = LLMModel("hidden-m", "Hidden Model", "OpenAI")
        val mDisabled = LLMModel("disabled-m", "Disabled Model", "Gemini")

        val e1 = ModelEntry("i1", m1)
        val e2 = ModelEntry("i2", m2)
        val eHidden = ModelEntry("i1", mHidden, isHidden = true)
        val eDisabled = ModelEntry("i3", mDisabled)

        val group = ModelGroup(
            id = "g1",
            name = "Smart",
            memberEntryIds = mutableListOf(e1.id, e2.id, eHidden.id, eDisabled.id),
            strategy = RoutingStrategy.fallback,
        )
        val config = ProviderConfig(
            instances = mutableListOf(inst1, inst2, instDisabled),
            modelEntries = mutableListOf(e1, e2, eHidden, eDisabled),
            modelGroups = mutableListOf(group),
        )

        // Preferred entry wins when available.
        assertEquals(e2.id, store.selectGroupMemberEntry(group, listOf(e1, e2), e2.id, "s1")?.id)

        // Fallback skips recently failed entry.
        store.noteEntryFailed(e1.id)
        assertEquals(e2.id, store.selectGroupMemberEntry(group, listOf(e1, e2), null, "s1")?.id)

        // If all failed, falls back to first available.
        store.noteEntryFailed(e2.id)
        assertEquals(e1.id, store.selectGroupMemberEntry(group, listOf(e1, e2), null, "s1")?.id)

        // LoadBalance selection is deterministic per session key.
        group.strategy = RoutingStrategy.loadBalance
        val firstPick = store.selectGroupMemberEntry(group, listOf(e1, e2), null, "session-xyz")
        val secondPick = store.selectGroupMemberEntry(group, listOf(e1, e2), null, "session-xyz")
        assertEquals(firstPick?.id, secondPick?.id)

        // Unavailable member reporting classifies hidden, disabled, and missing credentials.
        store.setSelectedGroup(group.id, group.name)
        val reasons = store.unavailableGroupMembers(config) { it.id != "i2" }
        assertTrue(reasons.any { it.contains("Claude Sonnet") && it.contains("Not logged in") })
        assertTrue(reasons.any { it.contains("Hidden Model") && it.contains("Hidden") })
        assertTrue(reasons.any { it.contains("Disabled Model") && it.contains("Disabled") })

        // Adopting a fallback marks previous active failed, clears new from failed, and bumps trigger.
        store.bindGroupMember(group, e1, inst1, FakeProvider("OpenAI", m1))
        store.recentlyFailedEntryIds.clear()
        store.noteEntryFailed(e2.id)
        val adopted = store.applyFallbackAdoption(
            candidate = FallbackCandidate(FakeProvider("Anthropic", m2), e2.id),
            config = config,
            markPreviousFailed = true,
            bumpFallbackTrigger = true,
        )
        assertEquals(e2.id, adopted?.id)
        assertEquals(e2.id, store.activeEntryId.value)
        assertEquals("Claude Sonnet", store.modelName.value)
        assertEquals("Backup", store.providerName.value)
        assertTrue(e1.id in store.recentlyFailedEntryIds)
        assertFalse(e2.id in store.recentlyFailedEntryIds)
        assertEquals(1, store.fallbackTrigger.value)
    }
}
