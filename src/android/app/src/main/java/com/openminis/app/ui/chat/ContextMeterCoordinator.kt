package com.openminis.app.ui.chat

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/** Pairs outbound estimates with provider reports and restores per-session calibration. */
internal class ContextMeterCoordinator(private val logTag: String) {
    private val ratios = mutableMapOf<String, Double>()
    private var lastLearnedRatio: Double? = null
    private var sessionId: String? = null
    var fixedTokens = 0
    var dispatchEstimate = 0
        private set
    var dispatchRatio = 1.0
        private set
    private var dispatchPredicted = 0
    private var dispatchModelId: String? = null
    private var dispatchWindow = 0
    private var lastCalibratedPair: Triple<Int, Int, String?>? = null
    var sentPastExtrapolatedLimit = false

    data class Measurement(
        val history: Int,
        val fixed: Int,
        val ratio: Double,
        val source: String,
        val measured: Int,
    )

    fun ratioFor(modelId: String?): Double =
        ContextSizeMeter.ratioFor(modelId, ratios, lastLearnedRatio)

    fun measure(history: List<LLMMessage>, modelId: String?, override: Double?): Measurement {
        val historyTokens = ContextSizeMeter.estimateTokens(history)
        val ratio = override ?: ratioFor(modelId)
        val source = if (override != null) "forced"
            else ContextSizeMeter.ratioSource(modelId, ratios, lastLearnedRatio)
        return Measurement(historyTokens, fixedTokens, ratio, source,
            ContextSizeMeter.calibrated(historyTokens + fixedTokens, ratio))
    }

    /** Record our estimate of the request being sent; returns its calibrated size. */
    fun recordDispatch(history: List<LLMMessage>, modelId: String?, window: Int): Int {
        dispatchModelId = modelId
        dispatchWindow = window
        dispatchEstimate = ContextSizeMeter.estimateTokens(history) + fixedTokens
        dispatchRatio = ratioFor(modelId)
        dispatchPredicted = ContextSizeMeter.calibrated(dispatchEstimate, dispatchRatio)
        return dispatchPredicted
    }

    /** Pair the provider's count with the recorded estimate, for the model that served it. */
    fun calibrate(reportedTokens: Int, selectedModelId: String?): Boolean {
        val sample = ContextSizeMeter.calibrationRatio(reportedTokens, dispatchEstimate) ?: return false
        val model = dispatchModelId ?: selectedModelId
        // [T-ctx-ratio-smoothing] Blend into the model's OWN history only; a
        // borrowed ratio is not evidence about this model.
        val own = model?.let { ratios[it] }
        val updated = ContextSizeMeter.smoothed(own, sample)
        if (model != null) ratios[model] = updated
        lastLearnedRatio = updated
        lastCalibratedPair = Triple(dispatchEstimate, fixedTokens, model)
        // [CtxMeter] actual — the prediction made at dispatch vs what the
        // provider counted; `err` is how wrong this request's decision could be.
        val err = if (dispatchPredicted > 0) (dispatchPredicted - reportedTokens) * 100.0 / reportedTokens else 0.0
        AppLogger.info(
            logTag,
            "[CtxMeter] actual model=${model ?: "?"} predicted=$dispatchPredicted reported=$reportedTokens " +
                "err=${"%+.1f".format(err)}% estimate=$dispatchEstimate sample=${"%.3f".format(sample)} " +
                "ratio=${"%.3f".format(dispatchRatio)}→${"%.3f".format(updated)}" +
                if (own == null) " (first own sample)" else "",
        )
        return true
    }

    /**
     * A provider rejected the request as too long — ground truth that we
     * under-read it (a borrowed ratio after a model switch, a legacy session,
     * content the estimator under-reads). Raise this model's ratio so the retry
     * or next send compacts instead of being rejected again. Never lowers it.
     */
    fun noteOverflow(detail: String, selectedModelId: String?, selectedWindow: () -> Int?) {
        val model = dispatchModelId ?: selectedModelId
        val window = dispatchWindow.takeIf { dispatchModelId != null && it > 0 }
            ?: selectedWindow() ?: 0
        val current = ratioFor(model)
        val requested = ContextSizeMeter.requestedTokens(detail)
        val raised = ContextSizeMeter.ratioAfterOverflow(current, dispatchEstimate, requested, window)
        if (model != null) ratios[model] = raised
        lastLearnedRatio = raised
        // [T-ctx-valve-rearm] The ratio now rests on the provider's own count,
        // not an extrapolation, so the uncalibrated send-once is spent: firing
        // it would re-send the request that was just rejected.
        sentPastExtrapolatedLimit = true
        AppLogger.warning(
            logTag,
            "[CtxMeter] rejected predicted=$dispatchPredicted — provider rejected the request as too long — calibration model=${model ?: "?"} " +
                "${"%.2f".format(current)} → ${"%.2f".format(raised)} (estimated=$dispatchEstimate " +
                "statedTokens=${requested ?: "none"} window=$window); the next attempt will compact",
        )
    }

    /**
     * Re-derive calibration from the session's persisted usage rows: the last
     * row carrying BOTH its report and our estimate of the same request. No raw
     * size is carried over, so a compaction or revert done since cannot make
     * the next decision stale. Returns true when a pair was found.
     */
    /**
     * Reloading the SAME session keeps what this view model already learned: a
     * revert reloads through loadSession, and a ratio raised by a provider
     * rejection exists only in memory (a rejected request stamps no pair), so
     * wiping it let the retry go out uncompacted and be rejected again —
     * measured on the iOS twin of this code. In-memory values are never older
     * than the rows', so they win; a different session starts from scratch.
     */
    fun seed(sid: String, usageJsons: List<String>): Boolean {
        val learned = if (sid.isNotEmpty() && sessionId == sid) {
            ContextSizeMeter.CalibrationState(ratios.toMap(), lastLearnedRatio, fixedTokens)
        } else {
            null
        }
        // [T-ctx-ratio-smoothing] Replay rows oldest → newest through the same
        // rule the live path uses, so a reload reproduces the smoothed ratio.
        val seeded = ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))
        val state = learned?.let { seeded.carryingOver(it) } ?: seeded
        ratios.clear()
        ratios.putAll(state.ratios)
        lastLearnedRatio = state.lastLearned
        fixedTokens = state.fixedTokens
        dispatchEstimate = 0
        dispatchModelId = null
        dispatchWindow = 0
        lastCalibratedPair = null
        // [T-ctx-valve-spend-survives-retry] The valve's spent state belongs to
        // the session's evidence like the ratios above: a different session
        // starts armed, the same session (revert/reload) keeps a rejection's spend.
        if (learned == null) sentPastExtrapolatedLimit = false
        sessionId = sid
        AppLogger.info(
            logTag,
            "[CtxMeter] seed session=${sid.take(8)} samples=${seeded.samples} keptInMemory=${learned?.ratios?.size ?: 0} " +
                "ratios=${state.ratios.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${"%.3f".format(it.value)}" }}",
        )
        return seeded.samples > 0 || !learned?.ratios.isNullOrEmpty()
    }

    /** `"estimatedRequestTokens":…,"estimatedFixedTokens":…` for the usage row, or "". Same keys as iOS StoredTokenUsage. */
    fun calibrationJsonFields(): String = lastCalibratedPair?.let { (est, fixed, model) ->
        val modelField = model?.let { ""","calibrationModelId":${JSONObject.quote(it)}""" } ?: ""
        ""","estimatedRequestTokens":$est,"estimatedFixedTokens":$fixed$modelField"""
    } ?: ""
}
