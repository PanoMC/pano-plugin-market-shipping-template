package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.CancellationException

/**
 * Every outbound HTTP call of the plugin (through `ctx.http`), with the error mapping of spec 16 section 8.5. It makes no
 * business decision: it returns what the carrier answered, or throws a [ProviderException] for everything that is not an
 * answer (transport failure, credentials, rate limit, server error, a challenge page, a body that is not JSON).
 *
 * Authentication: the API key and secret are exchanged for a bearer token (`POST /v1/auth/token`) that lives in
 * `ctx.state` until shortly before it expires. A 401 on a call drops that token, fetches one and repeats the call once.
 */
class ExampleClient(
    private val endpointsFor: (Boolean) -> ExampleEndpoints,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /** A parsed answer. [adminMessage] is the carrier's own text (or the start of the body), with secrets removed. */
    class Response(val status: Int, val json: JsonObject, val adminMessage: String?, val bytes: ByteArray = ByteArray(0), val contentType: String? = null) {
        val ok: Boolean get() = status in 200..299

        val errorCode: String? get() = json.obj("error")?.str("code")

        /** 2xx returns itself; 404 is `NOT_FOUND`; everything else the carrier refused is `GATEWAY_REJECTED`. */
        fun requireSuccess(): Response {
            if (ok) return this
            if (status == 404) throw ProviderException(ProviderErrorCode.NOT_FOUND, "the carrier does not know the object", adminMessage)
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the carrier rejected the request (HTTP $status)", adminMessage)
        }
    }

    // ---- the carrier's calls -----------------------------------------------------------------------------------------

    suspend fun services(ctx: ProviderContext, settings: ExampleSettings): JsonObject =
        call(ctx, settings, "services", HttpMethod.GET, "/v1/services").requireSuccess().json

    suspend fun rates(ctx: ProviderContext, settings: ExampleSettings, body: JsonObject): JsonObject =
        call(ctx, settings, "rates", HttpMethod.POST, "/v1/rates", body).requireSuccess().json

    /** The raw answer: a 4xx refusal (`address_invalid`, ...) is the caller's business ([ExampleProvider.createShipment]). */
    suspend fun createDraft(ctx: ProviderContext, settings: ExampleSettings, body: JsonObject, idempotencyKey: String): Response =
        call(ctx, settings, "create-shipment", HttpMethod.POST, "/v1/shipments", body, idempotencyKey)

    /** The raw answer: `402 insufficient_balance`, `409 rate_expired`, ... are the caller's business. */
    suspend fun purchase(ctx: ProviderContext, settings: ExampleSettings, shipmentId: String, body: JsonObject, idempotencyKey: String): Response =
        call(ctx, settings, "purchase-shipment", HttpMethod.POST, "/v1/shipments/${encode(shipmentId)}/purchase", body, idempotencyKey)

    /** Null when the carrier does not know the shipment. */
    suspend fun getShipment(ctx: ProviderContext, settings: ExampleSettings, shipmentId: String): JsonObject? {
        val response = call(ctx, settings, "get-shipment", HttpMethod.GET, "/v1/shipments/${encode(shipmentId)}")
        return if (response.status == 404) null else response.requireSuccess().json
    }

    /** At most [ExampleProvider.TRACK_BATCH_SIZE] ids. */
    suspend fun getShipments(ctx: ProviderContext, settings: ExampleSettings, ids: List<String>): JsonObject =
        call(ctx, settings, "track", HttpMethod.GET, "/v1/shipments", query = mapOf("ids" to ids.joinToString(","))).requireSuccess().json

    /** 200 with the document, 202 while it is being prepared; the raw answer, [Response.bytes] holds the document. */
    suspend fun label(ctx: ProviderContext, settings: ExampleSettings, shipmentId: String, format: String): Response =
        call(ctx, settings, "label", HttpMethod.GET, "/v1/shipments/${encode(shipmentId)}/label", query = mapOf("format" to format), binary = true)

    /** The raw answer: 409 `not_cancellable` is a refusal, 404 an unknown shipment. */
    suspend fun cancel(ctx: ProviderContext, settings: ExampleSettings, shipmentId: String): Response =
        call(ctx, settings, "cancel-shipment", HttpMethod.DELETE, "/v1/shipments/${encode(shipmentId)}")

    suspend fun validateAddress(ctx: ProviderContext, settings: ExampleSettings, body: JsonObject): JsonObject =
        call(ctx, settings, "validate-address", HttpMethod.POST, "/v1/addresses/validate", body).requireSuccess().json

    /** Cheapest authenticated call: used by `validateSettings`, the test-connection action and `balance`. */
    suspend fun account(ctx: ProviderContext, settings: ExampleSettings): JsonObject =
        call(ctx, settings, "account", HttpMethod.GET, "/v1/account").requireSuccess().json

    // ---- authentication ----------------------------------------------------------------------------------------------

    /**
     * The cached token, or a fresh one. [staleToken] is a token the carrier just refused: it is never returned again.
     * Two callers refreshing at the same moment race on `compareAndSet`; the loser uses the winner's token, so the
     * state never holds two.
     */
    private suspend fun token(ctx: ProviderContext, settings: ExampleSettings, staleToken: String?): String {
        val key = settings.tokenStateKey(ctx.testMode)
        val cached = ctx.state.get(key)
        if (cached != null && cached != staleToken) return cached
        val fresh = fetchToken(ctx, settings)
        val ttl = (fresh.second - TOKEN_SAFETY_SECONDS).coerceAtLeast(1L)
        if (ctx.state.compareAndSet(key, cached, fresh.first, ttl)) return fresh.first
        val winner = ctx.state.get(key)
        return if (winner != null && winner != staleToken) winner else fresh.first
    }

    private suspend fun fetchToken(ctx: ProviderContext, settings: ExampleSettings): Pair<String, Long> {
        val body = JsonObject().put("apiKey", settings.apiKey).put("apiSecret", settings.apiSecret)
        val response = send(ctx, settings, "auth", HttpMethod.POST, "/v1/auth/token", body, null, emptyMap(), null, binary = false, tolerate401 = false, extraSecrets = emptyList())
        if (!response.ok) {
            if (response.status == 400 || response.status == 422) throw ProviderException(ProviderErrorCode.AUTHENTICATION, "the carrier rejected the credentials", response.adminMessage)
            response.requireSuccess()
        }
        val token = response.json.str("token")
            ?: throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the carrier answer has no token", response.adminMessage)
        return token to (response.json.long("expiresIn") ?: DEFAULT_TOKEN_SECONDS)
    }

    // ---- the one HTTP path -------------------------------------------------------------------------------------------

    private suspend fun call(
        ctx: ProviderContext,
        settings: ExampleSettings,
        channel: String,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
        query: Map<String, String> = emptyMap(),
        binary: Boolean = false
    ): Response {
        var token = token(ctx, settings, null)
        var response = send(ctx, settings, channel, method, path, body, idempotencyKey, query, token, binary, tolerate401 = true, extraSecrets = listOf(token))
        if (response.status == 401) {
            // The token may have been revoked or expired early: replace it once, then the answer stands.
            token = token(ctx, settings, token)
            response = send(ctx, settings, channel, method, path, body, idempotencyKey, query, token, binary, tolerate401 = false, extraSecrets = listOf(token))
        }
        return response
    }

    private suspend fun send(
        ctx: ProviderContext,
        settings: ExampleSettings,
        channel: String,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        idempotencyKey: String?,
        query: Map<String, String>,
        token: String?,
        binary: Boolean,
        tolerate401: Boolean,
        extraSecrets: List<String>
    ): Response {
        val secrets = settings.secretValues() + extraSecrets.filter { it.length >= 4 }
        val url = endpointsFor(ctx.testMode).api + path
        // No redirect is followed: the Authorization header must never travel to a host the carrier sent us to.
        val request = ctx.http.requestAbs(method, url).timeout(timeoutMs).followRedirects(false)
            .putHeader("Accept", if (binary) "*/*" else "application/json")
        if (token != null) request.putHeader("Authorization", "Bearer $token")
        query.forEach { (name, value) -> request.addQueryParam(name, value) }
        if (idempotencyKey != null) request.putHeader("Idempotency-Key", idempotencyKey)

        val started = ctx.now()
        val response = try {
            if (body != null) request.sendJsonObject(body).coAwait() else request.send().coAwait()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.log.exchange(channel, describe(method, path, body), "transport error: ${e.javaClass.simpleName}", null, ctx.now() - started)
            throw ProviderException(
                ProviderErrorCode.GATEWAY_UNREACHABLE, "the carrier could not be reached (${e.javaClass.simpleName})",
                redact(e.message, secrets), retryable = true, cause = e
            )
        }

        val status = response.statusCode()
        val raw = response.body()?.bytes ?: ByteArray(0)
        val contentType = response.getHeader("content-type")?.lowercase().orEmpty()
        val binaryOk = binary && status in 200..299 && !contentType.contains("text/html") && !contentType.contains("json")
        val text = if (binaryOk) "" else String(raw, Charsets.UTF_8)
        // A document is never copied into the log: only its size (the base64 of a label would fill the record).
        val logged = if (binaryOk) "<${raw.size} bytes $contentType>" else redact(text.take(LOG_LIMIT), secrets)
        ctx.log.exchange(channel, describe(method, path, body), if (path == "/v1/auth/token") redactToken(logged) else logged, status, ctx.now() - started)

        if (binaryOk) return Response(status, JsonObject(), null, raw, contentType)

        // A challenge or maintenance page where JSON was expected: the carrier is effectively unreachable.
        if (contentType.contains("text/html") || text.trimStart().startsWith("<")) {
            throw ProviderException(
                ProviderErrorCode.GATEWAY_UNREACHABLE, "the carrier answered with an HTML page (HTTP $status)",
                redact(text.take(ADMIN_LIMIT), secrets), retryable = true
            )
        }
        val parsed: JsonObject? = try {
            if (text.isBlank()) JsonObject() else JsonObject(text)
        } catch (e: Exception) {
            null
        }
        val message = redact(parsed?.obj("error")?.str("message") ?: text.take(ADMIN_LIMIT), secrets)?.takeIf { it.isNotEmpty() }
        val code = parsed?.obj("error")?.str("code")
        if (code == "ip_not_allowed") throw ProviderException(ProviderErrorCode.IP_NOT_ALLOWED, "the carrier does not accept this server's IP address", message)
        if (status == 401 && tolerate401) return Response(401, parsed ?: JsonObject(), message)
        if (status == 401 || status == 403 || code == "invalid_credentials") throw ProviderException(ProviderErrorCode.AUTHENTICATION, "the carrier rejected the credentials", message)
        if (status == 429) throw ProviderException(ProviderErrorCode.RATE_LIMITED, "the carrier rate limit was hit", message, retryable = true)
        if (status >= 500) throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "the carrier failed (HTTP $status)", message, retryable = true)
        if (parsed == null && status != 404) {
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the carrier answer is not JSON (HTTP $status)", redact(text.take(ADMIN_LIMIT), secrets))
        }
        return Response(status, parsed ?: JsonObject(), message)
    }

    /**
     * One line for `ProviderLog.exchange`: method, path and the size of the JSON body. The body is not logged: it holds
     * the buyer's name, phone and address (and, for the token call, the credentials).
     */
    private fun describe(method: HttpMethod, path: String, body: JsonObject?): String =
        "${method.name()} $path" + (body?.let { " (${it.encode().length} chars)" } ?: "")

    private fun redact(text: String?, secrets: List<String>): String? {
        var out = text ?: return null
        for (secret in secrets) out = out.replace(secret, "***")
        return out
    }

    /** The token answer holds the new token: log that an answer arrived, not its value. */
    private fun redactToken(text: String?): String? = text?.replace(Regex("(\"token\"\\s*:\\s*\")[^\"]*(\")"), "$1***$2")

    private fun encode(segment: String): String = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val LOG_LIMIT = 2_000
        private const val ADMIN_LIMIT = 500
        private const val DEFAULT_TOKEN_SECONDS = 300L

        /** The token is dropped this long before the carrier would refuse it. */
        private const val TOKEN_SAFETY_SECONDS = 60L
    }
}
