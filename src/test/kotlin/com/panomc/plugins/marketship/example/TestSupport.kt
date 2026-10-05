package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.ShipmentLookup
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Recorded
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.spi.testkit.TestShippingContext
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The carrier id market stored for the shipment of every fixture. */
internal const val SHIPMENT_ID = "shp_1"

internal val API_KEY: String = TestContexts.secretMarker("apiKey")
internal val API_SECRET: String = TestContexts.secretMarker("apiSecret")

internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

internal fun eur(minor: Long) = Money(minor, "EUR")

/** A license check that never objects (a FREE build). */
internal val ALLOW = LicenseCheck { }

internal class LicenseDenied : RuntimeException("license denied (test)")

internal val DENY = LicenseCheck { throw LicenseDenied() }

/** Market's read-only view of this provider's shipments: exactly the shipments given. */
internal class FixedShipments(vararg shipments: ShipmentView) : ShipmentLookup {
    private val all = shipments.toList()

    override suspend fun byMerchantReference(reference: String): ShipmentView? = all.firstOrNull { it.merchantReference == reference }

    override suspend fun byCarrierReference(reference: String): ShipmentView? = all.firstOrNull { it.carrierReference == reference }

    override suspend fun byTrackingNumber(trackingNumber: String): ShipmentView? = all.firstOrNull { it.trackingNumber == trackingNumber }
}

internal fun shipmentView(
    id: Long = 9,
    carrierReference: String? = SHIPMENT_ID,
    status: ShipmentStatus = ShipmentStatus.LABEL_READY,
    providerData: JsonObject? = null
) = ShipmentView(
    id = id, merchantReference = "SHP-$id", carrierReference = carrierReference, trackingNumber = null, status = status, serviceCode = "standard",
    to = SampleData.address(), providerData = providerData, testMode = false, createdAt = TestContexts.START_MS
)

/**
 * One provider, one FakeGateway, one test context. [overrides] replace stored settings, [removed] drops keys (a missing
 * setting). The provider's endpoints always point at the fake; [modesSeen] records the `testMode` it asked for.
 *
 * The fake carrier hands out a numbered access token on `POST /v1/auth/token` ([authCalls] counts them) and answers 401 on
 * any call made with a token that is not (or no longer) valid, which is what [authed] wraps around a route handler.
 */
internal class Env(
    overrides: Map<String, Any?> = emptyMap(),
    removed: Set<String> = emptySet(),
    testMode: Boolean = false,
    license: LicenseCheck = ALLOW,
    timeoutMs: Long = 3_000,
    shipments: ShipmentLookup = FixedShipments(shipmentView())
) : AutoCloseable {
    val vertx: Vertx = Vertx.vertx()
    val gateway: FakeGateway = FakeGateway.start(vertx)
    val modesSeen = CopyOnWriteArrayList<Boolean>()
    val provider = ExampleProvider(license, { mode ->
        modesSeen.add(mode)
        ExampleEndpoints(gateway.baseUrl)
    }, timeoutMs)
    val values: Map<String, Any?> = (TestContexts.defaultValues(provider.settingsSchema()) + overrides).filterKeys { it !in removed }
    val ctx: TestShippingContext = TestContexts.shipping(provider.id, TestContexts.settings(values), vertx, testMode, shipments)

    val authCalls = AtomicInteger()
    val validTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    var tokenSeconds: Long = 3600

    init {
        gateway.on("POST", "/v1/auth/token") { r ->
            val body = JsonObject(r.bodyText())
            if (body.getString("apiKey") != values["apiKey"] || body.getString("apiSecret") != values["apiSecret"]) {
                errorReply(401, "invalid_credentials", "unknown key")
            } else {
                val token = "TOKEN-${authCalls.incrementAndGet()}-abcdef0123"
                validTokens.add(token)
                jsonReply(JsonObject().put("token", token).put("expiresIn", tokenSeconds))
            }
        }
    }

    /** Wraps [handler]: answers 401 unless the request carries a token this carrier issued. */
    fun authed(handler: (Recorded) -> Reply): (Recorded) -> Reply = { r ->
        val token = r.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || token !in validTokens) errorReply(401, "invalid_token", "token expired") else handler(r)
    }

    fun on(method: String, path: String, handler: (Recorded) -> Reply) {
        gateway.on(method, path, authed(handler))
    }

    /** The calls the carrier received, without the token exchange. */
    fun apiCalls(): List<Recorded> = gateway.requests.filter { it.path != "/v1/auth/token" }

    override fun close() {
        gateway.close()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }
}

