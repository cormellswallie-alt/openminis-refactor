package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceCandidate
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// Modality bit layout — must match src/ios/Providers/LLMTypes.swift
// ModelModality OptionSet rawValue exactly. Used by export/import to
// transmit modality info as a single Int that iOS can decode.
internal const val MODALITY_BIT_TEXT_IN = 1 shl 0
internal const val MODALITY_BIT_TEXT_OUT = 1 shl 1
internal const val MODALITY_BIT_IMG_IN = 1 shl 2
internal const val MODALITY_BIT_PDF_IN = 1 shl 3
internal const val MODALITY_BIT_AUD_IN = 1 shl 4
internal const val MODALITY_BIT_VID_IN = 1 shl 5
internal const val MODALITY_BIT_IMG_OUT = 1 shl 6
internal const val MODALITY_BIT_AUD_OUT = 1 shl 7
internal const val MODALITY_BIT_VID_OUT = 1 shl 8

/**
 * [T-codex-dynamic-discovery GH#319] The Codex discovery endpoint rejected the
 * stored OAuth credential (HTTP 401/403).
 *
 * Thrown ONLY from a user-initiated refresh. The requirement it serves is that
 * an expired or revoked token must surface as an authentication error rather
 * than as "refreshed, list unchanged" — the latter looks like success while the
 * account is actually signed out, and sends the user hunting for a bug in the
 * model list instead of re-authenticating.
 *
 * Background/auto refresh deliberately does NOT throw: nobody is watching, and
 * an unhandled throw there would abort the whole daily refresh pass for the
 * other providers.
 */
class CodexDiscoveryAuthException(val status: Int) :
    Exception("Codex model discovery rejected the credential (HTTP $status)")

