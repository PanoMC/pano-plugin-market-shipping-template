package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.testkit.FailedCreateScenario
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.ShippingProviderContractTest
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.spi.testkit.UnsignedWebhookScenario
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * S-01: the shared contract of 03 section 9 against the example provider. Every check must run: none may be skipped, which is
 * why an unsigned-webhook scenario (the example takes a trigger webhook) and a failed-create scenario (the example buys in two
 * steps) are both supplied.
 */
class ExampleContractTest : ShippingProviderContractTest() {
    private val gatewayHolder = lazy { FakeGateway.start(vertx).also { register(it) } }

    private val carrierState = AtomicInteger(0)
    private val purchaseFails = AtomicBoolean(false)

    private fun register(g: FakeGateway) {
        // The carrier hands out one token and accepts it; every other route here is open to that token.
        g.on("POST", "/v1/auth/token") { Reply.json("""{"token":"contract-token-1234","expiresIn":3600}""") }
        g.on("GET", "/v1/account") { Reply.json("""{"id":"acct_1"}""") }
        g.on("GET", "/v1/services") { Reply.json("""{"services":[{"code":"standard","name":"Standard"}]}""") }
        g.on("GET", "/v1/shipments/shp_1") {
            // state 0: in transit, state 1: delivered: the webhook bytes never change, the carrier's answer does
            val status = if (carrierState.get() == 0) "in_transit" else "delivered"
            Reply.json(
                Carrier.shipment(status = status, events = listOf(Carrier.event("ev_${carrierState.get()}", status, 1_760_000_000L + carrierState.get() * 1000))).encode()
            )
        }
        g.on("POST", "/v1/shipments") { Reply.json("""{"id":"shp_1","status":"draft"}""", 201) }
        g.on("POST", "/v1/shipments/shp_1/purchase") {
            if (purchaseFails.getAndSet(false)) Reply.json("""{"error":{"code":"insufficient_balance","message":"balance too low"}}""", 402)
            else Reply.json(Carrier.purchased(null).encode())
        }
    }

    override fun createProvider(): ShippingProvider = ExampleProvider(ALLOW, { ExampleEndpoints(gatewayHolder.value.baseUrl) })

    override val gatewayIsFake: Boolean get() = true

    private fun contextOf(provider: ShippingProvider): ShippingContext =
        TestContexts.shipping(provider.id, TestContexts.settings(settingValues(provider)), vertx, shipments = FixedShipments(shipmentView()))

    override fun unsignedWebhookScenario(provider: ShippingProvider): UnsignedWebhookScenario {
        val gateway = gatewayHolder.value
        return UnsignedWebhookScenario(contextOf(provider), gateway, Carrier.webhook()) { state -> carrierState.set(state) }
    }

    override fun failedCreateScenario(provider: ShippingProvider): FailedCreateScenario {
        val gateway = gatewayHolder.value
        return FailedCreateScenario(
            context = contextOf(provider), gateway = gateway,
            request = createRequest(), armFailure = { purchaseFails.set(true) }
        )
    }

    override fun close() {
        if (gatewayHolder.isInitialized()) gatewayHolder.value.close()
        super.close()
    }
}
