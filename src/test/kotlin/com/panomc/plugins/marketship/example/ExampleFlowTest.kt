package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.Parcel
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Quote, create, label, cancel, track and the token cache, against the FakeGateway that speaks the protocol of
 * VERIFICATION.md: S-04, S-05, S-06, S-07 (calls), S-09, S-10.
 */
class ExampleFlowTest {
    private fun quoteRequest(parcels: List<Parcel> = listOf(SampleData.parcel()), serviceCode: String? = null) = QuoteRequest(
        from = SampleData.address("DE"), to = SampleData.address("FR"), parcels = parcels, items = listOf(SampleData.shipItem()),
        orderValue = eur(2000), currency = "EUR", serviceCode = serviceCode
    )

    /** Registers the happy path of the two create calls; [purchase] answers the second one. */
    private fun Env.createRoutes(purchase: () -> Reply = { jsonReply(Carrier.purchased("%PDF-1.4".toByteArray())) }) {
        on("POST", "/v1/shipments") { jsonReply(JsonObject().put("id", SHIPMENT_ID).put("status", "draft").put("reference", "SHP-9"), 201) }
        on("POST", "/v1/shipments/$SHIPMENT_ID/purchase") { purchase() }
    }

    // ---- S-04 quote -----------------------------------------------------------------------------------------------------

    @Test
    fun `S-04 quote returns every rate with its opaque rate id and the services that are unavailable`() = env<Unit> { env ->
        env.on("POST", "/v1/rates") {
            jsonReply(
                JsonObject().put("rates", JsonArray().add(Carrier.rate()).add(Carrier.rate("express", 1290, "EUR", "rate_2")))
                    .put("unavailable", JsonObject().put("economy", "not offered to France"))
            )
        }
        val result = blocking { env.provider.quote(env.ctx, quoteRequest()) }
        assertTrue(result.supported)
        assertEquals(listOf("standard", "express"), result.rates.map { it.serviceCode })
        assertEquals(listOf(Money(590, "EUR"), Money(1290, "EUR")), result.rates.map { it.price })
        assertEquals(listOf("rate_1", "rate_2"), result.rates.map { it.rateRef })
        assertEquals(mapOf("economy" to "not offered to France"), result.unavailable)

        val call = env.gateway.requestsTo("/v1/rates").single()
        assertTrue(call.header("Authorization")!!.startsWith("Bearer TOKEN-1-"))
        val body = JsonObject(call.bodyText())
        assertEquals("DE", body.getJsonObject("from").getString("country"))
        assertEquals("FR", body.getJsonObject("to").getString("country"))
        val parcel = body.getJsonArray("parcels").getJsonObject(0)
        assertEquals("0.5", parcel.getString("weightKg"))
        assertEquals(20, parcel.getInteger("lengthCm"))
        assertEquals(JsonObject().put("amount", 2000L).put("currency", "EUR"), body.getJsonObject("declaredValue"))
        assertFalse(body.containsKey("serviceCode"))
    }

    @Test
    fun `S-04 a service code narrows the question and an empty answer is no rates, not an error`() = env<Unit> { env ->
        env.on("POST", "/v1/rates") { jsonReply(JsonObject().put("rates", JsonArray())) }
        val result = blocking { env.provider.quote(env.ctx, quoteRequest(serviceCode = "express")) }
        assertTrue(result.supported)
        assertTrue(result.rates.isEmpty())
        assertEquals("express", JsonObject(env.gateway.requestsTo("/v1/rates").single().bodyText()).getString("serviceCode"))
    }

    @Test
    fun `S-04 an unusable rate is dropped and the usable ones stay`() = env<Unit> { env ->
        env.on("POST", "/v1/rates") {
            jsonReply(JsonObject().put("rates", JsonArray().add(Carrier.rate(currency = "ZZZ")).add(Carrier.rate("express", 700)).add("junk")))
        }
        assertEquals(listOf("express"), blocking { env.provider.quote(env.ctx, quoteRequest()) }.rates.map { it.serviceCode })
    }

