package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Common interface for OpenAI SSE stream parsers (Chat Completions vs Responses API).
 */
internal interface OpenAISseStreamSession {
    val sseEventCount: Int
    val contentLen: Int
    val reasoningLen: Int
    suspend fun onDoneSentinel(emit: suspend (LLMStreamChunk) -> Unit)
    suspend fun processEvent(event: JSONObject, emit: suspend (LLMStreamChunk) -> Unit): Boolean
    suspend fun onStreamEnd(emit: suspend (LLMStreamChunk) -> Unit)
}

internal object OpenAINetworkTransport {
    /**
     * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte budget:
     * response HEADERS must arrive within this window after upload completes.
     * Does NOT bound the SSE body — a flowing stream stays unlimited.
     */
    const val STREAM_TTFB_TIMEOUT_MS = 120_000L

    /**
     * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
     * phase (call start → requestBodyEnd).
     */
    const val STREAM_UPLOAD_CAP_MS = 120_000L

    fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    /**
     * Execute a streaming OkHttp request under the split upload/TTFB watchdog.
     * On timeout, cancels the call and evicts ONLY this call's physical socket.
     */
    fun executeStreamingCall(
        client: OkHttpClient,
        request: Request,
        watchdogScope: CoroutineScope,
        ttfbTimeoutMs: Long = STREAM_TTFB_TIMEOUT_MS,
        uploadCapMs: Long = STREAM_UPLOAD_CAP_MS,
    ): Pair<Call, Response> {
        val watchState = CallWatchState()
        val call = client.newCall(
            request.newBuilder().tag(CallWatchState::class.java, watchState).build(),
        )
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = watchdogScope.launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= uploadCapMs) {
                        timedOutPhase = "upload"
                        break
                    }
                } else {
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= ttfbTimeoutMs) {
                        timedOutPhase = "ttfb"
                        break
                    }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) {
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") ttfbTimeoutMs / 1000 else uploadCapMs / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks execute().
                }
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${ttfbTimeoutMs / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        return call to response
    }
}

/**
 * [T-android-ttfb-upload-split / #188] Per-call state the streaming TTFB
 * watchdog shares with [OkHttpNetTraceListener].
 */
internal class CallWatchState {
    /** Set by requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    /** Physical connection serving this call, captured at connectionAcquired. */
    val connection = java.util.concurrent.atomic.AtomicReference<Connection?>(null)
}

/**
 * [T-android-openai-codex-timeout]
 * Network-leg trace listener for OpenAIProvider's OkHttpClient.
 */
internal class OkHttpNetTraceListener : EventListener() {
    private val tag = "OkHttpNetTrace"
    private val t0 = System.nanoTime()
    private fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
    private fun callTag(call: Call): String {
        val id = System.identityHashCode(call).toString(16)
        return "call#$id"
    }

    override fun callStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}",
        )
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectStart host=${url.host}",
        )
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}",
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsStart host=$domainName",
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}",
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectStart target=$inetSocketAddress proxy=$proxy",
        )
    }

    override fun secureConnectStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsStart",
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}",
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol",
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}",
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        call.request().tag(CallWatchState::class.java)?.connection?.set(connection)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionAcquired conn#$conn route=${connection.route()} proto=${connection.protocol()}",
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionReleased conn#$conn",
        )
    }

    override fun requestHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersStart",
        )
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersEnd",
        )
    }

    override fun requestBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyStart",
        )
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        call.request().tag(CallWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyEnd bytes=$byteCount",
        )
    }

    override fun responseHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersStart (server first byte)",
        )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersEnd status=${response.code} proto=${response.protocol}",
        )
    }

    override fun responseBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyStart",
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyEnd bytes=$byteCount",
        )
    }

    override fun callEnd(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callEnd",
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms callFailed err=${ioe.javaClass.simpleName}:${ioe.message}",
        )
    }

    override fun canceled(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms canceled",
        )
    }
}
