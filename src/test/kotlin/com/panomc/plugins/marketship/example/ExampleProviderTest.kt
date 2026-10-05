package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** S-02 (identity, descriptor, schema, capabilities), S-11 (redaction, license guard, test mode, hosts) and settings handling. */
class ExampleProviderTest {
    private fun quote(env: Env) = QuoteRequest(SampleData.address("DE"), SampleData.address("FR"), listOf(SampleData.parcel()), listOf(SampleData.shipItem()), eur(2000), "EUR", null)

    // ---- S-02 -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-02 id, plugin id, descriptor and logo`() = env<Unit> { env ->
        val provider = env.provider
        assertTrue(Regex("^[a-z0-9-]{2,29}$").matches(provider.id), "provider id '${provider.id}'")
        // 16 section 2.2: a shipping plugin id is pano-plugin-market-shipping-<slug>, never the payment shape pano-plugin-market-<slug>
        assertEquals("pano-plugin-market-shipping-${provider.id}", ExampleTexts.PLUGIN_ID)
        assertTrue(ExampleTexts.PLUGIN_ID.startsWith("pano-plugin-market-shipping-"), "plugin id '${ExampleTexts.PLUGIN_ID}' must start with pano-plugin-market-shipping-")
        assertTrue(provider.id.length in 2..20, "shipping slug '${provider.id}' must be 2 to 20 characters")
        val properties = java.util.Properties().also { props -> File("gradle.properties").inputStream().use { props.load(it) } }
        assertEquals(properties.getProperty("pluginId"), ExampleTexts.PLUGIN_ID, "ExampleTexts.PLUGIN_ID must equal pluginId of gradle.properties")
        assertEquals(ExampleTexts.PLUGIN_ID, JsonObject(File("store/store.json").readText()).getString("id"), "store resource id must equal the plugin id")
        assertTrue(ExampleTexts.PLUGIN_ID.length <= 48, "plugin id '${ExampleTexts.PLUGIN_ID}' is longer than the 48 characters of the store")
        val descriptor = provider.descriptor
        assertTrue(descriptor.icon.startsWith("fa-"), "icon is a FontAwesome class")
        assertEquals("global", descriptor.region)
        assertNotNull(descriptor.docsUrl)
        val logo = descriptor.logo
        assertNotNull(logo, "logo.png is read from the plugin's own resources")
        assertEquals("image/png", logo!!.contentType)
        assertTrue(logo.bytes.size in 1..65_536, "logo is ${logo.bytes.size} bytes")
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), logo.bytes.take(4).map { it.toInt() and 0xFF }, "PNG signature")
        assertEquals(descriptor.verification, Verification.valueOf(JsonObject(resource("/verification.json")).getString("level")))
    }

    @Test
    fun `S-02 the schema names both credentials as required secrets, the webhook url, the sender address and the default parcel`() = env<Unit> { env ->
        val schema = env.provider.settingsSchema()
        schema.toJson()
        assertEquals(setOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.API_SECRET), schema.secretKeys)
        assertTrue(schema.field(ExampleSettings.Keys.API_KEY)!!.required)
        assertTrue(schema.field(ExampleSettings.Keys.API_SECRET)!!.required)
        assertNotNull(schema.field(ExampleSettings.Keys.WEBHOOK_URL)!!.readonly)
        for (key in SenderKeys.ADDRESS + SenderKeys.PARCEL) assertNotNull(schema.field(key), "reserved key $key")
        assertEquals(listOf("pdf", "zpl"), schema.field(ExampleSettings.Keys.LABEL_FORMAT)!!.options.map { it.value })
        assertEquals("pdf", schema.field(ExampleSettings.Keys.LABEL_FORMAT)!!.default)
        assertTrue(schema.actions.any { it.id == ExampleSettings.Keys.ACTION_TEST_CONNECTION })
    }

    @Test
    fun `S-02 capabilities state what the example carrier does, with empty settings and without I-O`() = env<Unit> { env ->
        val c = env.provider.capabilities(TestContexts.settings())
        assertTrue(c.rateQuote && c.createShipment && c.cancel && c.trackingPull && c.trackingPush && c.addressResolve && c.prepaidBalance)
        assertEquals(setOf(LabelFormat.PDF, LabelFormat.ZPL), c.labelFormats)
        assertFalse(c.webhookSigned, "the webhook is unsigned: handleInbound re-fetches (03 section 9)")
        assertEquals(WebhookSetup.MANUAL_URL, c.webhookSetup)
        assertEquals(TestModeSupport.FLAG, c.testMode)
        assertEquals(20, c.trackBatchSize)
        assertEquals(1, c.maxParcels)
        assertTrue(env.gateway.requests.isEmpty())
    }

    // ---- configuration --------------------------------------------------------------------------------------------------

    @Test
    fun `a missing credential is a CONFIGURATION error on every path that needs it and nothing is sent`() {
        for (key in listOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.API_SECRET)) {
            env(removed = setOf(key)) { env ->
                val create = createRequest()
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.quote(env.ctx, quote(env)) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.createShipment(env.ctx, create) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.fetchLabel(env.ctx, shipmentView()) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.cancelShipment(env.ctx, shipmentView()) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.track(env.ctx, TrackRequest(listOf(shipmentView()))) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.listServices(env.ctx) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.balance(env.ctx) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.resolveAddress(env.ctx, SampleData.address()) }
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.runAction(env.ctx, "test-connection", JsonObject()) }
                assertTrue(env.gateway.requests.isEmpty(), "nothing is sent without credentials ($key)")
            }
        }
    }

    // ---- S-11 -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-11 no secret setting value and no access token reaches a log line, an exchange record or an error text`() = env<Unit> { env ->
        val echo = "key $API_KEY and secret $API_SECRET are wrong"
        // the carrier repeats the credentials back in every error it sends
        env.on("POST", "/v1/rates") { errorReply(422, "invalid_request", echo) }
        env.on("POST", "/v1/shipments") { errorReply(500, "boom", echo) }
        env.on("GET", "/v1/shipments/shp_1") { errorReply(500, "boom", echo) }
        env.on("GET", "/v1/account") { errorReply(403, "forbidden", echo) }
        env.on("DELETE", "/v1/shipments/shp_1") { errorReply(409, "not_cancellable", echo) }
        val messages = ArrayList<String>()
        fun record(block: suspend () -> Any?) {
            try {
                blocking { block() }
            } catch (e: ProviderException) {
                messages += listOfNotNull(e.message, e.adminMessage)
            }
        }
        record { env.provider.quote(env.ctx, quote(env)) }
        record { env.provider.createShipment(env.ctx, createRequest()) }
        record { env.provider.track(env.ctx, TrackRequest(listOf(shipmentView()))) }
        record { env.provider.runAction(env.ctx, "test-connection", JsonObject()) }
        record { env.provider.validateSettings(env.ctx, env.ctx.settings) }
        record { env.provider.handleInbound(env.ctx, Carrier.webhook()) }
        messages += listOfNotNull(blocking { env.provider.cancelShipment(env.ctx, shipmentView()) }.message)

        assertTrue(messages.isNotEmpty())
        val tokens = env.validTokens.toList()
        assertTrue(tokens.isNotEmpty())
        val everything = env.ctx.recordedLog.everything() + "\n" + messages.joinToString("\n")
        assertFalse(everything.contains(API_KEY), "the API key leaked")
        assertFalse(everything.contains(API_SECRET), "the API secret leaked")
        for (token in tokens) assertFalse(everything.contains(token), "the access token $token leaked")
        assertTrue(everything.contains("***"), "the echoed secrets were replaced")
        val exchanges = env.ctx.recordedLog.exchanges
        assertTrue(exchanges.isNotEmpty(), "outbound calls are logged")
        assertTrue(exchanges.none { (it.request ?: "").contains("Authorization", ignoreCase = true) })
        // the token call is logged without its body (it holds the credentials) and without the token it returned
        val auth = exchanges.first { it.channel == "auth" }
        assertTrue((auth.request ?: "").matches(Regex("POST /v1/auth/token \\(\\d+ chars\\)")), "the token request is logged without its body: ${auth.request}")
        assertTrue((auth.response ?: "").contains("\"token\":\"***\""), "the token answer is logged as ***: ${auth.response}")
        // the buyer's name, phone and address stay out of the exchange records
        assertTrue(exchanges.none { (it.request ?: "").contains("Example Street") || (it.request ?: "").contains("+49") })
    }

    @Test
    fun `S-11 a label document is logged as its size, never as its content`() = env<Unit> { env ->
        env.on("GET", "/v1/shipments/shp_1/label") { com.panomc.plugins.market.spi.testkit.Reply.bytes("%PDF-SECRET-CONTENT".toByteArray(), 200, "application/pdf") }
        blocking { env.provider.fetchLabel(env.ctx, shipmentView()) }
        val logged = env.ctx.recordedLog.exchanges.first { it.channel == "label" }
        assertEquals("<19 bytes application/pdf>", logged.response)
        assertFalse(env.ctx.recordedLog.everything().contains("SECRET-CONTENT"))
    }

    @Test
    fun `S-11 every entry point that spends money or moves goods asks the license first and nothing reaches the carrier`() = env<Unit>(license = DENY) { env ->
        val ctx = env.ctx
        val p = env.provider
        val calls: Map<String, suspend () -> Any?> = mapOf(
            "quote" to { p.quote(ctx, quote(env)) },
            "createShipment" to { p.createShipment(ctx, createRequest()) },
            "handleInbound" to { p.handleInbound(ctx, Carrier.webhook()) },
            "track" to { p.track(ctx, TrackRequest(listOf(shipmentView()))) }
        )
        for ((name, call) in calls) assertThrows(LicenseDenied::class.java, { blocking { call() } }, "$name must throw the license failure")
        assertTrue(env.gateway.requests.isEmpty(), "the carrier saw: ${env.gateway.requests}")
        // pure and descriptive entry points still work without a license
        assertNotNull(p.settingsSchema())
        assertNotNull(p.capabilities(ctx.settings))
        assertNotNull(p.descriptor)
    }

    @Test
    fun `S-11 test mode selects the sandbox host, live selects the live host`() {
        assertEquals(ExampleEndpoints.SANDBOX_API, ExampleEndpoints.of(true).api)
        assertEquals(ExampleEndpoints.LIVE_API, ExampleEndpoints.of(false).api)
        assertTrue(ExampleEndpoints.SANDBOX_API != ExampleEndpoints.LIVE_API)
        for (testMode in listOf(true, false)) {
            env(testMode = testMode) { env ->
                env.on("GET", "/v1/account") { jsonReply(JsonObject()) }
                blocking { env.provider.balance(env.ctx) }
                assertEquals(setOf(testMode), env.modesSeen.toSet(), "the provider asked for the endpoints of testMode=$testMode (token call included)")
                assertTrue(env.ctx.stateStore.keys.single().startsWith(if (testMode) "token:test:" else "token:live:"), "a sandbox token is never reused live")
            }
        }
    }

    @Test
    fun `S-11 no host literal outside ExampleEndpoints`() {
        val dir = File("src/main/kotlin/com/panomc/plugins")
        assertTrue(dir.isDirectory, "run the tests from the project directory")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/license/") && it.name != "ExampleEndpoints.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!isComment && Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(code)) "${file.name}:${i + 1}: $code" else null
                }
            }.toList()
        assertTrue(offenders.isEmpty(), "host literals outside ExampleEndpoints.kt:\n" + offenders.joinToString("\n"))
    }

    // ---- settings handling ------------------------------------------------------------------------------------------------

    @Test
    fun `validateSettings accepts working credentials and reports a bad key on the key field`() = env<Unit> { env ->
        env.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
        assertTrue(blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.ok)

        // the carrier refuses the credentials at the token call
        env.gateway.on("POST", "/v1/auth/token") { errorReply(401, "invalid_credentials", "bad key") }
        env.ctx.advance(10_000_000) // the cached token has expired
        blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.also {
            assertFalse(it.ok)
            assertEquals(setOf(ExampleSettings.Keys.API_KEY), it.fieldErrors.keys)
            assertEquals(ExampleTexts.errorAuthentication, it.fieldErrors.getValue(ExampleSettings.Keys.API_KEY))
        }

        env.gateway.on("POST", "/v1/auth/token") { errorReply(503, "maintenance", "down") }
        blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.also {
            assertFalse(it.ok)
            assertTrue(it.fieldErrors.isEmpty())
            assertEquals(ExampleTexts.errorUnreachable, it.message)
        }
    }

    @Test
    fun `validateSettings names every missing credential without calling the carrier`() = env<Unit> { env ->
        val result = blocking { env.provider.validateSettings(env.ctx, TestContexts.settings(mapOf("apiSecret" to "  "))) }
        assertFalse(result.ok)
        assertEquals(setOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.API_SECRET), result.fieldErrors.keys)
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `validateSettings checks the values typed into the form, not the stored ones`() = env<Unit> { env ->
        env.on("GET", "/v1/account") { jsonReply(JsonObject()) }
        val candidate = TestContexts.settings(mapOf("apiKey" to "candidate-key-123456", "apiSecret" to "candidate-secret-123456"))
        // the fake carrier knows the stored credentials only: the candidate must reach the token call
        blocking { env.provider.validateSettings(env.ctx, candidate) }.also { assertFalse(it.ok) }
        val auth = JsonObject(env.gateway.requestsTo("/v1/auth/token").single().bodyText())
        assertEquals("candidate-key-123456", auth.getString("apiKey"))
    }

    @Test
    fun `the test-connection action answers a message and refuses unknown actions`() = env<Unit> { env ->
        env.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
        (blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) } as ActionResult.Message).also {
            assertTrue(it.success)
            assertEquals(ExampleTexts.connectionOk, it.text)
        }
        env.gateway.on("GET", "/v1/account") { errorReply(500, "boom", "down") }
        assertFalse((blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) } as ActionResult.Message).success)
        expectProviderError(ProviderErrorCode.UNSUPPORTED) { env.provider.runAction(env.ctx, "import-catalog", JsonObject()) }
    }

    private fun resource(path: String): String = javaClass.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }
}