    @Test
    fun `S-04 the default parcel of the settings fills a parcel without dimensions`() = env<Unit>(
        overrides = mapOf("defaultParcelLengthMm" to 300, "defaultParcelWidthMm" to 100, "defaultParcelHeightMm" to 100)
    ) { env ->
        env.on("POST", "/v1/rates") { jsonReply(JsonObject().put("rates", JsonArray())) }
        blocking { env.provider.quote(env.ctx, quoteRequest(listOf(Parcel(750, null, null, null)))) }
        val parcel = JsonObject(env.gateway.requestsTo("/v1/rates").single().bodyText()).getJsonArray("parcels").getJsonObject(0)
        assertEquals("0.8", parcel.getString("weightKg"))
        assertEquals(30, parcel.getInteger("lengthCm"))
        assertEquals(1, parcel.getInteger("desi"))
    }

    @Test
    fun `S-04 a timeout is GATEWAY_UNREACHABLE and retryable`() = env<Unit>(timeoutMs = 400) { env ->
        env.on("POST", "/v1/rates") { jsonReply(JsonObject()) }
        env.gateway.hang("/v1/rates")
        val e = expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.quote(env.ctx, quoteRequest()) }
        assertTrue(e.retryable)
    }

    @Test
    fun `S-04 server errors, rate limits and refusals map to the error table`() = env<Unit> { env ->
        env.on("POST", "/v1/rates") { errorReply(503, "maintenance", "later") }
        assertTrue(expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.quote(env.ctx, quoteRequest()) }.retryable)
        env.on("POST", "/v1/rates") { errorReply(429, "slow_down", "too many") }
        assertTrue(expectProviderError(ProviderErrorCode.RATE_LIMITED) { env.provider.quote(env.ctx, quoteRequest()) }.retryable)
        env.on("POST", "/v1/rates") { errorReply(422, "address_invalid", "no such street") }
        assertEquals("no such street", expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { env.provider.quote(env.ctx, quoteRequest()) }.adminMessage)
        env.on("POST", "/v1/rates") { errorReply(403, "ip_not_allowed", "nope") }
        expectProviderError(ProviderErrorCode.IP_NOT_ALLOWED) { env.provider.quote(env.ctx, quoteRequest()) }
        env.on("POST", "/v1/rates") { Reply.text("<html>challenge</html>", 200, "text/html") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.quote(env.ctx, quoteRequest()) }
        env.on("POST", "/v1/rates") { Reply.text("not json at all", 200) }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { env.provider.quote(env.ctx, quoteRequest()) }
        env.on("POST", "/v1/rates") { Reply.redirect("http://127.0.0.1:${env.gateway.port}/elsewhere") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { env.provider.quote(env.ctx, quoteRequest()) }
        assertTrue(env.gateway.requests.none { it.path == "/elsewhere" }, "a redirect is never followed")
    }

    @Test
    fun `S-04 a parcel without weight or more than one parcel is refused before any call`() = env<Unit> { env ->
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.quote(env.ctx, quoteRequest(listOf(Parcel(0, null, null, null)))) }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.quote(env.ctx, quoteRequest(listOf(SampleData.parcel(), SampleData.parcel()))) }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.quote(env.ctx, quoteRequest(emptyList())) }
        assertTrue(env.gateway.requests.isEmpty(), "nothing is sent for a request market must not make")
    }

    // ---- S-05 create ----------------------------------------------------------------------------------------------------

    @Test
    fun `S-05 create is a draft then a purchase and returns the label that came inline`() = env<Unit> { env ->
        env.createRoutes()
        val created = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Created
        assertEquals(SHIPMENT_ID, created.carrierReference)
        assertEquals("EC0001", created.trackingNumber)
        assertEquals("https://track.examplecarrier.example/EC0001", created.trackingUrl)
        assertEquals("Example Post", created.carrierName)
        assertEquals(Money(590, "EUR"), created.cost)
        assertEquals(ShipmentStatus.LABEL_READY, created.status)
        assertEquals(1, created.labels.size)
        assertEquals(LabelFormat.PDF, created.labels.single().format)
        assertEquals("%PDF-1.4", String(created.labels.single().bytes))
        assertEquals("pdf", created.providerData!!.getString("labelFormat"))

        val draft = env.gateway.requestsTo("/v1/shipments").single()
        assertEquals("draft-SHP-9", draft.header("Idempotency-Key"))
        val body = JsonObject(draft.bodyText())
        assertEquals("SHP-9", body.getString("reference"))
        assertEquals("standard", body.getString("serviceCode"))
        assertEquals("T-shirt", body.getJsonArray("items").getJsonObject(0).getString("name"))
        val purchase = env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/purchase").single()
        assertEquals("buy-SHP-9", purchase.header("Idempotency-Key"))
        val buy = JsonObject(purchase.bodyText())
        assertEquals("rate_1", buy.getString("rateId"))
        assertEquals("standard", buy.getString("serviceCode"))
        assertEquals("pdf", buy.getString("labelFormat"))
    }

    @Test
    fun `S-05 a purchase without an inline label is CREATED and the label comes from fetchLabel later`() = env<Unit> { env ->
        env.createRoutes { jsonReply(Carrier.purchased(null)) }
        val created = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Created
        assertEquals(ShipmentStatus.CREATED, created.status)
        assertTrue(created.labels.isEmpty())
        assertEquals("EC0001", created.trackingNumber)
    }

    @Test
    fun `S-05 creating the same merchantReference twice returns the same carrier reference and buys once`() = env<Unit> { env ->
        val drafts = ConcurrentHashMap<String, String>()
        val purchases = ConcurrentHashMap.newKeySet<String>()
        val draftCounter = AtomicInteger()
        env.on("POST", "/v1/shipments") { r ->
            val id = drafts.computeIfAbsent(r.header("Idempotency-Key")!!) { "shp_${draftCounter.incrementAndGet()}" }
            jsonReply(JsonObject().put("id", id).put("status", "draft"))
        }
        // the carrier charges once per idempotency key and answers a replay with the first answer
        env.gateway.on("POST", "/v1/shipments/shp_1/purchase", env.authed { r ->
            purchases.add(r.header("Idempotency-Key")!!)
            jsonReply(Carrier.purchased(null))
        })
        val first = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Created
        val second = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Created
        assertEquals(first.carrierReference, second.carrierReference)
        assertEquals(1, drafts.size, "the carrier saw one draft")
        assertEquals(setOf("buy-SHP-9"), purchases, "the carrier saw one purchase key")
        val keys = env.gateway.requestsTo("/v1/shipments").map { it.header("Idempotency-Key") }
        assertEquals(listOf("draft-SHP-9", "draft-SHP-9"), keys)
        // another shipment has its own keys
        env.on("POST", "/v1/shipments/shp_2/purchase") { jsonReply(Carrier.purchased(null)) }
        val other = blocking { env.provider.createShipment(env.ctx, createRequest(merchantReference = "SHP-10")) } as CreateShipmentResult.Created
        assertEquals("shp_2", other.carrierReference)
    }

    @Test
    fun `S-05 insufficient balance is Failed with the draft id, and the retry resumes at the purchase`() = env<Unit> { env ->
        val broke = java.util.concurrent.atomic.AtomicBoolean(true)
        env.createRoutes {
            if (broke.get()) errorReply(402, "insufficient_balance", "balance 1.20 EUR is below 5.90 EUR") else jsonReply(Carrier.purchased(null))
        }
        val failed = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Failed
        assertEquals(ShipmentErrorCode.INSUFFICIENT_BALANCE, failed.code)
        assertEquals(SHIPMENT_ID, failed.carrierReference)
        assertEquals("balance 1.20 EUR is below 5.90 EUR", failed.message)

        broke.set(false)
        val retry = blocking { env.provider.createShipment(env.ctx, createRequest(previousCarrierReference = failed.carrierReference)) }
        assertTrue(retry is CreateShipmentResult.Created)
        assertEquals(1, env.gateway.requestsTo("/v1/shipments").size, "the retry did not create a second draft")
        assertEquals(2, env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/purchase").size)
    }

    @Test
    fun `S-05 an invalid address is Failed(ADDRESS_INVALID) at the draft and nothing is bought`() = env<Unit> { env ->
        env.on("POST", "/v1/shipments") { errorReply(422, "address_invalid", "postal code does not exist") }
        val failed = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Failed
        assertEquals(ShipmentErrorCode.ADDRESS_INVALID, failed.code)
        assertNull(failed.carrierReference, "no object was created")
        assertEquals("postal code does not exist", failed.message)
        assertTrue(env.gateway.requests.none { it.path.endsWith("/purchase") })
    }

    @Test
    fun `S-05 other refusals of the purchase keep their meaning`() = env<Unit> { env ->
        val cases = mapOf(
            409 to ("rate_expired" to ShipmentErrorCode.RATE_EXPIRED),
            422 to ("weight_limit" to ShipmentErrorCode.WEIGHT_LIMIT),
            423 to ("service_unavailable" to ShipmentErrorCode.SERVICE_UNAVAILABLE),
            400 to ("brand_new_code" to ShipmentErrorCode.REJECTED)
        )
        for ((status, pair) in cases) {
            env.createRoutes { errorReply(status, pair.first, "refused: ${pair.first}") }
            val failed = blocking { env.provider.createShipment(env.ctx, createRequest()) } as CreateShipmentResult.Failed
            assertEquals(pair.second, failed.code, pair.first)
            assertEquals(SHIPMENT_ID, failed.carrierReference)
        }
    }

    @Test
    fun `S-05 an outcome that is not known throws, and the retry sends the same idempotency keys`() = env<Unit> { env ->
        val flaky = java.util.concurrent.atomic.AtomicBoolean(true)
        env.createRoutes { if (flaky.get()) errorReply(503, "maintenance", "try later") else jsonReply(Carrier.purchased(null)) }
        val e = expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.createShipment(env.ctx, createRequest()) }
        assertTrue(e.retryable)
        flaky.set(false)
        // market lost the draft id (the call threw), so it retries from the start with the same merchantReference
        assertTrue(blocking { env.provider.createShipment(env.ctx, createRequest()) } is CreateShipmentResult.Created)
        assertEquals(listOf("draft-SHP-9", "draft-SHP-9"), env.gateway.requestsTo("/v1/shipments").map { it.header("Idempotency-Key") })
        assertEquals(listOf("buy-SHP-9", "buy-SHP-9"), env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/purchase").map { it.header("Idempotency-Key") })
    }

    @Test
    fun `S-05 a draft answer without an id is refused and a purchase of an unknown draft is NOT_FOUND`() = env<Unit> { env ->
        env.on("POST", "/v1/shipments") { jsonReply(JsonObject().put("status", "draft")) }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { env.provider.createShipment(env.ctx, createRequest()) }
        expectProviderError(ProviderErrorCode.NOT_FOUND) { env.provider.createShipment(env.ctx, createRequest(previousCarrierReference = "shp_gone")) }
    }

    @Test
    fun `S-05 more than one parcel, no weight or a blank reference are refused before any call`() = env<Unit> { env ->
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.createShipment(env.ctx, createRequest(parcels = listOf(SampleData.parcel(), SampleData.parcel()))) }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.createShipment(env.ctx, createRequest(parcels = listOf(Parcel(0, 1, 1, 1)))) }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.createShipment(env.ctx, createRequest(merchantReference = " ")) }
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `S-05 the label format is the preferred one when the carrier has it, else the setting`() {
        env(overrides = mapOf("labelFormat" to "zpl")) { env ->
            env.createRoutes { jsonReply(Carrier.purchased(null)) }
            val html = createRequest().let {
                com.panomc.plugins.market.spi.shipping.CreateShipmentRequest(
                    it.shipmentId, it.merchantReference, it.orderPublicId, it.from, it.to, it.parcels, it.items, it.serviceCode, it.rateRef,
                    LabelFormat.HTML, it.declaredValue, it.note, null, null
                )
            }
            val created = blocking { env.provider.createShipment(env.ctx, html) } as CreateShipmentResult.Created
            assertEquals("zpl", JsonObject(env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/purchase").single().bodyText()).getString("labelFormat"))
            assertEquals("zpl", created.providerData!!.getString("labelFormat"))
        }
        env { env ->
            env.createRoutes { jsonReply(Carrier.purchased(null)) }
            val zpl = createRequest().let {
                com.panomc.plugins.market.spi.shipping.CreateShipmentRequest(
                    it.shipmentId, it.merchantReference, it.orderPublicId, it.from, it.to, it.parcels, it.items, it.serviceCode, it.rateRef,
                    LabelFormat.ZPL, it.declaredValue, it.note, null, null
                )
            }
            blocking { env.provider.createShipment(env.ctx, zpl) }
            assertEquals("zpl", JsonObject(env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/purchase").single().bodyText()).getString("labelFormat"))
        }
    }

    // ---- S-06 labels ----------------------------------------------------------------------------------------------------

    @Test
    fun `S-06 fetchLabel says notReady while the carrier prepares it and returns the document once it is there`() = env<Unit> { env ->
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        env.on("GET", "/v1/shipments/$SHIPMENT_ID/label") {
            if (ready.get()) Reply.bytes("%PDF-1.4 label".toByteArray(), 200, "application/pdf") else jsonReply(JsonObject().put("status", "pending"), 202)
        }
        val shipment = shipmentView(providerData = JsonObject().put("labelFormat", "pdf"))
        assertTrue(blocking { env.provider.fetchLabel(env.ctx, shipment) }.notReady)
        ready.set(true)
        val result = blocking { env.provider.fetchLabel(env.ctx, shipment) }
        assertFalse(result.notReady)
        assertEquals(LabelFormat.PDF, result.documents.single().format)
        assertEquals("%PDF-1.4 label", String(result.documents.single().bytes))
        assertEquals("format=pdf", env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/label").last().query)
    }

    @Test
    fun `S-06 a ZPL shipment is fetched as ZPL, a shipment without carrier reference has no label`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments/$SHIPMENT_ID/label") { Reply.bytes("^XA^XZ".toByteArray(), 200, "application/octet-stream") }
        val result = blocking { env.provider.fetchLabel(env.ctx, shipmentView(providerData = JsonObject().put("labelFormat", "zpl"))) }
        assertEquals(LabelFormat.ZPL, result.documents.single().format)
        assertEquals("format=zpl", env.gateway.requestsTo("/v1/shipments/$SHIPMENT_ID/label").single().query)
        val none = blocking { env.provider.fetchLabel(env.ctx, shipmentView(carrierReference = null)) }
        assertTrue(none.documents.isEmpty() && !none.notReady)
    }

    @Test
    fun `S-06 a label that is a maintenance page, unknown or too large is an error, never a document`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments/$SHIPMENT_ID/label") { Reply.text("<html>down</html>", 200, "text/html") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.fetchLabel(env.ctx, shipmentView()) }
        env.on("GET", "/v1/shipments/$SHIPMENT_ID/label") { errorReply(404, "not_found", "no such shipment") }
        expectProviderError(ProviderErrorCode.NOT_FOUND) { env.provider.fetchLabel(env.ctx, shipmentView()) }
        env.on("GET", "/v1/shipments/$SHIPMENT_ID/label") { Reply.bytes(ByteArray(ExampleMapper.MAX_LABEL_BYTES + 1), 200, "application/pdf") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { env.provider.fetchLabel(env.ctx, shipmentView()) }
    }

    // ---- S-09 cancel ----------------------------------------------------------------------------------------------------

    @Test
    fun `S-09 cancelShipment is cancelled when the carrier confirms`() = env<Unit> { env ->
        env.on("DELETE", "/v1/shipments/$SHIPMENT_ID") { jsonReply(JsonObject().put("id", SHIPMENT_ID).put("status", "cancelled")) }
        val result: CancelShipmentResult = blocking { env.provider.cancelShipment(env.ctx, shipmentView()) }
        assertTrue(result.cancelled && result.supported)
    }

    @Test
    fun `S-09 cancelShipment is refused with the carrier's reason, never assumed`() = env<Unit> { env ->
        env.on("DELETE", "/v1/shipments/$SHIPMENT_ID") { errorReply(409, "not_cancellable", "already picked up") }
        blocking { env.provider.cancelShipment(env.ctx, shipmentView()) }.also {
            assertFalse(it.cancelled)
            assertTrue(it.supported)
            assertEquals("already picked up", it.message)
        }
        env.on("DELETE", "/v1/shipments/$SHIPMENT_ID") { jsonReply(JsonObject().put("status", "in_transit")) }
        assertFalse(blocking { env.provider.cancelShipment(env.ctx, shipmentView()) }.cancelled, "a 2xx that does not say cancelled is not a cancellation")
        env.on("DELETE", "/v1/shipments/$SHIPMENT_ID") { errorReply(404, "not_found", "unknown") }
        assertFalse(blocking { env.provider.cancelShipment(env.ctx, shipmentView()) }.cancelled)
        val before = env.gateway.requests.size
        blocking { env.provider.cancelShipment(env.ctx, shipmentView(carrierReference = null)) }.also { assertFalse(it.cancelled) }
        assertEquals(before, env.gateway.requests.size, "no call without a carrier reference")
    }

    @Test
    fun `S-09 a carrier that is down while cancelling is an error, not a refusal`() = env<Unit> { env ->
        env.on("DELETE", "/v1/shipments/$SHIPMENT_ID") { errorReply(500, "boom", "down") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { env.provider.cancelShipment(env.ctx, shipmentView()) }
    }

    // ---- S-07 track (calls) ---------------------------------------------------------------------------------------------

    @Test
    fun `S-07 track asks for the batch in one call and maps every shipment to its market id`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments") {
            jsonReply(
                JsonObject().put(
                    "shipments",
                    JsonArray()
                        .add(Carrier.shipment("shp_1", "delivered", listOf(Carrier.event("a", "picked_up", 1760000000), Carrier.event("b", "delivered", 1760050000))))
                        .add(Carrier.shipment("shp_2", "in_transit", listOf(Carrier.event("c", "teleported", 1760000000)), trackingNumber = null))
                        .add(Carrier.shipment("shp_unknown", "delivered"))
                )
            )
        }
        val updates = blocking {
            env.provider.track(env.ctx, TrackRequest(listOf(shipmentView(9, "shp_1"), shipmentView(10, "shp_2"), shipmentView(11, null))))
        }
        // shp_2 has no event the plugin understands and no tracking number: it tells nothing; shp_unknown was not asked for
        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals(9L, (update.target as ShipmentTarget.Id).shipmentId)
        assertEquals(listOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED), update.events.map { it.status })
        assertEquals(listOf("a", "b"), update.events.map { it.eventId })
        assertEquals("EC0001", update.trackingNumber)
        val call = env.gateway.requestsTo("/v1/shipments").single()
        assertEquals("ids=shp_1,shp_2", java.net.URLDecoder.decode(call.query, "UTF-8"), "one call, the ids of the shipments that have a carrier reference")
    }

    @Test
    fun `S-07 track respects trackBatchSize and makes no call when there is nothing to ask`() = env<Unit> { env ->
        val caps = env.provider.capabilities(env.ctx.settings)
        assertTrue(caps.trackingPull)
        assertEquals(20, caps.trackBatchSize)
        env.on("GET", "/v1/shipments") { jsonReply(JsonObject().put("shipments", JsonArray())) }
        val exactly = (1..caps.trackBatchSize).map { shipmentView(it.toLong(), "shp_$it") }
        assertTrue(blocking { env.provider.track(env.ctx, TrackRequest(exactly)) }.isEmpty())
        assertEquals(1, env.gateway.requestsTo("/v1/shipments").size)
        env.gateway.clearRequests()
        val tooMany = (1..caps.trackBatchSize + 1).map { shipmentView(it.toLong(), "shp_$it") }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { env.provider.track(env.ctx, TrackRequest(tooMany)) }
        assertTrue(blocking { env.provider.track(env.ctx, TrackRequest(emptyList())) }.isEmpty())
        assertTrue(blocking { env.provider.track(env.ctx, TrackRequest(listOf(shipmentView(1, null)))) }.isEmpty())
        assertTrue(env.gateway.requests.isEmpty())
    }

    // ---- S-10 token cache -----------------------------------------------------------------------------------------------

    private fun Env.account() = on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }

    @Test
    fun `S-10 the second call reuses the cached token`() = env<Unit> { env ->
        env.account()
        blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }
        blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) }
        blocking { env.provider.balance(env.ctx) }
        assertEquals(1, env.authCalls.get())
        assertEquals(setOf("Bearer TOKEN-1-abcdef0123"), env.gateway.requestsTo("/v1/account").map { it.header("Authorization") }.toSet())
        // the token is in ctx.state under a key that holds no secret
        val key = env.ctx.stateStore.keys.single()
        assertTrue(key.startsWith("token:live:"))
        assertFalse(key.contains(API_KEY) || key.contains(API_SECRET))
        // the token request carried the credentials in its body, not in a header or the URL
        val auth = env.gateway.requestsTo("/v1/auth/token").single()
        assertEquals(API_KEY, JsonObject(auth.bodyText()).getString("apiKey"))
        assertNull(auth.header("Authorization"))
        assertEquals("", auth.query)
    }

    @Test
    fun `S-10 an expired token is replaced and a live one is not`() = env<Unit> { env ->
        env.tokenSeconds = 100 // cached for 100 - 60 = 40 seconds
        env.account()
        blocking { env.provider.balance(env.ctx) }
        env.ctx.advance(39_000)
        blocking { env.provider.balance(env.ctx) }
        assertEquals(1, env.authCalls.get(), "39 s: still cached")
        env.ctx.advance(2_000)
        blocking { env.provider.balance(env.ctx) }
        assertEquals(2, env.authCalls.get(), "41 s: refreshed")
        assertEquals(listOf("Bearer TOKEN-1-abcdef0123", "Bearer TOKEN-1-abcdef0123", "Bearer TOKEN-2-abcdef0123"), env.gateway.requestsTo("/v1/account").map { it.header("Authorization") })
    }

    @Test
    fun `S-10 a token the carrier revoked early is replaced once and the call is repeated`() = env<Unit> { env ->
        env.account()
        blocking { env.provider.balance(env.ctx) }
        env.validTokens.clear()
        blocking { env.provider.balance(env.ctx) }
        assertEquals(2, env.authCalls.get())
        assertEquals(3, env.gateway.requestsTo("/v1/account").size, "first ok, then 401, then ok with the new token")
        assertEquals("Bearer TOKEN-2-abcdef0123", env.gateway.requestsTo("/v1/account").last().header("Authorization"))
    }

    @Test
    fun `S-10 a carrier that keeps answering 401 is AUTHENTICATION after exactly one repeat`() = env<Unit> { env ->
        env.gateway.on("GET", "/v1/account") { errorReply(401, "invalid_token", "no") }
        expectProviderError(ProviderErrorCode.AUTHENTICATION) { env.provider.balance(env.ctx) }
        assertEquals(2, env.gateway.requestsTo("/v1/account").size)
        assertEquals(2, env.authCalls.get())
    }

    @Test
    fun `S-10 wrong credentials are AUTHENTICATION and no other call is made`() = env<Unit>(overrides = mapOf("apiSecret" to "a-different-secret-value")) { env ->
        // the fake carrier knows the values this Env was built with, so give it the real ones
        env.gateway.on("POST", "/v1/auth/token") { errorReply(401, "invalid_credentials", "unknown key") }
        env.account()
        expectProviderError(ProviderErrorCode.AUTHENTICATION) { env.provider.balance(env.ctx) }
        assertTrue(env.apiCalls().isEmpty())
        assertTrue(env.ctx.stateStore.keys.isEmpty(), "nothing is cached for failed credentials")
    }

    @Test
    fun `S-10 a token another caller stored while this one was fetching wins the compareAndSet`() = env<Unit> { env ->
        val key = ExampleSettings(env.ctx.settings).tokenStateKey(false)
        env.gateway.on("POST", "/v1/auth/token") {
            // the competitor finishes first: its token is in the state before ours can be written
            env.validTokens.add("COMPETITOR-TOKEN")
            runBlocking { env.ctx.stateStore.put(key, "COMPETITOR-TOKEN", 3000) }
            env.validTokens.add("OUR-LOSING-TOKEN")
            jsonReply(JsonObject().put("token", "OUR-LOSING-TOKEN").put("expiresIn", 3600))
        }
        env.account()
        blocking { env.provider.balance(env.ctx) }
        assertEquals("Bearer COMPETITOR-TOKEN", env.gateway.requestsTo("/v1/account").single().header("Authorization"))
        assertEquals("COMPETITOR-TOKEN", runBlocking { env.ctx.stateStore.get(key) }, "the state holds exactly one token")
    }

    @Test
    fun `S-10 two callers refreshing at once end up with one token in the state`() = env<Unit> { env ->
        env.account()
        val key = ExampleSettings(env.ctx.settings).tokenStateKey(false)
        runBlocking {
            coroutineScope {
                (1..6).map { async { env.provider.balance(env.ctx) } }.forEach { it.await() }
            }
        }
        val stored = runBlocking { env.ctx.stateStore.get(key) }
        assertNotNull(stored)
        val used = env.gateway.requestsTo("/v1/account").map { it.header("Authorization") }.toSet()
        assertEquals(setOf("Bearer $stored"), used, "every call used the token that won")
    }

    @Test
    fun `S-10 the state key depends on the credentials and the mode and holds no secret`() = env<Unit> { env ->
        val a = ExampleSettings(TestContexts.settings(mapOf("apiKey" to "key-aaaa", "apiSecret" to "secret-aaaa")))
        val b = ExampleSettings(TestContexts.settings(mapOf("apiKey" to "key-bbbb", "apiSecret" to "secret-aaaa")))
        val c = ExampleSettings(TestContexts.settings(mapOf("apiKey" to "key-aaaa", "apiSecret" to "secret-cccc")))
        assertEquals(a.tokenStateKey(false), a.tokenStateKey(false))
        val keys = setOf(a.tokenStateKey(false), a.tokenStateKey(true), b.tokenStateKey(false), c.tokenStateKey(false))
        assertEquals(4, keys.size)
        assertTrue(keys.none { it.contains("key-aaaa") || it.contains("secret-aaaa") })
        assertNotNull(env)
    }

    // ---- the rest of the SPI that the carrier offers ---------------------------------------------------------------------

    @Test
    fun `listServices maps the carrier's services`() = env<Unit> { env ->
        env.on("GET", "/v1/services") {
            jsonReply(
                JsonObject().put(
                    "services",
                    JsonArray().add(JsonObject().put("code", "standard").put("name", "Standard").put("carrier", "Example Post"))
                        .add(JsonObject().put("code", "world").put("name", "World").put("international", true)).add(JsonObject().put("name", "no code"))
                )
            )
        }
        val services = blocking { env.provider.listServices(env.ctx) }
        assertEquals(listOf("standard", "world"), services.map { it.code })
        assertEquals("Example Post", services[0].carrierName)
        assertTrue(services[1].international)
    }

    @Test
    fun `balance reads the prepaid balance and is null when the carrier does not state one`() = env<Unit> { env ->
        env.on("GET", "/v1/account") { jsonReply(JsonObject().put("balance", JsonObject().put("amount", 12345).put("currency", "EUR"))) }
        assertEquals(Money(12345, "EUR"), blocking { env.provider.balance(env.ctx) })
        env.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
        assertNull(blocking { env.provider.balance(env.ctx) })
    }

    @Test
    fun `resolveAddress returns the normalised address or the carrier's complaint`() = env<Unit> { env ->
        env.on("POST", "/v1/addresses/validate") {
            jsonReply(JsonObject().put("valid", true).put("normalized", JsonObject().put("name", "Steve Miner").put("line1", "Example Strasse 1").put("postalCode", "10115").put("city", "Berlin")))
        }
        val ok = blocking { env.provider.resolveAddress(env.ctx, SampleData.address()) }
        assertTrue(ok.supported && ok.valid)
        assertEquals("Example Strasse 1", ok.normalized!!.line1)
        assertEquals("Steve", ok.normalized!!.firstName)
        assertEquals("steve@example.com", ok.normalized!!.email, "what the carrier does not return stays as it was")
        env.on("POST", "/v1/addresses/validate") { jsonReply(JsonObject().put("valid", false).put("message", "unknown street")) }
        val bad = blocking { env.provider.resolveAddress(env.ctx, SampleData.address()) }
        assertFalse(bad.valid)
        assertEquals("unknown street", bad.message)
    }
}
