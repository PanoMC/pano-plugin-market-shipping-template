package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.testkit.Reply
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S-08: the tracking webhook of Example Carrier is unsigned. The body only says which shipment changed; the state comes
 * from a fresh read, and a webhook that is not about one of market's shipments, or that cannot be confirmed, changes nothing.
 */
class ExampleInboundTest {
    private fun Env.shipmentAnswer(status: String, eventId: String = "ev_1", time: Long = 1_760_000_000L) {
        on("GET", "/v1/shipments/$SHIPMENT_ID") { jsonReply(Carrier.shipment(status = status, events = listOf(Carrier.event(eventId, status, time)))) }
    }

    @Test
    fun `S-08 the state comes from the re-fetch and the webhook body is not believed`() = env<Unit> { env ->
        env.shipmentAnswer("in_transit")
        // the body claims "delivered" and a tracking number; the carrier says in transit
        val body = Carrier.webhook(extra = JsonObject().put("status", "delivered").put("trackingNumber", "FAKE-999"))
        val result = blocking { env.provider.handleInbound(env.ctx, body) }
        assertTrue(result.verified)
        assertEquals(200, result.reply.status)
        val update = result.updates.single()
        assertEquals(listOf(ShipmentStatus.IN_TRANSIT), update.events.map { it.status })
        assertEquals("EC0001", update.trackingNumber)
        assertEquals(SHIPMENT_ID, (update.target as ShipmentTarget.CarrierReference).reference)
        // the request log shows the outbound read before any update was returned
        val call = env.apiCalls().single()
        assertEquals("GET", call.method)
        assertEquals("/v1/shipments/$SHIPMENT_ID", call.path)
        assertTrue(call.header("Authorization")!!.startsWith("Bearer "))
    }

    @Test
    fun `S-08 identical bodies with different carrier states each apply their own state and set no eventKey`() = env<Unit> { env ->
        val request = Carrier.webhook()
        env.shipmentAnswer("in_transit", "ev_1", 1_760_000_000L)
        val first = blocking { env.provider.handleInbound(env.ctx, request) }
        env.shipmentAnswer("delivered", "ev_2", 1_760_050_000L)
        val second = blocking { env.provider.handleInbound(env.ctx, request) }
        assertEquals(listOf(ShipmentStatus.IN_TRANSIT), first.updates.flatMap { u -> u.events.map { it.status } })
        assertEquals(listOf(ShipmentStatus.DELIVERED), second.updates.flatMap { u -> u.events.map { it.status } })
        assertNull(first.eventKey)
        assertNull(second.eventKey, "the body is the same for every state, so no key could tell two deliveries apart")
        assertEquals(2, env.apiCalls().size)
    }

    @Test
    fun `S-08 a webhook for a shipment market does not know costs no outbound call`() = env<Unit>(shipments = FixedShipments()) { env ->
        env.shipmentAnswer("delivered")
        val result = blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }
        assertTrue(result.verified)
        assertTrue(result.updates.isEmpty())
        assertTrue(env.gateway.requests.isEmpty(), "a stranger with the URL must not make this server call the carrier")
    }

    @Test
    fun `S-08 a shipment id that is not a plain id never reaches a URL`() = env<Unit> { env ->
        for (bad in listOf("../account", "shp_1/purchase", "shp 1", "shp_1?x=1", "", "a".repeat(65))) {
            val result = blocking { env.provider.handleInbound(env.ctx, Carrier.webhook(shipmentId = bad)) }
            assertFalse(result.verified, "'$bad'")
            assertTrue(result.updates.isEmpty())
            assertEquals(400, result.reply.status)
        }
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `S-08 an event that is not a shipment update, and a body that is not an object, are never applied`() = env<Unit> { env ->
        val other = Carrier.rawWebhook(JsonObject().put("event", "shipment.created").put("shipmentId", SHIPMENT_ID).encode().toByteArray())
        blocking { env.provider.handleInbound(env.ctx, other) }.also {
            assertTrue(it.verified && it.updates.isEmpty())
            assertEquals(200, it.reply.status)
        }
        for (garbage in listOf("", "null", "[]", "{", "\"x\"", "12")) {
            val r = blocking { env.provider.handleInbound(env.ctx, Carrier.rawWebhook(garbage.toByteArray())) }
            assertTrue(r.updates.isEmpty(), garbage)
            assertEquals(400, r.reply.status, garbage)
        }
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `S-08 only POST is accepted and an oversized body is refused before it is parsed`() = env<Unit> { env ->
        val get = blocking { env.provider.handleInbound(env.ctx, Carrier.webhook(method = "GET")) }
        assertFalse(get.verified)
        assertEquals(405, get.reply.status)
        val big = blocking { env.provider.handleInbound(env.ctx, Carrier.rawWebhook(ByteArray(64 * 1024 + 1) { ' '.code.toByte() })) }
        assertFalse(big.verified)
        assertEquals(400, big.reply.status)
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `S-08 when the state cannot be confirmed nothing is reported and the carrier is asked to deliver again`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments/$SHIPMENT_ID") { errorReply(503, "maintenance", "later") }
        val result = blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }
        assertFalse(result.verified)
        assertTrue(result.updates.isEmpty())
        assertEquals(503, result.reply.status)
        assertTrue(env.ctx.recordedLog.everything().contains("re-fetch failed"))
    }

    @Test
    fun `S-08 an answer about another shipment, a 404 and an answer with nothing in it change nothing`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments/$SHIPMENT_ID") { jsonReply(Carrier.shipment(id = "shp_other")) }
        blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }.also { assertTrue(it.verified && it.updates.isEmpty()) }
        env.on("GET", "/v1/shipments/$SHIPMENT_ID") { errorReply(404, "not_found", "gone") }
        blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }.also { assertTrue(it.verified && it.updates.isEmpty()) }
        env.on("GET", "/v1/shipments/$SHIPMENT_ID") { jsonReply(JsonObject().put("id", SHIPMENT_ID)) }
        blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }.also { assertTrue(it.verified && it.updates.isEmpty()) }
        env.on("GET", "/v1/shipments/$SHIPMENT_ID") { Reply.text("<html>maintenance</html>", 200, "text/html") }
        blocking { env.provider.handleInbound(env.ctx, Carrier.webhook()) }.also { assertFalse(it.verified); assertTrue(it.updates.isEmpty()) }
    }

    @Test
    fun `S-08 without credentials the webhook fails with CONFIGURATION, never with an update`() = env<Unit>(removed = setOf("apiKey")) { env ->
        expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.handleInbound(env.ctx, Carrier.webhook()) }
        assertTrue(env.gateway.requests.isEmpty())
    }
}