internal inline fun <T> env(
    overrides: Map<String, Any?> = emptyMap(),
    removed: Set<String> = emptySet(),
    testMode: Boolean = false,
    license: LicenseCheck = ALLOW,
    timeoutMs: Long = 3_000,
    shipments: ShipmentLookup = FixedShipments(shipmentView()),
    block: (Env) -> T
): T = Env(overrides, removed, testMode, license, timeoutMs, shipments).use(block)

/** Runs [block], which must throw a [ProviderException]; returns it for further assertions. */
internal fun expectProviderError(code: ProviderErrorCode, block: suspend () -> Unit): ProviderException {
    try {
        blocking(block)
    } catch (e: ProviderException) {
        assertEquals(code, e.code, "wrong error code (message: ${e.message})")
        return e
    }
    fail<Unit>("expected ProviderException($code), nothing was thrown")
    throw IllegalStateException()
}

internal fun jsonReply(body: JsonObject, status: Int = 200) = Reply.json(body.encode(), status)

internal fun errorReply(status: Int, code: String, message: String) =
    Reply.json(JsonObject().put("error", JsonObject().put("code", code).put("message", message)).encode(), status)

/** What the carrier sends. */
internal object Carrier {
    fun event(id: String, status: String, time: Long = TestContexts.START_MS / 1000L, description: String? = null, location: String? = null): JsonObject =
        JsonObject().put("id", id).put("status", status).put("time", time).also {
            description?.let { d -> it.put("description", d) }
            location?.let { l -> it.put("location", l) }
        }

    fun shipment(
        id: String = SHIPMENT_ID,
        status: String = "in_transit",
        events: List<JsonObject> = listOf(event("ev_1", status)),
        trackingNumber: String? = "EC0001"
    ): JsonObject = JsonObject().put("id", id).put("reference", "SHP-9").put("status", status).put("events", JsonArray(events)).also {
        if (trackingNumber != null) it.put("trackingNumber", trackingNumber).put("trackingUrl", "https://track.examplecarrier.example/$trackingNumber")
    }

    fun rate(service: String = "standard", amount: Long = 590, currency: String = "EUR", rateId: String? = "rate_1"): JsonObject =
        JsonObject().put("service", service).put("name", service.replaceFirstChar { it.uppercase() }).put("carrier", "Example Post")
            .put("amount", amount).put("currency", currency).put("minDays", 2).put("maxDays", 4).put("expiresAt", TestContexts.START_MS / 1000L + 600)
            .put("includesTax", true).also { if (rateId != null) it.put("rateId", rateId) }

    /** The pending-label purchase answer, or one with an inline PDF when [label] is given. */
    fun purchased(label: ByteArray? = null, trackingNumber: String = "EC0001"): JsonObject =
        JsonObject().put("id", SHIPMENT_ID).put("status", if (label != null) "label_ready" else "purchased").put("trackingNumber", trackingNumber)
            .put("trackingUrl", "https://track.examplecarrier.example/$trackingNumber").put("carrier", "Example Post")
            .put("cost", JsonObject().put("amount", 590).put("currency", "EUR")).also {
                if (label != null) it.put("label", JsonObject().put("format", "pdf").put("data", java.util.Base64.getEncoder().encodeToString(label)))
            }

    /** A webhook as the carrier posts it: unsigned, and the same bytes for every state of the shipment. */
    fun webhook(shipmentId: String = SHIPMENT_ID, extra: JsonObject = JsonObject(), method: String = "POST"): InboundRequest {
        val body = JsonObject().put("event", "shipment.updated").put("shipmentId", shipmentId).mergeIn(extra).encode().toByteArray()
        return rawWebhook(body, method = method)
    }

    fun rawWebhook(body: ByteArray, contentType: String = "application/json", method: String = "POST"): InboundRequest = InboundRequest(
        kind = InboundKind.WEBHOOK, channel = "default", method = method, rawQuery = null, query = emptyMap(),
        headers = mapOf("content-type" to listOf(contentType)), contentType = contentType, body = body, remoteIp = "203.0.113.9",
        receivedAt = TestContexts.START_MS
    )
}

internal fun createRequest(
    merchantReference: String = "SHP-9",
    previousCarrierReference: String? = null,
    serviceCode: String? = "standard",
    rateRef: String? = "rate_1",
    parcels: List<com.panomc.plugins.market.spi.shipping.Parcel> = listOf(SampleData.parcel())
): CreateShipmentRequest {
    val base = SampleData.createShipmentRequest(previousCarrierReference)
    return CreateShipmentRequest(
        shipmentId = base.shipmentId, merchantReference = merchantReference, orderPublicId = base.orderPublicId, from = base.from, to = base.to,
        parcels = parcels, items = base.items, serviceCode = serviceCode, rateRef = rateRef, preferredLabelFormat = null,
        declaredValue = base.declaredValue, note = null, previousCarrierReference = previousCarrierReference, previousProviderData = null
    )
}
