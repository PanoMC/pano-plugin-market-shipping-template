package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderAsset
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.shipping.AddressField
import com.panomc.plugins.market.spi.shipping.AddressResolution
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.LabelDocument
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.LabelResult
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.QuoteResult
import com.panomc.plugins.market.spi.shipping.SenderAddress
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.ShippingService
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.spi.shipping.defaultParcel
import com.panomc.plugins.market.spi.shipping.senderAddress
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The imaginary carrier "Example Carrier" (provider id `example`). Every SPI method is: guard, read settings, call the
 * client, map, return (16 section 8.1). Capabilities: rates, a two-step shipment (draft, then the paid purchase), PDF and ZPL
 * labels (inline or fetched later), cancel, pull tracking in batches of 20, push tracking through an UNSIGNED webhook that is
 * only a trigger (the provider re-reads the shipment before it reports anything), a prepaid balance and address validation.
 *
 * [endpoints] is the test seam (point the provider at a FakeGateway); [timeoutMs] is the per-request timeout.
 */
class ExampleProvider(
    private val license: LicenseCheck,
    endpoints: ((Boolean) -> ExampleEndpoints)? = null,
    timeoutMs: Long = ExampleClient.DEFAULT_TIMEOUT_MS
) : ShippingProvider {
    private val client = ExampleClient(endpoints ?: { testMode -> ExampleEndpoints.of(testMode) }, timeoutMs)

    override val id: String = "example"

    override val descriptor: ProviderDescriptor =
        ProviderDescriptor(ExampleTexts.displayName, ExampleTexts.description, "fa-solid fa-truck-fast").also {
            it.logo = loadLogo()
            it.color = "#0d9488"
            it.region = "global"
            it.docsUrl = ExampleEndpoints.DOCS_URL
            it.verification = Verification.UNVERIFIED // must equal src/test/resources/verification.json (VerificationLevelTest)
        }

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        group(ExampleSettings.Keys.GROUP_CREDENTIALS, ExampleTexts.groupCredentials)
        group(ExampleSettings.Keys.GROUP_SENDER, ExampleTexts.groupSender)
        group(ExampleSettings.Keys.GROUP_OPTIONS, ExampleTexts.groupOptions)

        secret(ExampleSettings.Keys.API_KEY) {
            label = ExampleTexts.apiKeyLabel
            help = ExampleTexts.apiKeyHelp
            required = true
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        secret(ExampleSettings.Keys.API_SECRET) {
            label = ExampleTexts.apiSecretLabel
            help = ExampleTexts.apiSecretHelp
            required = true
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        webhookUrl(ExampleSettings.Keys.WEBHOOK_URL) {
            label = ExampleTexts.webhookUrlLabel
            help = ExampleTexts.webhookUrlHelp
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        senderAddress(ExampleSettings.Keys.GROUP_SENDER)
        defaultParcel(ExampleSettings.Keys.GROUP_SENDER)
        select(ExampleSettings.Keys.LABEL_FORMAT) {
            label = ExampleTexts.labelFormatLabel
            help = ExampleTexts.labelFormatHelp
            default = "pdf"
            group = ExampleSettings.Keys.GROUP_OPTIONS
            option("pdf", ExampleTexts.labelFormatPdf)
            option("zpl", ExampleTexts.labelFormatZpl)
        }
        action(ExampleSettings.Keys.ACTION_TEST_CONNECTION) {
            label = ExampleTexts.testConnectionLabel
        }
    }

    override fun capabilities(settings: ProviderSettings): ShippingCapabilities = ShippingCapabilities().also {
        it.rateQuote = true
        it.createShipment = true
        it.labelFormats = setOf(LabelFormat.PDF, LabelFormat.ZPL)
        it.cancel = true
        it.trackingPull = true
        it.trackBatchSize = TRACK_BATCH_SIZE
        it.trackingPush = true
        it.webhookSigned = false // the webhook is a trigger: handleInbound re-fetches before it reports (03 section 9)
        it.webhookSetup = WebhookSetup.MANUAL_URL
        it.addressResolve = true
        it.prepaidBalance = true
        it.requiredAddressFields = setOf(AddressField.PHONE)
        it.maxParcels = MAX_PARCELS
        it.quoteCacheSeconds = 600
        it.testMode = TestModeSupport.FLAG
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    override suspend fun validateSettings(ctx: ShippingContext, settings: ProviderSettings): SettingsValidation {
        val missing = LinkedHashMap<String, LocalizedText>()
        if (settings.string(ExampleSettings.Keys.API_KEY) == null) missing[ExampleSettings.Keys.API_KEY] = ExampleTexts.errorMissing
        if (settings.string(ExampleSettings.Keys.API_SECRET) == null) missing[ExampleSettings.Keys.API_SECRET] = ExampleTexts.errorMissing
        if (missing.isNotEmpty()) return SettingsValidation.invalid(missing)
        return try {
            client.account(ctx, ExampleSettings(settings))
            SettingsValidation.ok()
        } catch (e: ProviderException) {
            when (e.code) {
                ProviderErrorCode.AUTHENTICATION -> SettingsValidation.invalid(mapOf(ExampleSettings.Keys.API_KEY to ExampleTexts.errorAuthentication))
                else -> SettingsValidation.invalid(emptyMap(), ExampleTexts.errorUnreachable)
            }
        }
    }

    override suspend fun runAction(ctx: ShippingContext, actionId: String, input: JsonObject): ActionResult {
        if (actionId != ExampleSettings.Keys.ACTION_TEST_CONNECTION) throw ProviderException(ProviderErrorCode.UNSUPPORTED, "unknown action $actionId")
        return try {
            client.account(ctx, ExampleSettings(ctx.settings))
            ActionResult.Message(ExampleTexts.connectionOk, true)
        } catch (e: ProviderException) {
            when (e.code) {
                ProviderErrorCode.CONFIGURATION -> throw e
                ProviderErrorCode.AUTHENTICATION -> ActionResult.Message(ExampleTexts.errorAuthentication, false)
                else -> ActionResult.Message(ExampleTexts.errorUnreachable, false)
            }
        }
    }

    // ---- services and rates ------------------------------------------------------------------------------------------

    override suspend fun listServices(ctx: ShippingContext): List<ShippingService> {
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val services = client.services(ctx, settings).getValue("services") as? JsonArray ?: return emptyList()
        return services.mapNotNull { (it as? JsonObject) }.mapNotNull { s ->
            val code = s.str("code") ?: return@mapNotNull null
            ShippingService(code, s.str("name") ?: code).also {
                it.carrierName = s.str("carrier")
                it.international = s.bool("international") ?: false
            }
        }
    }

    override suspend fun quote(ctx: ShippingContext, request: QuoteRequest): QuoteResult {
        license.assertLicensed()
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        requireShippable(request.parcels.map { it.weightGrams }, request.parcels.size)
        val answer = client.rates(ctx, settings, ExampleMapper.ratesBody(request, SenderAddress.defaultParcelMm(ctx.settings)))
        val rates = (answer.getValue("rates") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(ExampleMapper::rateOption) } ?: emptyList()
        return QuoteResult(rates).also { it.unavailable = ExampleMapper.unavailable(answer.obj("unavailable")) }
    }

    override suspend fun resolveAddress(ctx: ShippingContext, address: Address): AddressResolution {
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val answer = client.validateAddress(ctx, settings, JsonObject().put("address", ExampleMapper.addressJson(address)))
        if (answer.bool("valid") != true) return AddressResolution.invalid(answer.str("message") ?: "the carrier does not accept this address")
        val n = answer.obj("normalized") ?: return AddressResolution.ok(address)
        fun pick(key: String, original: String?): String? = n.str(key) ?: original
        val parts = n.str("name")?.split(' ', limit = 2)
        return AddressResolution.ok(
            Address(
                firstName = parts?.getOrNull(0) ?: address.firstName, lastName = parts?.getOrNull(1) ?: address.lastName,
                company = pick("company", address.company), phone = pick("phone", address.phone), email = address.email,
                country = pick("country", address.country), state = pick("state", address.state), city = pick("city", address.city),
                district = pick("district", address.district), neighborhood = address.neighborhood,
                line1 = pick("line1", address.line1), line2 = pick("line2", address.line2), postalCode = pick("postalCode", address.postalCode),
                taxOffice = address.taxOffice, taxNumber = address.taxNumber, identityNumber = address.identityNumber
            )
        )
    }

    // ---- shipments ---------------------------------------------------------------------------------------------------

    /**
     * Two steps. Step 1 creates a draft (free), step 2 buys it (spends balance). The draft id is the `carrierReference`:
     * when step 2 is refused (no balance, expired rate) it travels back in `Failed` and a retry resumes at step 2 instead
     * of creating a second draft. Both calls carry an `Idempotency-Key` derived from `merchantReference`, so a retry after a
     * timeout (outcome unknown) gets the same draft and the same purchase back and never buys twice.
     */
    override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult {
        license.assertLicensed()
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        requireShippable(request.parcels.map { it.weightGrams }, request.parcels.size)
        if (request.merchantReference.isBlank()) throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "merchantReference is the idempotency key")

        var shipmentId = request.previousCarrierReference
        if (shipmentId == null) {
            val draft = client.createDraft(ctx, settings, ExampleMapper.draftBody(request, SenderAddress.defaultParcelMm(ctx.settings)), "draft-" + request.merchantReference)
            if (draft.status == 404) draft.requireSuccess()
            if (!draft.ok) return CreateShipmentResult.Failed(ExampleMapper.errorCode(draft.status, draft.errorCode), draft.adminMessage)
            shipmentId = draft.json.str("id")
                ?: throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the carrier answer has no shipment id", draft.json.encode().take(500))
        }

        val format = labelFormatFor(request, settings)
        val bought = client.purchase(ctx, settings, shipmentId, ExampleMapper.purchaseBody(request, format), "buy-" + request.merchantReference)
        if (bought.status == 404) bought.requireSuccess()
        if (!bought.ok) {
            return CreateShipmentResult.Failed(ExampleMapper.errorCode(bought.status, bought.errorCode), bought.adminMessage).also {
                it.carrierReference = shipmentId
            }
        }
        val answer = bought.json
        val created = CreateShipmentResult.Created(shipmentId)
        created.trackingNumber = answer.str("trackingNumber")
        created.trackingUrl = answer.str("trackingUrl")
        created.carrierName = answer.str("carrier")
        created.cost = ExampleMapper.moneyOrNull(answer.obj("cost"))
        ExampleMapper.inlineLabel(answer)?.let { (labelFormat, bytes) ->
            created.labels = listOf(LabelDocument(labelFormat, bytes))
            created.status = ShipmentStatus.LABEL_READY
        }
        created.providerData = JsonObject().put("labelFormat", format.name.lowercase())
        return created
    }

    override suspend fun fetchLabel(ctx: ShippingContext, shipment: ShipmentView): LabelResult {
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val reference = shipment.carrierReference ?: return LabelResult.none()
        val format = shipment.providerData?.str("labelFormat")?.let(ExampleMapper::labelFormat) ?: settings.labelFormat
        val answer = client.label(ctx, settings, reference, format.name.lowercase()).requireSuccess()
        if (answer.status == 202) return LabelResult.notReady()
        if (answer.bytes.isEmpty()) return LabelResult.notReady()
        if (answer.bytes.size > ExampleMapper.MAX_LABEL_BYTES) throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the label is larger than ${ExampleMapper.MAX_LABEL_BYTES} bytes")
        return LabelResult.of(listOf(LabelDocument(format, answer.bytes)))
    }

    override suspend fun cancelShipment(ctx: ShippingContext, shipment: ShipmentView): CancelShipmentResult {
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val reference = shipment.carrierReference ?: return CancelShipmentResult.refused("the shipment has no carrier reference")
        val answer = client.cancel(ctx, settings, reference)
        if (answer.ok) {
            return if (ExampleMapper.status(answer.json.str("status")) == ShipmentStatus.CANCELLED) CancelShipmentResult.cancelled()
            else CancelShipmentResult.refused("the carrier did not confirm the cancellation")
        }
        return CancelShipmentResult.refused(answer.adminMessage ?: "the carrier refused to cancel (HTTP ${answer.status})")
    }

    // ---- tracking ----------------------------------------------------------------------------------------------------

    override suspend fun track(ctx: ShippingContext, request: TrackRequest): List<TrackingUpdate> {
        license.assertLicensed()
        if (request.shipments.size > TRACK_BATCH_SIZE) {
            throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "at most $TRACK_BATCH_SIZE shipments per call, got ${request.shipments.size}")
        }
        val byReference = request.shipments.filter { it.carrierReference != null }.associateBy { it.carrierReference!! }
        if (byReference.isEmpty()) return emptyList()
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val answer = client.getShipments(ctx, settings, byReference.keys.toList())
        val shipments = answer.getValue("shipments") as? JsonArray ?: return emptyList()
        return shipments.mapNotNull { (it as? JsonObject) }.mapNotNull { shipment ->
            val view = shipment.str("id")?.let { byReference[it] } ?: return@mapNotNull null
            ExampleMapper.trackingUpdate(shipment, ShipmentTarget.Id(view.id))
        }
    }

    /**
     * The tracking webhook is UNSIGNED: anyone who knows the URL can post to it, and the body is the same for every
     * state. So the body is only read for the shipment id; the state comes from the carrier's own answer to a fresh read
     * (03 section 9). `eventKey` stays unset (the body cannot identify a delivery), a webhook for a shipment market does not
     * know costs no outbound call, and the id is checked against a strict pattern before it goes into a URL.
     */
    override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
        license.assertLicensed()
        if (!request.method.equals("POST", ignoreCase = true)) return ShippingInboundResult.rejected(HttpReply.text("method not allowed", 405), "method ${request.method}")
        if (request.body.size > MAX_WEBHOOK_BYTES) return ShippingInboundResult.rejected(HttpReply.text("invalid request", 400), "body too large")
        val envelope = try {
            JsonObject(request.bodyAsString())
        } catch (e: Exception) {
            return ShippingInboundResult.rejected(HttpReply.text("invalid request", 400), "body is not a JSON object")
        }
        if (envelope.str("event") != "shipment.updated") return acknowledged()
        val shipmentId = envelope.str("shipmentId")
        if (shipmentId == null || !SHIPMENT_ID.matches(shipmentId)) return ShippingInboundResult.rejected(HttpReply.text("invalid request", 400), "no valid shipmentId")
        // Not ours: nothing to refresh, and no reason to let a stranger make this server call the carrier.
        if (ctx.shipments.byCarrierReference(shipmentId) == null) return acknowledged()

        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val shipment = try {
            client.getShipment(ctx, settings, shipmentId)
        } catch (e: ProviderException) {
            if (e.code == ProviderErrorCode.CONFIGURATION) throw e
            // Could not confirm: nothing is reported, and the carrier is asked to deliver again.
            ctx.log.warn("webhook re-fetch failed (${e.code})")
            return ShippingInboundResult.rejected(HttpReply.retryLater(), "re-fetch failed: ${e.code}")
        } ?: return acknowledged()
        if (shipment.str("id") != shipmentId) return acknowledged()
        val update = ExampleMapper.trackingUpdate(shipment, ShipmentTarget.CarrierReference(shipmentId)) ?: return acknowledged()
        return ShippingInboundResult.accepted(HttpReply.text("OK"), listOf(update), eventKey = null)
    }

    private fun acknowledged(): ShippingInboundResult = ShippingInboundResult.ignored(HttpReply.text("OK"))

    // ---- balance -----------------------------------------------------------------------------------------------------

    override suspend fun balance(ctx: ShippingContext): Money? {
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        return ExampleMapper.moneyOrNull(client.account(ctx, settings).obj("balance"))
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** Market keeps to `capabilities.maxParcels`; a request outside it is a market bug, refused before any call. */
    private fun requireShippable(weightsGrams: List<Int>, parcelCount: Int) {
        if (parcelCount < 1 || parcelCount > MAX_PARCELS) throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "this carrier takes exactly $MAX_PARCELS parcel per shipment, got $parcelCount")
        if (weightsGrams.any { it <= 0 }) throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "every parcel needs a weight above zero")
    }

    private fun labelFormatFor(request: CreateShipmentRequest, settings: ExampleSettings): LabelFormat =
        request.preferredLabelFormat?.takeIf { it == LabelFormat.PDF || it == LabelFormat.ZPL } ?: settings.labelFormat

    private fun loadLogo(): ProviderAsset? = try {
        ExampleProvider::class.java.getResourceAsStream("/logo.png")?.use { ProviderAsset("image/png", it.readBytes()) }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TRACK_BATCH_SIZE = 20
        const val MAX_PARCELS = 1
        private const val MAX_WEBHOOK_BYTES = 64 * 1024
        private val SHIPMENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
