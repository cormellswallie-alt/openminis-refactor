package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    private val apiKey: String?,
    private val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    internal val basePath: String = "https://api.openai.com/v1",
    internal val extraHeaders: Map<String, String> = emptyMap(),
    /** Codex account ID for OAuth mode (extracted from JWT). */
    var codexAccountId: String? = null,
    /** When true, route through /v1/responses even on API-key providers. */
    private val useResponsesAPI: Boolean = false,
    /**
     * When true, force Chat Completions even when the bearer is provided
     * via OAuth. Set by OAuth-but-not-Codex callers (xAI Grok) — without
     * it the default `usesChatCompletionsAPI = !isOAuth && !useResponsesAPI`
     * heuristic incorrectly drags an OAuth bearer onto the Codex
     * Responses backend at chatgpt.com, where it 404s.
     */
    private val forceChatCompletions: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    internal val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    internal val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    private val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"

    private val dialect = OpenAIVendorDialect(
        basePath, extraHeaders, useResponsesAPI, forceChatCompletions,
        customUserAgent, isAzure, azureBase, oauthTokenProvider != null,
    )

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how [codexAccountId] is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from [com.openminis.app.provider.thinking.ThinkingRuleResolver]'s
     * cache. Null → no custom rules (identical to Phase-1 built-in-only behaviour).
     */
    var thinkingRuleInstanceId: String? = null

    /**
     * [T-android-model-custom-params] Per-model user overrides for this
     * request: temperature / top_p / custom headers / extra body params.
     *
     * A post-construction `var` set by ProviderFactory, matching how
     * [thinkingRuleInstanceId] and [sessionId] are wired — the private primary
     * constructor is shared by a dozen call sites and several secondary
     * constructors, so threading a new parameter through all of them would be a
     * far larger change for no gain.
     *
     * Deliberately SEPARATE from [chatExtraBody] / [chatExtraHeaders], rather
     * than reusing them: those are per-CALL passthrough owned by
     * ModelUseOffloadHandler, which assigns them on a freshly built provider.
     * Folding per-model settings into the same fields would mean one silently
     * clobbering the other depending on call order. Keeping them apart also
     * fixes the precedence deliberately — see [mergeModelOverridesBody].
     *
     * null = no overrides (the default for every caller that does not set it).
     */
    var modelOverrides: com.openminis.app.data.model.ModelOverrides? = null

    /**
     * [T-android-xai-priority] Whether this provider speaks xAI's Priority
     * Processing extension, i.e. whether it is eligible to carry
     * `service_tier: "priority"` when the user's global Fast Mode is on.
     *
     * This is a CAPABILITY flag, not the user's choice. The choice lives in
     * the app-level [com.openminis.app.data.FastModePrefs] toggle that Codex
     * Fast Mode already uses, and is read at request-BUILD time (see
     * [buildRequestBody]) so flipping it applies to the very next request of
     * an ongoing session — including offload / title-gen calls that never pass
     * through ChatViewModel. Storing the user's answer here instead would
     * freeze it at provider-construction time and miss those.
     *
     * False by default so every other provider's body is byte-for-byte
     * unchanged. That matters beyond tidiness: `service_tier` is an xAI
     * extension, and OpenAI-compatible relays that reject unknown body keys
     * would 400 on it, so ProviderFactory sets this for ProviderType.xAI alone.
     * A post-construction var rather than a constructor parameter for the same
     * reason as [thinkingRuleInstanceId] — xAI resolves through two different
     * constructors (API key and OAuth), and threading a flag through both
     * duplicates it.
     */
    var supportsPriorityProcessing: Boolean = false

    /**
     * [T-android-xai-priority] The effective `service_tier` for this request,
     * or null to omit the field. Consulted by both body builders.
     *
     * Omits rather than sending `"default"` when Fast Mode is off: "default" is
     * xAI's own behaviour, so leaving the key out keeps the body byte-identical
     * to before this feature existed.
     */
    internal fun resolvedServiceTier(): String? = dialect.resolvedServiceTier(supportsPriorityProcessing)

    /** API Key constructor (Chat Completions API by default; set useResponsesAPI=true for /v1/responses). */
    constructor(
        apiKey: String,
        model: LLMModel = LLMModel.gpt4oMini,
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    /** OAuth constructor (Codex Responses API). */
    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = LLMModel.codexMini,
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        /**
         * [T-codex-gpt-image25-android] The 2.5 image variants, which name
         * themselves inside the image_generation tool object.
         *
         * Separate from [CODEX_IMAGE_MODEL_IDS] because the two questions are
         * different: that one asks "does this route through the Codex image
         * path", this one asks "does the tool object need an explicit model".
         * gpt-image-2 answers yes to the first and no to the second.
         */
        internal val CODEX_IMAGE_25_MODEL_IDS = setOf(
            "gpt-image-2.5-sunburst",
            "gpt-image-2.5-flare",
        )

        /**
         * [T-codex-gpt-image2-oauth-android] Every model that goes through the
         * Codex OAuth image_generation path rather than the ordinary
         * Chat Completions / Responses APIs. Must stay in step with the entries
         * OpenAIModelsApi appends after enrichModels.
         */
        private val CODEX_IMAGE_MODEL_IDS = setOf("gpt-image-2") + CODEX_IMAGE_25_MODEL_IDS

        /**
         * [T-android-thinking-level-arch] Codex OAuth client version advertised
         * in the Version / User-Agent headers. Bumped 0.142.3 → 0.144.1 to
         * match the CLIProxyAPI/sub2api upstream (fixes a gpt-5.6-luna 404 seen
         * on the older client). Shared constant so future bumps touch one place.
         *
         * [T-gpt6-astra] 0.144.1 → 0.153.3. OpenAI gates models on the
         * advertised client version: gpt-6-astra's `minimal_client_version`
         * is 0.153.0, and an older client gets a 400 or an empty reply for it.
         * Tracks CLIProxyAPI c77b1369, which moved its default to 0.153.3.
         * Only the number changes; the UA shape below is unchanged.
         */
        /**
         * [T-codex-dynamic-discovery GH#319] `internal`, not `private`: model
         * DISCOVERY must advertise the same client version as inference.
         * OpenAI gates model availability on this number, so if the two ever
         * drifted the picker could list a model that every send then refuses.
         * Sharing the one constant makes that drift unrepresentable.
         *
         * GH#319 asked us to re-check whether a newer version is required, and
         * the check was run rather than assumed: on a live ChatGPT account at
         * 0.153.3, discovery returned gpt-6-astra / gpt-5.6-{sol,terra,luna} /
         * gpt-5.5, and gpt-6-astra and gpt-5.6-luna both completed a real
         * inference request (2026-09-11). At that point Codex CLI stable was
         * 0.154.0 and bumping would have changed a validated fingerprint with
         * no evidence behind it, so it was left alone — with the note that the
         * signal to revisit is "a discovered model is listed and then refused".
         *
         * [T-gpt6-sol-luna] 0.153.3 → 0.155.0. That signal arrived: gpt-6-sol
         * and gpt-6-luna are gated above 0.153.3, and CLIProxyAPI moved its
         * default codex client to 0.155.0 in the same commit that added these
         * two ids to its registry (2430354330af). The gate is a floor, so the
         * models that were verified callable at 0.153.3 stay callable.
         *
         * NOT verified against a live account here — this session has no Codex
         * OAuth token to probe with. If gpt-6-astra or a gpt-5.6-* model starts
         * failing after this bump, this constant is the first thing to suspect.
         */
        internal const val CODEX_CLIENT_VERSION = "0.155.0"

        /**
         * Factory for OAuth-bearer OpenAI-compatible providers that aren't
         * Codex (e.g. xAI Grok). Same dynamic bearer plumbing, but the
         * wire format stays Chat Completions and the endpoint is the
         * caller-supplied base URL — not chatgpt.com's Responses API.
         *
         * Implemented as a factory (not a secondary ctor) because the
         * JVM erases the signature down to
         * `(Function1, LLMModel, String)` which collides with the Codex
         * ctor's `(oauthTokenProvider, model, codexAccountId)` overload.
         */
        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
            /**
             * [T-copilot-provider] Static headers added to every request.
             * Copilot needs a fixed editor-identity set (Editor-Version,
             * Copilot-Integration-Id, …) alongside the bearer; without them
             * the API refuses the request. Defaulted empty so existing
             * callers (xAI, Kimi) are unchanged.
             */
            extraHeaders: Map<String, String> = emptyMap(),
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
            extraHeaders = extraHeaders,
        )
    }

    private val isOAuth: Boolean get() = oauthTokenProvider != null

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    // MARK: - Per-request headers [T-android-copilot-per-request-headers]

    /**
     * Headers DERIVED from the outgoing body, applied to every chat/responses
     * request. Null (the default) for every provider that does not need them.
     *
     * Exists because some headers are only correct per request and are wrong
     * the moment they are pinned at construction. GitHub Copilot is the case
     * that forced it: `X-Initiator` must say `agent` when the loop is feeding
     * tool results back and `user` when a person typed, and
     * `Copilot-Vision-Request` must be set only when images are actually
     * attached. Both were fixed at build time to `user` / absent, so every
     * agent turn was labelled as human traffic — which is the specific thing
     * that gets a Copilot account flagged — and `X-Request-Id` repeated one
     * UUID for the whole life of the provider instead of identifying a request.
     *
     * A lambda rather than a subclass hook, so the shared request builder stays
     * free of provider-specific branching; the same reason iOS keeps it a
     * closure. Applied in [buildRequest], which is the single choke point every
     * chat and responses call already passes through.
     */
    var perRequestHeaders: ((org.json.JSONObject) -> Map<String, String>)? = null

    /**
     * [T-android-opencode-session-header] Id of the conversation this provider
     * is serving, used only to derive OpenCode Go's `x-opencode-session`
     * header (see [com.openminis.app.provider.OpenCodeSessionHeader]).
     *
     * A `var` read at request time rather than a constructor value, because a
     * provider outlives the event this id changes on: a brand-new chat builds
     * its provider while still a draft (`ChatViewModel.loadSession`), and the
     * real UUID only exists once `ensureSession()` persists the row on the
     * first send. Capturing at construction would leave that first turn — the
     * one that opens the upstream cache entry — without the header, and the
     * turn after it with one, which is precisely the instability OpenCode
     * asks clients to avoid. Reading late means the promotion is picked up
     * with no provider rebuild.
     *
     * Written from the chat ViewModel's main-thread flow and read on the
     * request-building coroutine; `@Volatile` publishes that hand-off. Only
     * ever a whole-reference assignment, so no further synchronisation is
     * needed.
     */
    @Volatile
    var sessionId: String? = null

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null

    /**
     * [T-android-model-use-passthrough-mode] Build a URL from the provider's
     * scheme+host(+port) ONLY, with [path] replacing the entire URL path.
     * [path] must start with "/" and may carry a query string. Credentials stay
     * bound to the instance's host — callers can never point this at a different
     * host. Returns null if the base URL can't be parsed. Mirrors iOS
     * OpenAIProvider.hostRootURL.
     */
    fun hostRootURL(path: String): String? = dialect.hostRootURL(path)

    /**
     * Resolve the effective URL for a modeled endpoint, honoring the
     * absolute-path override when present. [defaultPath] is joined after
     * [basePath] (which is already normalized to base + /v1). Mirrors iOS
     * OpenAIProvider.endpointURL.
     */
    private fun endpointURL(defaultPath: String): String = dialect.endpointURL(absoluteEndpointOverride, defaultPath)

    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    internal fun Request.Builder.applyKeyAuth(token: String): Request.Builder = dialect.applyKeyAuth(this, token)

    /**
     * Build the request URL for Azure OpenAI, mirroring the official AzureOpenAI
     * SDK shape (and iOS azureURL, T-ios-azure-openai-deployments):
     *
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     *
     * The user pastes the resource endpoint as the custom base — typically the
     * bare `https://x.openai.azure.com`, optionally already including `/openai`,
     * with the `?api-version=…` query on it. We (1) split off the query, (2)
     * strip a trailing `/`, a stray `/v1` (Azure has no /v1), and a trailing
     * `/openai` (re-added), then (3) assemble the deployments path. [path] is
     * e.g. "/chat/completions". Returns null when no Azure base is configured.
     */
    internal fun azureUrl(path: String): String? = dialect.azureUrl(model.id, path)

    private val usesChatCompletionsAPI: Boolean get() = dialect.usesChatCompletionsAPI

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     *
     * [T-codex-gpt-image25-android] The 2.5 variants route identically — the
     * only difference is that they name themselves inside the tool object
     * (see [buildCodexImageBody]). Membership lives in
     * [CODEX_IMAGE_MODEL_IDS] so the routing gate and the body builder cannot
     * disagree about which ids are image models: a model listed in the picker
     * but missing from this gate would silently take the ordinary Responses
     * path and fail as an unknown model.
     */
    private val isCodexImageModel: Boolean get() = isOAuth && model.id in CODEX_IMAGE_MODEL_IDS

    internal suspend fun getToken(): String {
        oauthTokenProvider?.let { return it() }
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    internal val client = OpenAINetworkTransport.createHttpClient()

    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (isCodexImageModel) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance is driven through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public /v1/images/generations Images API — a Codex OAuth
            // token lacks the api.model.images.request scope and the Images API
            // 401s with "Missing scopes: api.model.images.request" (see
            // codex_oauth_image_generation_summary.md §11.1). Aligns with iOS
            // commit 2dd35a14. The Codex-backend route is selected purely by
            // `isCodexImageModel` (isOAuth + model id) — it does NOT require a
            // codexAccountId (the Chatgpt-Account-Id header is optional and its
            // absence doesn't 401), which is exactly the iOS fall-through bug
            // this avoids. Android has no Images-API path at all, so an OAuth
            // gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        val request = buildRequest(bodyStr)
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        val (call, response) = OpenAINetworkTransport.executeStreamingCall(client, request, this)
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody)
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        val parser: OpenAISseStreamSession = if (usesChatCompletionsAPI) {
            ChatCompletionsStreamSession(model.id, dialect)
        } else {
            ResponsesStreamSession(model.id)
        }
        try {
            send(LLMStreamChunk.Started)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    parser.onDoneSentinel { send(it) }
                    break
                }
                val event = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message}",
                    )
                    continue
                }
                if (parser.processEvent(event) { send(it) }) break
            }
            parser.onStreamEnd { send(it) }
        } catch (e: Exception) {
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=${parser.sseEventCount} contentLen=${parser.contentLen} reasoningLen=${parser.reasoningLen})"
            )
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }

    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    /**
     * Execute a verbatim request against this provider instance's base URL with
     * the instance's credentials. The response is returned UNPARSED — passthrough
     * mode's output contract is raw bytes; the caller (agent or follow-up script)
     * owns interpretation. Mirrors iOS OpenAIProvider.rawPassthroughRequest.
     *
     * - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
     *   relative segment (joined after basePath like modeled endpoints). null →
     *   the default chat/completions path.
     * - headers: applied LAST → same-name REPLACE semantics over every default
     *   (including Authorization/Content-Type), per design.
     */
    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        bodyObject: JSONObject?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        if (verb != "GET" && bodyObject != null) {
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyObject.toString().toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            builder.method(verb, requestBody)
        } else {
            builder.method(verb, null)
        }
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        // ctor extraHeaders, then user headers LAST — replace semantics.
        for ((k, v) in extraHeaders) builder.header(k, v)
        for ((k, v) in headers) builder.header(k, v)

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[${bodyObject?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    /**
     * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
     * API (`POST $basePath/images/generations`). Mirrors iOS
     * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
     * image-output routing for API-key OpenAI-compat instances — the Codex
     * OAuth gpt-image-2 path goes through the existing isCodexImageModel branch
     * and never reaches here.
     *
     * Request body: `{ model, prompt, n, size?, quality?, response_format:
     * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
     * 400 mentioning it, we retry once without the field (iOS parity).
     *
     * On a non-2xx response throws [mapHttpError]'s result. A route-missing
     * error (404 / "got chat completions response") surfaces as
     * LLMError.ProviderError whose message the handler matches with
     * looksLikeEndpointMissing() to drive the auto-mode fallback.
     */
    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = generateImageImpl(prompt, n, size, quality)

    /**
     * [T-android-image-edit-endpoint] Call `/images/edits` for image-to-image
     * (reference-image) generation. Android previously had no such endpoint, so
     * minis-model-use returned `image_edit_not_supported` for every
     * input-image + pure-image-generator call — the gap this closes. Mirrors
     * iOS `OpenAIProvider.editImage`.
     *
     * Request: multipart/form-data with `image` (file), `prompt`, `model`, `n`,
     * plus optional `size` / `quality`.
     * Response: identical shape to `/images/generations`
     * (`{ data: [{ b64_json?, url? }] }`), so [parseImageGenerationsResult] is
     * reused verbatim.
     *
     * Multi-image: the first attachment goes in as `image`, any extras as
     * `image[]` — same field naming as iOS. Providers that only accept a single
     * reference image reject the extras themselves; nothing is silently dropped
     * on our side.
     */
    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = editImageImpl(prompt, images, n, size, quality)

    // `internal` rather than private so the serialization can be asserted
    // directly in unit tests. The tool-result-image regression this guards
    // (T-android-toolresult-image-dropped) is a property of the request BODY,
    // and going through MockWebServer to read it only adds a network dependency
    // to a question that is pure JSON construction.
    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = OpenAIChatCompletionsProtocol.buildRequestBody(
        dialect, model, thinkingRuleInstanceId, supportsPriorityProcessing, modelOverrides, chatExtraBody,
        messages, systemPrompt, maxTokens, stream, temperature, imageParts, tools, thinkingLevel,
    )

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
     * (its body is part of the client fingerprint and must stay untouched).
     */

    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
     * dropped by ~2× the body size (often tens of MB on long agent loops).
     */
    private suspend fun buildRequest(bodyStr: String): Request = dialect.buildChatOrResponsesRequest(
        bodyStr, getToken(), model.id, codexAccountId, CODEX_CLIENT_VERSION, absoluteEndpointOverride,
        perRequestHeaders, sessionId, modelOverrides, chatExtraHeaders,
    )

    /**
     * Inject provider-specific thinking parameters into the request body.
     * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
     *   forced-reasoning models keep their default)
     * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
     * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
     *   — Qwen3 thinks by default, so OFF needs an explicit disable.
     * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
     *   V4 thinks by default and rejects requests without an explicit toggle
     *   when reasoning_content is missing. Distinct from deepseek-reasoner /
     *   deepseek-chat which keep the no-params path below.
     * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
     */

    /**
     * [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
     * model actually declares. Mirrors iOS
     * `OpenAIAgentProvider.clampEffort(_:to:)` — keep both in sync.
     *
     * Necessary because the catalog's effort sets are far from uniform
     * (["low","medium","high"], ["high","max"], ["high","xhigh"], …). Sending an
     * undeclared tier is the same class of failure the MiMo/Agnes xhigh clamp
     * already guards against ("Invalid reasoning_effort: xhigh" 400s).
     *
     * Nearest-tier semantics: step down to the closest declared tier at or below
     * the request; only if none exists step up to the lowest declared one.
     * Downgrading is preferred because overshooting costs money and latency the
     * user did not ask for. Null/empty values pass the string through unchanged.
     */
    internal fun clampEffort(effort: String, values: List<String>?): String = dialect.clampEffort(effort, values)

    // MARK: - Codex image generation (gpt-image-2)

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */

    // `internal` for the same reason as [buildRequestBody]: the tool-result-image
    // regression is asserted against the constructed JSON directly.
    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        /**
         * [T-android-responses-toplevel-images] Images passed as the top-level
         * argument rather than on `msg.contentParts`, attached to the LAST user
         * message — the same contract [buildRequestBody] implements.
         *
         * This parameter did not exist, and that was a silent data loss: every
         * caller that supplies images this way (minis-model-use's `image_url`
         * blocks, VisionGroupResolver.describeOnce, any direct
         * sendMessage(imageParts=…)) had its pixels dropped on the floor the
         * moment the provider was on the Responses path, with no error. The
         * user-visible symptom was a vision model replying "no image was
         * provided" — reported against a Vision Group whose describing model
         * ran on Responses.
         */
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = OpenAIResponsesProtocol.buildRequestBody(
        dialect, model, supportsPriorityProcessing, modelOverrides, chatExtraBody,
        messages, systemPrompt, maxTokens, stream, imageParts, tools, thinkingLevel,
    )

    internal fun mapHttpError(statusCode: Int, body: String): LLMError = dialect.mapHttpError(statusCode, body)

    private fun mapError(error: Throwable): LLMError = dialect.mapError(error)
}