class ProviderRepository(internal val context: Context) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all providers/groups vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    internal val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    internal val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    internal val repoIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thinkingRulesMutex = Mutex()
    private val thinkingRulesCache = ConcurrentHashMap<String, List<Pair<String, ThinkingRule>>>()

    companion object {
        /** [T-sub-agents-v1] Log category for roster repairs. */
        internal const val SUB_AGENT_LOG = "SubAgents"

        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        internal const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * [T-android-models-refresh-window] How long a whole-app model refresh
         * stays fresh. iOS parity (`modelsRefreshWindow`, fe625d5fd).
         *
         * Distinct from [MODEL_CACHE_TTL_MS], which is the per-instance cache
         * used by the stale-while-revalidate path. This one gates the
         * launch/foreground sweep across every enabled instance.
         */
        internal const val MODELS_REFRESH_WINDOW_MS = 6 * 60 * 60 * 1000L

        /**
         * [T-android-model-absence-grace] How long a catalog model may stay
         * unlisted by its provider before the entry (and its overrides) is
         * really deleted. iOS parity (`modelAbsenceGracePeriod`).
         *
         * 7 days, chosen against [MODELS_REFRESH_WINDOW_MS] above: automatic
         * refreshes are at most every 6h, so this spans roughly 28 of them. A
         * model has to be missing from many consecutive responses — not one
         * unlucky one — before anything is lost, which is the whole point.
         * Deliberately generous: keeping a dead entry a few days too long costs
         * one greyed-out row, while deleting a live one costs the user's
         * overrides, silently and permanently.
         *
         * Internal rather than private so the unit test can assert the window
         * spans many refreshes instead of hardcoding a duplicate constant.
         */
        internal const val MODEL_ABSENCE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

        /** Per-instance `lastFetchAt` pref key. */
        internal fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        private const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        /**
         * [T-android-provider-voice] Normalize a base URL for shadow-voice
         * cross-instance de-dup: lowercased, trailing "/" and "/v1" stripped.
         * Mirrors iOS ProviderConfigStore.normalizedShadowKey.
         */
        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
     * refreshes it immediately. Called when instance config changes in ways
     * that could alter the set of available models (e.g. base URL rewrite,
     * credential swap). Mirrors iOS's implicit invalidation on
     * `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) = invalidateModelCacheImpl(instanceId)

    internal val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    internal val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    /** Internal scope for the one-shot async config load. */
    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    private val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        loadScope.launch {
            var adoptedByAsyncLoader = false
            // [T-android-provider-empty-load-wipe] loadConfig() THROWS when the
            // store is unreadable (rather than returning an empty config that a
            // later save would write over real data). Contain it here: an
            // uncaught throw in this coroutine reaches the default uncaught
            // handler and crashes cold start — in a LOOP, for as long as the DB
            // stays unreadable, which is precisely the post-crash state the
            // refusal exists for. Swallowing it leaves _configLoaded false, so
            // ensureConfigLoaded retries on the next access.
            try {
                val loaded = loadConfig()
                synchronized(configLock) {
                    // Only adopt the disk config if nothing has written in the
                    // meantime. A write during the load window (rare — writes come
                    // from user/refresh actions that themselves need config) flips
                    // _configLoaded true and takes precedence; we must not clobber
                    // it with the stale on-disk snapshot.
                    if (!_configLoaded.value) {
                        _config.value = loaded
                        _configLoaded.value = true
                        adoptedByAsyncLoader = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
                // [GH#306] Warm the thinking-rule cache HERE too, not only from
                // ensureConfigLoaded().
                //
                // ensureConfigLoaded() calls loadAllThinkingRulesIntoCache() only after
                // its synchronized block — and it early-returns at the top when
                // `_configLoaded` is already true. This coroutine is what sets that flag,
                // so the two raced on every cold start:
                //
                //   loader wins  -> _configLoaded true -> every later ensureConfigLoaded()
                //                   early-returns -> the cache is NEVER warmed -> custom
                //                   thinking rules silently fall back to built-ins, with
                //                   the rule still sitting on disk looking active.
                //   caller wins  -> takes the synchronized path -> falls through to the
                //                   warm -> rules work.
                //
                // Reproduced as a race, not a constant: on a Pixel 6 the synchronous
                // callers won 3/3 cold starts, which is exactly why the report ("works
                // after I edit the rule, wrong after a restart") was hard to pin down.
                // Warming from the branch that sets the flag closes the window.
                //
                // Guarded so this only runs when THIS coroutine adopted the config: if a
                // writer won the race, `ensureConfigLoaded()` already warmed the cache and
                // a second full reload would be pure work.
                if (adoptedByAsyncLoader) loadAllThinkingRulesIntoCache()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[Voice] ensureVoiceTemplateModels failed: ${e.message}")
            }
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    internal val configLock = Any()

    private fun loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

    /**
     * [T-android-provider-room-store] DB-first load with three-way
     * reconciliation between provider.db and the legacy JSON mirror:
     *
     *   - DB has rows AND meta.json_sync_hash matches the live mirror's
     *     hash → DB is in sync with what we last wrote. Use DB.
     *   - DB has rows but the hash mismatches → an older app build was
     *     installed at some point, wrote through the JSON path, and
     *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
     *     DB → resync hash.
     *   - DB is empty but JSON exists → first launch on a build that
     *     knows about the DB. One-shot import from JSON → DB.
     *   - Both empty → empty config (fresh install).
     *
     * The JSON mirror is the durable downgrade safety net: we keep
     * writing it on every save so the old build always sees current
     * config; if the user round-trips through an old build, the
     * hash check above re-syncs DB to whatever JSON looks like now.
     */
    private suspend fun loadConfigSuspending(): ProviderConfig {
        val rawJson = prefs.getString("config", null)
        // [T-android-provider-empty-load-wipe] Distinguish "the DB says zero"
        // from "we could not ask the DB". Swallowing the exception as 0 made a
        // transient DAO failure (locked/mid-write file after a crash) look
        // exactly like a fresh install: the loader fell through to an empty
        // ProviderConfig(), and the next mutation's persistToDbAndMirror wrote
        // that emptiness over a fully populated store — wiping every provider.
        // Observed on Pixel 6 after a ConcurrentModificationException crash
        // left provider.db mid-write: 18 instances became 5.
        var daoReadFailed = false
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            daoReadFailed = true
            0
        }
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
                "mirrorBytes=${rawJson?.length ?: -1}",
        )

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val snapshot = ProviderConfigSnapshot(
                    instances = providerDao.loadInstances(),
                    entries = providerDao.loadEntries(),
                    groups = providerDao.loadGroups(),
                    loopIds = providerDao.loadAgentLoopIds(),
                    meta = providerDao.loadMeta(),
                )
                val cfg = snapshot.toProviderConfig(json)
                val storedHash = snapshot.meta.firstOrNull {
                    it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                daoReadFailed = true
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val liveHash = rawJson?.let(::hashJsonMirror)
            if (liveHash == dbHashStored) {
                return dbConfig
            }
            // Hash mismatch: JSON has been written by an older build during
            // a downgrade window. Re-import JSON → reseed DB so DB catches
            // up to the user's actual current config.
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                // Migrate the per-user lastUsedEntryId SharedPreferences key
                // from the legacy random-uuid entry id form to the new
                // composite "{instanceId}/{modelId}" shape, using the
                // pre-canonicalization `parsed` entries as the uuid→composite
                // dictionary. Without this, the user's last-picked model on
                // the upgrade-first-launch isn't recognized by
                // lastUsedVisibleEntry() and the next new chat falls through
                // to the newest-provider fallback — i.e. it looks like the
                // upgrade "forgot" the user's recent selection.
                val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        // [T-android-provider-room-store] Last-resort fallback. If DB had
        // rows but the live JSON mirror is unparseable (disk corruption,
        // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
        // — losing user config is worse than running with a stale mirror.
        // The next successful save will rewrite the mirror and resync the
        // hash. Returning ProviderConfig() here would let the very next
        // mutator's persistToDbAndMirror overwrite the populated DB with
        // an empty config, silently wiping the user's providers.
        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }

        // [T-android-provider-empty-load-wipe] Reaching here means BOTH stores
        // came back empty. That is legitimate on a fresh install — but if the
        // DB read actually FAILED (rather than honestly reporting zero rows),
        // an empty config is a lie we are about to persist over real data.
        // Refuse: throwing keeps _configLoaded false, so ensureConfigLoaded
        // retries on the next access instead of caching the empty value, and
        // no mutation can run against a phantom-empty config.
        if (daoReadFailed) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                    "unusable; not overwriting a possibly-populated store",
            )
            throw IllegalStateException(
                "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    private fun hashJsonMirror(str: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(Character.forDigit(v shr 4, 16))
                append(Character.forDigit(v and 0xF, 16))
            }
        }
    }

    /**
     * Atomically persist [config] to (DB) + (legacy JSON mirror), with
     * the meta.json_sync_hash kept in lockstep with the mirror we just
     * wrote. Returns the canonicalized config (entry uuids rewritten to
     * the composite "{instanceId}/{modelId}" shape via the snapshot
     * round-trip). Callers should treat the return value as the new
     * authoritative state — using the in-memory pre-call object would
     * leak the legacy uuid form into [_config.value].
     */
    private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
        // First serialize without the hash so the meta row reflects the
        // exact string we put into prefs (the hash sees the mirror that
        // older builds will read, not a hash-of-itself).
        val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
        val mirrorHash = hashJsonMirror(mirrorStr)
        val snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash)
        providerDao.replaceAll(
            instances = snapshot.instances,
            entries = snapshot.entries,
            groups = snapshot.groups,
            loopIds = snapshot.loopIds,
            meta = snapshot.meta,
        )
        // commit() not apply(): the json_sync_hash we just stored to DB is
        // a hash of THIS mirror string. If apply() queues the disk write
        // and the process dies before it flushes, the next launch sees
        // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch
        // path interprets it as "old build wrote during downgrade" and
        // re-imports the stale JSON, blowing away the write that the DB
        // already persisted synchronously. commit() blocks the writer
        // (~5–30ms) but guarantees DB and JSON land together.
        //
        // commit() returns false (no exception) on disk-full / permission
        // failure / corrupted prefs XML. We log so the situation is
        // observable; the DB already holds the new state authoritatively,
        // and a subsequent successful save will resync the mirror + hash.
        // The downside if the next save never comes: a hash mismatch on
        // next cold-start would try to re-import the stale mirror — but
        // the dbConfig-fallback at the end of loadConfigSuspending keeps
        // the DB rows when re-import fails or is rejected, so the user's
        // data is not lost.
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror commit() returned false — DB updated " +
                    "but JSON write rejected (disk full? prefs corruption?); " +
                    "DB remains authoritative",
            )
        }
        // Return canonicalized form so the caller's _config.value reflects
        // entry uuids in composite-key shape from this write forward.
        return snapshot.toProviderConfig(json)
    }

    /**
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads `_config.value`. Without this a
     * write that lands during the startup load window would read the empty
     * placeholder, mutate it, and persist — WIPING the user's real config.
     * Idempotent and cheap once loaded (just a volatile read). Synchronized on
     * [configLock] so it can't race the async loader's emit.
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    internal fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            // [T-android-provider-empty-load-wipe] loadConfig() throws when the
            // store is unreadable. Do NOT let that escape into the caller: this
            // runs from UI handlers (every read-modify-write mutator calls it),
            // and a throw there crashes whichever gesture triggered it. Leaving
            // _configLoaded false means the next access retries, and callers
            // see the empty placeholder — which is safe, because the mutators'
            // saves cannot overwrite a store the loader refused to read.
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                return
            }
            _config.value = loaded
            _configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
        // config is available, so the (sync) request builder can read user rules.
        loadAllThinkingRulesIntoCache()
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
    }

    internal fun saveConfig(config: ProviderConfig) {
        // [T-android-provider-room-store] Double-write: DB + legacy JSON
        // mirror, both produced inside the same configLock window. The
        // mirror keeps older app builds able to read current config on
        // downgrade; the DB is the new authoritative store on this build.
        //
        // Serialize + persist + emit under [configLock] so we never serialize
        // a list that another writer is mutating. The fresh `.copy(…toMutableList())`
        // wrapper alone is not enough: data-class structural equals walks the
        // inner Lists and `prev` (already mutated in place by replaceEntries /
        // addEntry / removeEntry) compares equal to `next` → MutableStateFlow
        // suppresses the emission. T273 bumps `revision` so equals always
        // returns false and 18+ collectAsState callers see the new value.
        synchronized(configLock) {
            // [T-android-provider-empty-load-wipe] No "block empty saves" guard
            // here on purpose: mutators pass the SAME object as _config.value
            // and mutate it in place, so at this point an empty `config` and an
            // empty `_config.value` are the same list — a legitimate
            // "user deleted their last provider" is indistinguishable from a
            // phantom-empty load. The wipe is prevented at the source instead
            // (loadConfigSuspending refuses to return a blank config when the
            // DB read failed rather than honestly reporting zero rows).
            // persistToDbAndMirror returns the canonicalized config (entries'
            // uuid in composite "{instanceId}/{modelId}" form). Emit that so
            // subsequent in-memory reads — which compare entry.id by string
            // equality (e.g. group.memberEntryIds.contains(it.id)) — use one
            // consistent id shape rather than mixing legacy random uuids and
            // composite keys.
            //
            // Catch persistence failures so callers stay fire-and-forget
            // (matches the legacy `apply()` contract — pre-Room saveConfig
            // never threw). DB write fails are rare in practice (disk full,
            // SQLite corruption, transaction deadlock) but uncaught they'd
            // crash whichever UI handler triggered the mutation. Still
            // emit the in-memory state so the UI reflects the user's
            // intent even when the disk write didn't land; the next
            // successful save resyncs everything.
            val canonical = try {
                runBlocking { persistToDbAndMirror(config) }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] saveConfig persistence failed; emitting in-memory only: ${e.message}",
                    e,
                )
                config
            }
            _config.value = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                revision = ProviderConfig.nextRevision(),
            )
        }
    }

    // [T-android-provider-mutator-lock] Snapshot, not the live list. The
    // declared type is List, but the backing object is the MutableList that
    // mutators append to in place — so a caller iterating this (e.g.
    // ProvidersCollection.childIds) could take a ConcurrentModificationException
    // from a concurrent addInstance. Copying under the lock costs a few objects
    // and removes the hazard for every current and future caller.
    //
    // [T-android-hang-false-positive] Read WITHOUT the lock. Every mutator
    // builds its change on a workingCopy() and publishes a new object, so the
    // object behind `_config.value` is never mutated after publication, and a
    // StateFlow read is a volatile read of that object. The lock bought
    // nothing for a reader and cost the main thread dearly: saveConfig()
    // holds configLock across a runBlocking Room write plus a prefs commit(),
    // and the launch resolver's read of this getter waited on it for 21 s on a
    // Pixel 6 (stall-2026-09-24 10:42, stall-2026-09-25 11:33, both
    // ProviderRepository.getInstances <- AppNavigation). The copy is kept so
    // callers still get a list they cannot see change.
    val instances: List<ProviderInstance>
        get() = _config.value.instances.toList()

    /**
     * [T-android-provider-emitted-list-cow] A PRIVATE working copy of the
     * current config for a mutator to modify.
     *
     * The lock alone does not make mutation safe, because Compose readers do
     * not take it: `_config.value` is the object collectors are iterating, and
     * mutating its lists in place throws ConcurrentModificationException *in
     * the reader's* frame — reproduced on Pixel 6 as a crash inside
     * UnifiedModelPickerSheet's LazyColumn while three imports ran
     * concurrently:
     *
     *   ArrayList$Itr.checkForComodification → UnifiedModelPickerSheet
     *     → LazyListIntervalContent.<init> → Snapshot.observe
     *
     * saveConfig already EMITS defensive copies; the gap was that the next
     * mutator read that emitted object back and mutated it. Copy-on-write here
     * closes the loop: a mutator never touches a published object, so whatever
     * a reader is iterating stays frozen for the life of that iteration.
     */
    internal fun workingCopy(): ProviderConfig {
        val live = _config.value
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            // Deep-copy the groups too: ModelGroup.memberEntryIds is itself a
            // MutableList that removeEntry/removeGroup edit in place, so a
            // shallow list copy would still expose the published members to a
            // reader mid-iteration.
            modelGroups = live.modelGroups
                .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
            // [T-android-subagents-working-copy] The roster too. It was the one
            // MutableList left shared with the published config, and
            // upsert/delete/reorderSubAgents edit it in place (clear() then
            // addAll()). The lock-free `subAgents` getter could read it between
            // the two — an empty roster, so delegate_task reported an unknown
            // sub agent — or throw ConcurrentModificationException mid-iteration.
            subAgents = live.subAgents.toMutableList(),
        )
    }

    fun addInstance(instance: ProviderInstance): Unit = addInstanceImpl(instance)

    /**
     * [T-android-provider-voice] Reconcile voice-template seed entries on every
     * launch: add template models a previous build didn't know, remove RETIRED
     * seeds, and heal corrupted modality. Straight port of iOS
     * ProviderConfigStore.ensureVoiceTemplateModels with its two hard-won
     * guards:
     *
     *  - [T-voice-seed-shape-exact] Stale-seed removal matches ONLY the exact
     *    single-flag seed shape (isVoiceTemplateSeedShape). API-fetched models
     *    always carry the paired text side (≥2 flags), so a future API ASR/TTS
     *    model can never be mistaken for a seed and wiped every launch
     *    (regression class of cffbec0e: MiMo launch model count 15→11).
     *  - Heal: a models refresh can overwrite a template entry with text-only
     *    modality from /v1/models; restore the template's authoritative
     *    modality when it diverged.
     */
    fun ensureVoiceTemplateModels() = ensureVoiceTemplateModelsImpl()

    fun updateInstance(instance: ProviderInstance): Unit = updateInstanceImpl(instance)

    fun removeInstance(instanceId: String): Unit = removeInstanceImpl(instanceId)

    /**
     * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
     * `auto`-mode image generation on [instanceId]. Called by
     * ModelUseOffloadHandler after a successful /images/generations call (cache
     * `imagesGenerations`) or after a route-missing fallback (cache
     * `chatCompletions`). No-op when unchanged so we don't churn the config /
     * iCloud sync on every image call. Does not invalidate the model cache —
     * the endpoint choice never moves the model list.
     */
    // [T-android-provider-mutator-lock] `ProviderInstance` has mutable `var`
    // fields, so `workingCopy()`'s list copy is not enough on its own — the
    // instance objects inside it are still the published ones. Replace the
    // element with a `.copy()` rather than writing through to the shared
    // object. Called from ModelUseOffloadHandler on an offload worker thread
    // during image generation, so the unsynchronized write had no
    // happens-before with main-thread readers.
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit = setImageEndpointResolvedImpl(instanceId, endpoint)

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedByReleaseRank()

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedByReleaseRank()

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries
                .filter { it.providerInstanceId in enabledIds && !it.isHidden }
                .sortedByReleaseRank()
        }

    /**
     * [T-model-release-ranking] Newest / most capable model first, so a picker
     * never opens on a stale (or, as in OpenMinis#83, an uncallable) model.
     *
     * The ordering itself now lives in [ModelEntryRanking] so the model-picker
     * sheets can apply the SAME order to lists they build straight from a
     * `ProviderConfig` snapshot, without a second copy of the comparator.
     */
    private fun List<ModelEntry>.sortedByReleaseRank(): List<ModelEntry> =
        ModelEntryRanking.sortedByReleaseRank(this)

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /**
     * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
     * entry — or null when the recorded id was deleted / hidden / its provider
     * disabled. Callers treat null as "fall through to the next tier".
     */
    fun lastUsedVisibleEntry(): ModelEntry? = lastUsedVisibleEntryImpl()

    /**
     * [T-newchat-default-model-fallback-android] Final-tier default for a new
     * chat: the newest text-output model from the newest-added enabled
     * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
     * model" = last text-capable entry in add order (modelEntries is appended
     * in add order, so the instance's last matching entry is the most recently
     * added). Image/audio-only models are excluded — a fresh chat must default
     * to something that can produce a text reply.
     *
     * Walks providers newest→oldest so that if the newest provider somehow has
     * no text model (all image/audio), we still land on the newest text model
     * from the next provider rather than returning null.
     */
    fun newestProviderNewestTextEntry(): ModelEntry? = newestProviderNewestTextEntryImpl()

    /**
     * [T-disabled-provider-via-group-android] Members of [group] whose
     * provider instance is currently enabled. Used everywhere we previously
     * read `group.memberEntryIds` directly to resolve / display a model —
     * runtime selection must skip disabled providers, and the settings UI
     * uses this to surface "no available models" when every member sits
     * behind a disabled provider.
     *
     * Returns entries in the same order as [ModelGroup.memberEntryIds] so
     * the routing-strategy semantics (primary = first member) carry over.
     * Entries whose [ModelEntry.providerInstanceId] no longer maps to any
     * instance are also filtered — orphaned ids would otherwise pin the
     * group to a model that can't be instantiated.
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> = enabledMemberEntriesImpl(group)

    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? = firstEnabledMemberEntryImpl(group)

    /**
     * [T-android-group-resolve-skip-uncredentialed] Whether [instance] has ANY
     * usable credential — API key, manual bearer, or a stored OAuth token.
     *
     * Mirrors iOS `ProviderInstance.hasAnyCredential`. The distinction that
     * matters here is OAuth: [usableApiKey] only reads `apikey_<id>`, so an
     * instance that is logged in via OAuth (Claude Code / Codex login) reads
     * as "no credential" to every `?: return`/`?: continue` call site even
     * though the chat path authenticates with it perfectly well. Routing must
     * not skip a provider the user is actually signed into.
     *
     * Deliberately synchronous and allocation-light: this runs inside model
     * selection on the main thread. It only probes credential STORAGE — it
     * never refreshes a token or performs I/O beyond an EncryptedSharedPrefs
     * read, so an expired-but-present token still counts as credentialed and
     * the refresh happens later on the request path (same as iOS).
     */
    fun hasAnyCredential(instance: ProviderInstance): Boolean = hasAnyCredentialImpl(instance)

    /**
     * [T-android-group-resolve-skip-uncredentialed] Members of [group] that are
     * actually usable RIGHT NOW: not hidden, provider enabled, and provider
     * holding a credential.
     *
     * This is the Android counterpart of iOS `ModelGroupRouter.availableEntryIds`
     * and exists because [enabledMemberEntries] answers a narrower question
     * ("is the provider switched on"), which is the right filter for the
     * settings UI but too weak for routing: a member whose provider has no
     * credential cannot serve a request, so selecting it only produces an
     * immediate auth failure.
     *
     * Order is preserved from [ModelGroup.memberEntryIds] so "primary = first
     * member" still holds — the filter removes candidates, it never reorders.
     */
    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> = availableMemberEntriesImpl(group)

    /**
     * [T-android-regenerate-title-submodel] The dedicated title-generation
     * sub-model entry: the first enabled member of the configured
     * `defaultSubGroupId` group. Returns null when no sub-group is configured or
     * every member sits behind a disabled provider (caller then falls back to
     * the primary model). Single source of truth for both the auto-title path
     * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
     * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
     */
    fun resolveTitleSubEntry(): ModelEntry? = resolveTitleSubEntryImpl()

    /**
     * [T-disabled-provider-via-group-android] True when [entryId] resolves
     * to an entry whose provider instance is currently enabled. Used by the
     * settings UI to dim members that won't be reachable at runtime.
     */
    fun isEntryProviderEnabled(entryId: String): Boolean = isEntryProviderEnabledImpl(entryId)

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = replaceEntriesImpl(instanceId, models)

    // --- Model Entry management ---
    //
    // [T-android-provider-mutator-lock] Every read-modify-write mutator below
    // holds configLock for its WHOLE body, not just the saveConfig at the end.
    //
    // These mutate `_config.value`'s inner MutableLists IN PLACE — the very
    // lists already handed to collectors (the pickers filter over
    // config.modelEntries on the main thread). Mutating them from an IO thread
    // while a composition iterates throws ConcurrentModificationException in
    // the READER. 5399fe270 removed one such walk (ProviderConfig.equals inside
    // StateFlow emission) but left every consumer exposed; the shared mutable
    // list is the actual defect, and the lock is what serialises it against
    // saveConfig's defensive copy (which only snapshots AFTER the mutation).
    // configLock is a plain JVM monitor, so the nested saveConfig re-entry is
    // fine — replaceEntries / ensureVoiceTemplateModels already rely on that.

    fun addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (!entry.isCustom) {
            val exists = config.modelEntries.any {
                it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
            }
            // Explicit label: a bare `return` in an expression body reads as if
            // it might only leave the synchronized lambda.
            if (exists) return@addEntry
        }
        config.modelEntries.add(entry)
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
            saveConfig(config)
        }
    }

    fun removeEntry(entryId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        // T171: cascade-clean the agent-loop direct-entry pin so the
        // AgentLoopModelsScreen never surfaces a checkmark on a model that
        // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
        // (Providers/ProviderConfigStore.swift L268).
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        saveConfig(config)
    }

    // --- Model Group management ---

    fun addGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.add(group)
        saveConfig(config)
    }

    fun updateGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            // Defensive re-wrap: callers routinely pass `published.copy(...)`,
            // and data-class copy() shares memberEntryIds BY REFERENCE with the
            // published group. Storing that object would put published inner
            // state back inside a working copy, quietly undoing the invariant.
            config.modelGroups[idx] = group.copy(
                memberEntryIds = group.memberEntryIds.toMutableList(),
            )
            saveConfig(config)
        }
    }

    fun removeGroup(groupId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.removeAll { it.id == groupId }
        if (config.defaultPrimaryGroupId == groupId) {
            config.defaultPrimaryGroupId = null
        }
        if (config.defaultSubGroupId == groupId) {
            config.defaultSubGroupId = null
        }
        if (config.voiceInputGroupId == groupId) {
            config.voiceInputGroupId = null
        }
        if (config.voiceOutputGroupId == groupId) {
            config.voiceOutputGroupId = null
        }
        if (config.visionGroupId == groupId) {
            config.visionGroupId = null
        }
        config.agentLoopGroupIds.removeAll { it == groupId }
        // [T-sub-agents-v1] A sub agent pinned to this group reverts to Auto.
        // Leaving the dangling id would make the definition resolve to
        // "pinned, but unroutable" on every delegation — the child would
        // silently run on the parent's model while the settings screen still
        // claimed a group the user had deleted.
        config.subAgents.forEachIndexed { i, def ->
            if (def.modelGroupId == groupId) {
                config.subAgents[i] = def.copy(modelGroupId = null)
            }
        }
        saveConfig(config)
    }

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopModelEntryIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
        // first-seen order). addAgentLoopEntry guards against dups, but any
        // path writing straight through this sink (cross-device sync merge,
        // reorder write-back, data migration) could otherwise persist a
        // duplicate id — which surfaces downstream as two pinnedEntries with
        // the same id, a duplicate LazyColumn key, and a crash on scroll.
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        saveConfig(config)
    }

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopGroupIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (see above) so
        // no write path can persist duplicate group ids → duplicate LazyColumn
        // key on the agent-loop groups list.
        config.agentLoopGroupIds.addAll(ids.distinct())
        saveConfig(config)
    }

    // T182: thin add/remove helpers used by AgentLoopModelsSection +
    // AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
    // set* functions so caller doesn't have to round-trip through the
    // existing list (mutating in place would race with a parallel
    // saveConfig). Keep insertion order — preserves the order the user
    // added items in the picker, mirrors iOS appendIfNeeded behaviour.

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    // T186: reorder helpers — UI keeps a local mutable copy during
    // drag and pushes the final order through here on drop. Validates
    // that [newOrder] is a permutation of the current list to defend
    // against a stale dragged-from-different-snapshot reorder slipping
    // in mid-write (e.g. a cascade-cleanup raced with the drag).
    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val cur = _config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val cur = _config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopGroupIds(newOrder)
    }

    /**
     * [T-android-provider-reorder] Reorder provider instances (drag-to-sort in
     * the Providers list). Mirrors iOS `ProviderConfigStore.reorderInstances`.
     *
     * [newOrder] is a list of instance ids. Unknown ids are dropped and any
     * instance missing from it is appended in its existing relative order, so a
     * caller that only knows about ONE provider-type section can pass just that
     * section's ids and leave the rest untouched — which is exactly what the
     * per-section drag in ProviderListScreen does.
     *
     * Persistence: `sort_order` is derived from list position at save time
     * (ProviderConfigMapping writes `sortOrder = idx`) and the DAO reads back
     * `ORDER BY sort_order ASC`, so permuting the list IS the persistence — no
     * per-row column write is needed.
     *
     * Unlike iOS there is no dirty-marking step here: Android keeps provider
     * config local-only (Room + the JSON mirror), with no CloudKit upload, so
     * the "pure reorder doesn't mark dirty" bug iOS had to fix has no analogue.
     * `saveConfig` bumps `revision`, which is the Android-side equivalent
     * concern — it guarantees the StateFlow re-emits even though a pure
     * permutation compares equal under data-class structural equality.
     */
    fun reorderInstances(newOrder: List<String>): Unit = reorderInstancesImpl(newOrder)

    /**
     * [T-android-modelgroup-reorder] Reorder the user's Model Groups
     * (drag-to-sort in the Model Groups screen). Mirrors iOS
     * `ProviderConfigStore.reorderGroups` (4ba54ff5) and follows the exact
     * contract of [reorderInstances] above: unknown ids dropped, duplicates
     * collapsed, unmentioned groups appended in their existing relative order,
     * no-op guard, and the list mutation held under [configLock] — same
     * CME/empty-window rationale as documented on reorderInstances
     * ([T-android-reorder-unlocked-mutation]).
     *
     * Persistence needs nothing extra: ProviderConfigMapping writes each
     * group's `sort_order` from its list index at save time and the DAO reads
     * `ORDER BY sort_order ASC`, so list position IS the stored order.
     */
    fun reorderModelGroups(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ModelGroup>(current.size)
        for (id in newOrder) {
            val group = byId[id] ?: continue     // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(group)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (group in current) {
            if (seen.add(group.id)) reordered.add(group)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(configLock) {
            config.modelGroups.clear()
            config.modelGroups.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Expands groups to their members, unions with individual entries, dedupes by ID,
     * and filters to entries of enabled provider instances. Mirrors iOS
     * ProviderConfigStore.resolvedAgentLoopEntries.
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> = resolvedAgentLoopEntriesImpl()

    fun group(id: String): ModelGroup? =
        _config.value.modelGroups.find { it.id == id }

    // ---------------------------------------------------------------- sub agents

    /**
     * [T-sub-agents-v1] The sub agent roster, always normalized.
     *
     * Normalizing on READ rather than only on write is what makes the built-in
     * unconditional: the stored list can arrive from a newer build, from
     * another platform, or from a device set to a different language, and every
     * consumer (the tool schema's `agent` enum, the system-prompt roster, the
     * settings list) has to see the same repaired view. Cheap enough to do per
     * read — the list is bounded at 10.
     */
    val subAgents: List<SubAgentDefinition>
        get() = SubAgentRoster.normalize(_config.value.subAgents) { msg ->
            AppLogger.info(SUB_AGENT_LOG, msg)
        }

    /** One definition by id, from the normalized roster. */
    fun subAgent(id: String): SubAgentDefinition? = subAgents.find { it.id == id }

    /**
     * Insert or replace a definition, then persist the normalized roster.
     *
     * Writing the normalized form back (rather than the caller's list) keeps
     * the stored blob and the in-memory view identical, so a later read cannot
     * produce a different order or a different count than the write did.
     */
    fun upsertSubAgent(def: SubAgentDefinition): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val stamped = def.copy(updatedAt = System.currentTimeMillis())
        val idx = config.subAgents.indexOfFirst { it.id == stamped.id }
        if (idx >= 0) config.subAgents[idx] = stamped else config.subAgents.add(stamped)
        val normalized = SubAgentRoster.normalize(config.subAgents) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    /** Delete a custom definition. The built-in cannot be deleted. */
    fun deleteSubAgent(id: String): Unit = synchronized(configLock) {
        if (id == SubAgentDefinition.BUILT_IN_ID) return
        ensureConfigLoaded()
        val config = workingCopy()
        config.subAgents.removeAll { it.id == id }
        val normalized = SubAgentRoster.normalize(config.subAgents) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    /** Apply a new order, by id. Unknown ids are ignored; missing ones keep their place. */
    fun reorderSubAgents(orderedIds: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val byId = config.subAgents.associateBy { it.id }
        val reordered = mutableListOf<SubAgentDefinition>()
        orderedIds.forEach { id -> byId[id]?.let { reordered.add(it) } }
        config.subAgents.forEach { if (it.id !in orderedIds) reordered.add(it) }
        reordered.forEachIndexed { i, def -> reordered[i] = def.copy(sortOrder = i) }
        val normalized = SubAgentRoster.normalize(reordered) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    var defaultPrimaryGroupId: String?
        get() = _config.value.defaultPrimaryGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultSubGroupId = value
            saveConfig(config)
        }

    // --- Voice groups [T-android-provider-voice] ---

    var voiceInputGroupId: String?
        get() = _config.value.voiceInputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceInputGroupId = value
            saveConfig(config)
        }

    var voiceOutputGroupId: String?
        get() = _config.value.voiceOutputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceOutputGroupId = value
            saveConfig(config)
        }

    // --- Vision group [T-android-vision-group / GH#182] ---

    var visionGroupId: String?
        get() = _config.value.visionGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.visionGroupId = value
            saveConfig(config)
        }

    /** True when a Vision Group is bound AND still exists. Gates read_image
     *  tool exposure for main models that cannot natively see images. */
    fun hasVisionGroupConfigured(): Boolean {
        val gid = _config.value.visionGroupId ?: return false
        return _config.value.modelGroups.any { it.id == gid }
    }

    /** Bound Vision group's display name, or null. */
    fun visionGroupName(): String? {
        val gid = _config.value.visionGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-vision-group] Ordered vision-capable fail-over candidates from
     * the bound Vision Group. Mirrors resolveVoiceInputCandidates: filters
     * members to enabled instances whose model declares image input, honours
     * the group's routing strategy (`fallback` keeps order; `loadBalance`
     * rotates the start by [loadBalanceSeed] so separate reads spread across
     * members). Returns [] when no group is bound or no member is usable — the
     * caller (ReadImageTool) then returns a clear failure text.
     */
    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> = resolveVisionCandidatesImpl(loadBalanceSeed)

    // --- Thinking rules (custom) [T-android-thinking-rules-phase2] ---
    //
    // User-authored rules live in provider.db (provider_thinking_rules), keyed by
    // provider-instance id, in sort_order priority order. Built-in vendor rules are
    // never stored. On every mutation we publish the instance's rules into the
    // ThinkingRuleResolver cache so the (sync) request-builder can read them.

    /** Non-blocking in-memory snapshot for UI initial render before async Room load completes. */
    fun cachedThinkingRulesWithIds(instanceId: String): List<Pair<String, ThinkingRule>>? =
        thinkingRulesCache[instanceId]

    /** Load one instance's custom rules and persisted ids from Room off the main thread. */
    suspend fun loadThinkingRulesWithIds(instanceId: String): List<Pair<String, ThinkingRule>> =
        withContext(Dispatchers.IO) {
            runCatching {
                providerDao.loadThinkingRules(instanceId)
                    .sortedBy { it.sortOrder }
                    .map { it.id to ThinkingRuleCoding.toRule(it) }
            }.getOrDefault(emptyList()).also { entries ->
                thinkingRulesCache[instanceId] = entries
                ThinkingRuleResolver.setCustomRules(instanceId, entries.map { it.second })
            }
        }

    /** Load one instance's custom rules (serves from memory cache when warm). */
    fun thinkingRules(instanceId: String): List<ThinkingRule> {
        thinkingRulesCache[instanceId]?.let { return it.map { pair -> pair.second } }
        return runBlocking(Dispatchers.IO) { loadThinkingRulesWithIds(instanceId).map { it.second } }
    }

    /** The persisted ids for one instance's custom rules, parallel to [thinkingRules]. */
    fun thinkingRuleIds(instanceId: String): List<String> {
        thinkingRulesCache[instanceId]?.let { return it.map { pair -> pair.first } }
        return runBlocking(Dispatchers.IO) { loadThinkingRulesWithIds(instanceId).map { it.first } }
    }

    /** First model id served by [instanceId], for the resolution-trace sample. Null if none. */
    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    }

    /** Warm the resolver cache with every instance's custom rules (called on config load). */
    fun loadAllThinkingRulesIntoCache() {
        runCatching {
            val rows = runBlocking(Dispatchers.IO) { providerDao.loadAllThinkingRules() }
            val byInstanceEntries = rows.groupBy { it.providerInstanceId }
                .mapValues { (_, rs) ->
                    rs.sortedBy { it.sortOrder }.map { it.id to ThinkingRuleCoding.toRule(it) }
                }
            thinkingRulesCache.clear()
            thinkingRulesCache.putAll(byInstanceEntries)
            ThinkingRuleResolver.setAllCustomRules(
                byInstanceEntries.mapValues { (_, entries) -> entries.map { it.second } },
            )
        }
    }

    internal fun deleteThinkingRulesForInstanceAsync(instanceId: String) {
        thinkingRulesCache.remove(instanceId)
        ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        repoIoScope.launch {
            thinkingRulesMutex.withLock {
                runCatching { providerDao.deleteThinkingRulesForInstance(instanceId) }
            }
        }
    }

    private fun publishThinkingRulesSnapshot(
        instanceId: String,
        entities: List<com.openminis.app.data.db.ThinkingRuleEntity>,
    ) {
        val entries = entities.sortedBy { it.sortOrder }.map { it.id to ThinkingRuleCoding.toRule(it) }
        thinkingRulesCache[instanceId] = entries
        ThinkingRuleResolver.setCustomRules(instanceId, entries.map { it.second })
    }

    /**
     * Suspend variant of [saveThinkingRule] safe to invoke from UI/ViewModel coroutines
     * without blocking the main thread.
     */
    suspend fun saveThinkingRuleAsync(instanceId: String, rule: ThinkingRule, id: String? = null): String =
        withContext(Dispatchers.IO) {
            thinkingRulesMutex.withLock {
                val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
                val ruleId = id ?: java.util.UUID.randomUUID().toString()
                val idx = existing.indexOfFirst { it.id == ruleId }
                if (idx >= 0) {
                    existing[idx] = ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, existing[idx].sortOrder)
                } else {
                    existing.add(0, ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, 0))
                }
                val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
                providerDao.replaceThinkingRules(instanceId, renumbered)
                publishThinkingRulesSnapshot(instanceId, renumbered)
                ruleId
            }
        }

    /**
     * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
     * list (position 0) — a rule overriding a built-in is useless below it; existing
     * rules shift down. A non-null [id] updates in place, preserving position.
     * Returns the rule id.
     */
    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String =
        runBlocking(Dispatchers.IO) { saveThinkingRuleAsync(instanceId, rule, id) }

    /** Suspend variant of [deleteThinkingRule] safe for UI callers. */
    suspend fun deleteThinkingRuleAsync(instanceId: String, id: String) = withContext(Dispatchers.IO) {
        thinkingRulesMutex.withLock {
            providerDao.deleteThinkingRule(id)
            val survivors = providerDao.loadThinkingRules(instanceId)
                .sortedBy { it.sortOrder }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, survivors)
            publishThinkingRulesSnapshot(instanceId, survivors)
        }
    }

    /** Delete a custom rule by id. Hard delete — Android provider config is local-only,
     *  so there is no sync channel that could resurrect it (no tombstone needed). */
    fun deleteThinkingRule(instanceId: String, id: String) =
        runBlocking(Dispatchers.IO) { deleteThinkingRuleAsync(instanceId, id) }

    /** Suspend variant of [reorderThinkingRules] safe for UI callers. */
    suspend fun reorderThinkingRulesAsync(instanceId: String, orderedIds: List<String>) =
        withContext(Dispatchers.IO) {
            thinkingRulesMutex.withLock {
                val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
                val reordered = orderedIds.mapNotNull { byId[it] }
                    .mapIndexed { i, e -> e.copy(sortOrder = i) }
                val omitted = byId.values.filter { it.id !in orderedIds }
                val merged = (reordered + omitted).mapIndexed { i, e -> e.copy(sortOrder = i) }
                providerDao.replaceThinkingRules(instanceId, merged)
                publishThinkingRulesSnapshot(instanceId, merged)
            }
        }

    /** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) =
        runBlocking(Dispatchers.IO) { reorderThinkingRulesAsync(instanceId, orderedIds) }

    /**
     * Built-in rules relevant to THIS instance, for the Provider-detail UI. Mirrors iOS
     * builtInRulesForDisplay: resolve the vendor context from the instance's base URL,
     * then keep every AllModels-scoped rule (endpoint/provider-type defaults) plus any
     * ModelPattern rule the provider actually serves a matching model for. An empty
     * catalog keeps everything (list must not be mysteriously empty before first fetch).
     */
    fun builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
        ensureConfigLoaded()
        val config = _config.value
        val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
        val base = (inst.effectiveBaseURL ?: "").lowercase()
        val ctx = com.openminis.app.provider.thinking.ThinkingResolveContext(
            modelId = "",
            supportsReasoning = null,
            declaredEffortValues = null,
            level = com.openminis.app.data.model.ThinkingLevel.OFF,
            maxTokens = 0,
            isOpenRouter = base.contains("openrouter.ai"),
            usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
            isMistral = base.contains("mistral.ai"),
            isDashScope = base.contains("dashscope"),
            offEffort = null,
        )
        val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
        return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
            when (rule.scope) {
                is ThinkingRule.Scope.AllModels -> true
                is ThinkingRule.Scope.ModelPattern ->
                    modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
            }
        }
    }

    /**
     * Ensure a default Voice INPUT group exists and is bound when the user
     * hasn't configured one. Seeds "Voice Input" with the System ASR sentinel
     * entries (device SpeechRecognizer online/offline). Sentinel composite ids
     * match iOS so a config moved cross-platform keeps its selection. No-op
     * when a valid binding already exists. Mirrors iOS
     * ProviderConfigStore.ensureDefaultVoiceInputGroup.
     */
    fun ensureDefaultVoiceInputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceInputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Input",
            memberEntryIds = mutableListOf(
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            ),
        )
        config.modelGroups.add(group)
        config.voiceInputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Input group ${group.id.take(8)} [System ASR online+offline]")
        return group.id
    }

    /**
     * Ensure a default Voice OUTPUT group exists and is bound. Seeds
     * "Voice Output" with the System TTS auto sentinel (device TextToSpeech,
     * best voice per reply language). Mirrors iOS ensureDefaultVoiceOutputGroup.
     */
    fun ensureDefaultVoiceOutputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceOutputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Output",
            memberEntryIds = mutableListOf("$sentinel/${SystemVoiceIds.SYSTEM_TTS}"),
        )
        config.modelGroups.add(group)
        config.voiceOutputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Output group ${group.id.take(8)} [System Voice (Auto)]")
        return group.id
    }

    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector (mirrors iOS VoiceSelectionStore.inputEntryId).
     * Values: a System sentinel composite id ("<sentinel>/system-asr-online" /
     * "-offline"), a provider entry composite id, or null = follow the Voice
     * Input group binding. Per-device (prefs), not part of the synced config.
     */
    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-input choice for the panel: either the on-device
     * System engine (with an online/offline preference) or a provider entry.
     * Resolution order mirrors iOS VoiceProviderResolver: explicit override
     * first, then the Voice Input group's members in fallback order (a System
     * sentinel member selects the System engine), then the System default.
     */
    data class VoiceInputChoice(
        /** null → provider-backed; non-null → System engine (true = prefer offline). */
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    fun resolveVoiceInputChoice(): VoiceInputChoice = resolveVoiceInputChoiceImpl()

    /** Bound Voice Input group's display name, or null (chip's "Group · Model"). */
    fun voiceInputGroupName(): String? {
        val gid = _config.value.voiceInputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-provider-voice] Resolve the current provider-backed voice
     * INPUT selection: honours the explicit override first, then walks the
     * Voice Input group's members in order (fallback semantics). System
     * sentinel members are skipped — they are served by
     * SystemSpeechRecognitionEngine, not a cloud provider.
     */
    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceInputChoice().entry

    /**
     * [T-voice-asr-group-failover] Ordered ASR fail-over candidates: the
     * explicit override first (if usable and provider-backed), then every
     * usable member of the Voice Input group, ordered per the group's routing
     * strategy — `fallback` keeps group order, `loadBalance` rotates the start
     * position by [loadBalanceSeed] so separate capture sessions spread across
     * members (mirrors ModelGroupRouter's per-session rotation for text chat,
     * and iOS VoiceProviderResolver.resolvedInputCandidates). An explicit
     * System override returns [] — the caller uses the System engine directly.
     * System sentinel group members are skipped: they never serve cloud ASR.
     */
    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> = resolveVoiceInputCandidatesImpl(loadBalanceSeed)

    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---
    //
    // Mirror of the voice-INPUT resolution above. Before this existed,
    // `voiceOutputGroupId` was write-only: the user could bind a Voice Output
    // group in settings and it was persisted, but nothing at runtime ever read
    // it back, so read-aloud always fell through to the on-device engine and
    // every provider TTS voice was unreachable outside the Quick Test sheet.

    /**
     * Explicit voice-output voice override picked in a selector (mirrors iOS
     * VoiceSelectionStore.outputEntryId, and [voiceInputOverrideEntryId] on the
     * input side). Values: a System sentinel composite id
     * ("<sentinel>/system-tts"), a provider entry composite id, or null =
     * follow the Voice Output group binding. Per-device (prefs), not part of
     * the synced config.
     */
    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-output choice for read-aloud: either the
     * on-device System TextToSpeech or a provider entry. Same shape and
     * resolution order as [VoiceInputChoice].
     */
    data class VoiceOutputChoice(
        /** null → provider-backed; true → System TextToSpeech. */
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    /**
     * Resolution order mirrors [resolveVoiceInputChoice] exactly: explicit
     * override first, then the Voice Output group's members in fallback order
     * (a System sentinel member selects the device engine), then the System
     * default as the terminal fallback.
     *
     * The capability gate is `isVoiceOutputCandidate` (vs
     * `isVoiceInputCandidate` on the input side) — a model that only *accepts* audio must never be picked to
     * *produce* it.
     */
    fun resolveVoiceOutputChoice(): VoiceOutputChoice = resolveVoiceOutputChoiceImpl()

    /**
     * [T-android-voice-picker-active] Which MEMBER of a bound voice group is
     * the one that would actually serve the next request — the id the picker
     * marks "Active" when no explicit override is set.
     *
     * Deliberately derived here rather than recomputed in the picker: this is
     * the same first-usable-member-in-group-order walk that
     * [resolveVoiceInputChoice] / [resolveVoiceOutputChoice] perform, and a
     * second copy in the UI would silently drift from the runtime rule the
     * moment either resolver's usability test changes. Returns null when no
     * member is usable (the group can serve nothing).
     *
     * Note this reports the group's *static* first choice. Under
     * `loadBalance`, or after a fail-over, the request may land on a later
     * member; the badge answers "which one leads", which is what the row's
     * fallback-strategy chip already promises.
     */
    fun activeVoiceGroupMemberId(output: Boolean): String? = activeVoiceGroupMemberIdImpl(output)

    /** Bound Voice Output group's display name, or null. */
    fun voiceOutputGroupName(): String? {
        val gid = _config.value.voiceOutputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * Resolve the current provider-backed voice OUTPUT selection, or null when
     * read-aloud should use the on-device engine. Counterpart of
     * [resolveVoiceInputEntry].
     */
    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? = resolveVoiceOutputEntryImpl()

    // --- Shadow Voice Providers [T-android-provider-voice] ---
    // Port of iOS [T-mimo-shadow-voice]: a "Voice Service" is NOT a stored
    // entity — it's a runtime read-only MIRROR of any ordinary instance that
    // owns audio-modality model entries (dual-purpose vendors like MiMo serve
    // text + voice on one host). Voice ability is a per-MODEL concern; no
    // base-URL "voice-only" whitelist.

    /** True if [instanceId] has ANY model entry with an audio modality. */
    fun hasVoiceModels(instanceId: String): Boolean =
        _config.value.modelEntries.any {
            it.providerInstanceId == instanceId && it.model.isVoiceCandidate
        }

    /**
     * Per-instance "hide from Voice Services" flag ("I only want the text
     * models"). Absent = shadow shown by default. Stored in prefs (mirrors iOS
     * UserDefaults voiceShadowDisabled.<id>), not the synced config.
     */
    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
        prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
        // Bump revision so Compose collectors re-read the shadow list.
        // The read must be INSIDE the lock: reading _config.value outside it
        // and re-publishing that snapshot afterwards would silently revert any
        // save that landed in between (lost update). The other two
        // revision-bump sites already read inside their block.
        synchronized(configLock) {
            _config.value = _config.value.copy(revision = ProviderConfig.nextRevision())
        }
    }

    /**
     * A read-only mirror of an instance's voice capability, surfaced in Voice
     * Services. Shares the underlying instance's credential + endpoint.
     */
    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,   // audio-in entries (ASR)
        val outputModels: List<ModelEntry>,  // audio-out entries (TTS)
    )

    /**
     * All shadow voice providers: one per enabled instance that has audio
     * models and isn't shadow-disabled, FOLDED by normalized base URL so two
     * instances on one host show a single deterministic representative row.
     */
    fun shadowVoiceProviders(): List<ShadowVoiceProvider> {
        ensureConfigLoaded()
        val config = _config.value
        // [T-android-reorder-unlocked-mutation] Snapshot first — same
        // main-thread composition reader as hasFoldedShadowDuplicates.
        val candidates = config.instances.toList().filter { inst ->
            inst.isEnabled && hasVoiceModels(inst.id) && !isVoiceShadowDisabled(inst.id) &&
                com.openminis.app.provider.voice.VoiceProviderFactory.supports(inst, loadApiKey(inst.id))
        }
        val byKey = candidates.groupBy { inst ->
            normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
        }

        fun mostRecentModified(instanceId: String): Long =
            config.modelEntries
                .filter { it.providerInstanceId == instanceId }
                .mapNotNull { it.userModifiedAt }
                .maxOrNull() ?: Long.MIN_VALUE

        return byKey.values.map { insts ->
            // Representative selection — deterministic across devices: enabled
            // first, then most-recently-modified entries, then oldest createdAt,
            // then id.
            val rep = insts.sortedWith(
                compareByDescending<ProviderInstance> { it.isEnabled }
                    .thenByDescending { mostRecentModified(it.id) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            ).first()
            val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
            ShadowVoiceProvider(
                instanceId = rep.id,
                displayName = rep.label,
                inputModels = entries.filter { it.model.isVoiceInputCandidate },
                outputModels = entries.filter { it.model.isVoiceOutputCandidate },
            )
        }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
    }

    /**
     * True when ≥2 enabled instances share a normalized base URL AND have voice
     * models — the folded-duplicate case; UI shows a non-destructive hint.
     */
    fun hasFoldedShadowDuplicates(): Boolean {
        val seen = mutableSetOf<String>()
        // [T-android-reorder-unlocked-mutation] Snapshot before iterating.
        // This runs on the MAIN thread from ProviderListScreen's composition,
        // so taking configLock here would block the UI behind a DB write.
        // toList() copies under no contention and removes the CME risk
        // outright — the writer's structural edits can no longer be observed
        // mid-iteration.
        for (inst in _config.value.instances.toList()) {
            if (!inst.isEnabled || !hasVoiceModels(inst.id)) continue
            val key = normalizedShadowKey(inst.customBaseURL)
            if (key.isEmpty()) continue
            if (!seen.add(key)) return true
        }
        return false
    }

    /**
     * [T-provider-refresh-outlives-screen] (GH#265) Background work owned by
     * the repository — it outlives any screen that starts it. See
     * [ModelReconciler].
     */
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val modelReconciler = ModelReconciler(
        scope = repositoryScope,
        lookup = { id -> instance(id) },
        refresh = { inst, force -> refreshModels(inst, forceRefresh = force) },
    )

    /**
     * Refresh [instanceId]'s model list in the background, independent of the
     * caller's lifetime. Use this whenever a screen that triggers a refresh
     * may be popped right after (save / sign-in flows): a refresh launched on
     * that screen's own scope is cancelled with it.
     */
    fun triggerAsyncModelReconcile(instanceId: String, forceRefresh: Boolean = false): kotlinx.coroutines.Job =
        modelReconciler.trigger(instanceId, forceRefresh)

    /**
     * @param forceRefresh set by a user-initiated Refresh tap. Today only the
     *   Codex discovery path reads it ([T-codex-dynamic-discovery GH#319]:
     *   "手动点击刷新必须真正绕过缓存重新拉取"); every other path is already
     *   uncached at this level. Defaulted false so background/auto refresh and
     *   the existing call sites keep their behaviour.
     * @param onVendorError [T-onboarding-model-fetch-fallback] receives the
     *   vendor fetch's error message. The fetch still falls through to the
     *   models.dev fallback exactly as before; this only lets a caller that has
     *   to explain an empty list (onboarding's model picker) show the provider's
     *   own words instead of an endless spinner.
     */
    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        onVendorError: ((String) -> Unit)? = null,
    ) = refreshModelsImpl(instance, forceRefresh, onVendorError)

    /**
     * Stale-while-revalidate helper for UI code. Callers (e.g. the model
     * picker sheet) invoke this on open: the currently-persisted
     * `config.modelEntries` is returned immediately via the StateFlow
     * (no waiting), and if any instance's cache is older than
     * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
     * StateFlow when the network fetch completes.
     *
     * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
     * and the global daily-refresh flag prevents cold-start double fetch.
     * This helper bypasses the daily flag because it runs per-instance — it's
     * meant for targeted "user is looking at this picker now" revalidation.
     */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) = triggerBackgroundRefreshIfStaleImpl(scope)

    /**
     * Refresh model lists for all enabled instances, at most once per
     * [MODELS_REFRESH_WINDOW_MS] rolling window.
     *
     * Mirrors iOS `refreshAllModelsIfNeeded()`. Called from
     * Application.onCreate AND on every foreground return, so a model a
     * provider enables while the app sits open appears on the next visit
     * rather than the next day.
     * Refreshes run in parallel; failures are logged but don't block other instances.
     */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) = refreshAllModelsIfNeededImpl(scope)

    // API Key management
    fun saveApiKey(instanceId: String, key: String) = saveApiKeyImpl(instanceId, key)

    fun loadApiKey(instanceId: String): String? = loadApiKeyImpl(instanceId)

    /**
     * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
     * use: the stored key, or "" for instances where an empty key is a valid
     * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
     * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
     * genuinely has no usable credential, so existing `?: return/continue`
     * call sites keep their skip semantics for everything else (notably OAuth
     * instances without a token, which must stay unauthenticated).
     */
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

    fun deleteApiKey(instanceId: String) = deleteApiKeyImpl(instanceId)

    // -- Import / Export --

    /**
     * [T-android-provider-export-oauth-token] OAuth manager for [instance],
     * covering EVERY OAuth provider type — including gemini / antigravity, which
     * OAuthManager.forInstance deliberately omits (it's tuned for the
     * login/logout/manual-bearer UI paths). Used only by the export/import
     * round-trip so we don't widen forInstance's shared behavior.
     */
    internal fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? = exportInstanceJSONImpl(instanceId)

    /**
     * [T-android-backup-secrets] Collect ONE instance's credentials for the
     * backup `secrets.json`, base64-encoded exactly as [exportInstanceJSON]
     * does (same keychain + OAuth-manager access paths, same field semantics).
     * Returns null when the instance carries no usable credential, so the
     * caller can skip empty entries — matching iOS `BackupSecretsCollector`'s
     * `if !secret.isEmpty` guard.
     */
    fun collectBackupProviderSecret(
        instance: ProviderInstance,
    ): BackupSecrets.ProviderSecret? = collectBackupProviderSecretImpl(instance)

    /**
     * [T-android-backup-secrets] Restore one instance's credentials from a
     * backup `secrets.json` entry. Returns true if any credential was WRITTEN,
     * false if the instance already had a key (kept) or the secret was empty —
     * this true/false is what the importer accumulates into
     * `providersRestored` vs `providersSkippedExisting` (the counts that drive
     * the restore-complete credentials message, iOS parity).
     *
     * Keep-existing policy mirrors iOS: an API key already on this device is
     * NOT overwritten, so restoring a backup onto its origin device reports
     * "kept" rather than "restored".
     */
    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean = restoreBackupProviderSecretImpl(secret)

    /**
     * [T-android-backup-restore-order] Non-destructive, ORDER-PRESERVING union
     * merge of a restored [remote] ProviderConfig into the live config. This is
     * the Android port of iOS `mergeProviderConfigFallback`
     * (BackupImporter+Categories.swift), including the fix `T-backup-restore-order`
     * (b33eb1ff6): the package decides POSITION for the items it carries, so a
     * restore reproduces the backed-up arrangement instead of re-sorting by
     * createdAt.
     *
     * Rules (identical to iOS):
     *  - instances / modelGroups: rebuilt in PACKAGE order. For each remote id,
     *    if the id already exists locally keep the LOCAL element (content wins),
     *    else take the remote element. Local-only ids are appended afterwards in
     *    their original relative order.
     *  - modelEntries: additive by id (append remote ids not present locally);
     *    order not significant (entries are looked up by id, ordered per group).
     *  - agentLoop bindings: set-union.
     *  - per-device pointers (default / voice / vision group ids, session
     *    bindings) are NOT part of ProviderConfig here and are untouched.
     *
     * Returns (beforeInstances, afterInstances) so the caller derives
     * imported = after-before and skipped = package.count - imported — the same
     * counting iOS uses (fix 93cad55ae: union-by-id already-present is SKIPPED,
     * not "updated").
     */
    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> = mergeBackupProviderConfigImpl(remote)

    /**
     * [T-android-backup-thinking-rules] Restore custom thinking rules by
     * id-keyed replace. Returns (written, skipped).
     *
     * Divergence from iOS, called out deliberately: iOS does `updated_at` LWW
     * (local equal-or-newer wins). Android's `provider_thinking_rules` table has
     * NO time columns, so there is nothing to compare — a rule already present
     * by id is left as-is (skipped), an absent one is inserted. This is the
     * closest faithful behaviour the local schema allows; the record's carried
     * createdAt/updatedAt are ignored on import.
     */
    /**
     * [T-android-backup-subagents] Merge the custom sub agents a backup package
     * carried in `data/sub_agents.jsonl` (iOS writes them only there). Rules in
     * [SubAgentRoster.mergeBackup]. Returns (written, skipped).
     */
    fun restoreBackupSubAgents(incoming: List<SubAgentDefinition>): Pair<Int, Int> = restoreBackupSubAgentsImpl(incoming)

    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = restoreBackupThinkingRulesImpl(rules)

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? = importInstanceJSONImpl(jsonStr)

    // -- Modality interop with iOS ----------------------------------------
    //
    // iOS encodes ModelModality as a single Int bitfield (OptionSet rawValue);
    // Android carries inputModalities / outputModalities as bare string lists
    // ("text" / "image" / "pdf" / "audio" / "video"). The export/import path
    // writes both encodings so the wire format is portable in either
    // direction without losing fidelity:
    //   - Android → Android: the native string lists round-trip exactly.
    //   - Android → iOS:    iOS reads `modalityOverride` Int and ignores
    //                       the unknown list keys (forward-compatible).
    //   - iOS → Android:    Android prefers the native list keys when
    //                       present (Android-original export); otherwise
    //                       decodes `modalityOverride` Int back into lists.
    //
    // Bit layout constants live at file scope above the class (Kotlin
    // forbids a second companion object, and ProviderRepository already
    // has one).

}
