package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.Parcel
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.RateOption
import com.panomc.plugins.market.spi.shipping.ShipItem
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Pure functions between the carrier's JSON and the SPI types: no I/O, no `ctx`, never a `Double`.
 *
 * Wire amounts are ISO minor units (`590` = 5.90 EUR, `500` = 500 JPY); `Money` holds value x 100, so the conversion
 * always goes through `Money.toMinorUnits()` / `Money.ofMinorUnits()`. The carrier writes Turkish lira as `TL`. Weights
 * and sizes go through [ExampleUnits].
 */
object ExampleMapper {
    fun toWireCurrency(code: String): String = if (code == "TRY") "TL" else code

    fun fromWireCurrency(code: String): String = code.trim().uppercase().let { if (it == "TL") "TRY" else it }

    fun moneyJson(money: Money): JsonObject = JsonObject().put("amount", money.toMinorUnits()).put("currency", toWireCurrency(money.currency))

    /** Null instead of throwing: the carrier's answer carries something the SPI cannot represent (unknown currency, overflow). */
    fun moneyOrNull(minor: Long?, wireCurrency: String?): Money? {
        if (minor == null || wireCurrency == null) return null
        return try {
            Money.ofMinorUnits(minor, fromWireCurrency(wireCurrency))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    fun moneyOrNull(json: JsonObject?): Money? = if (json == null) null else moneyOrNull(json.long("amount"), json.str("currency"))

    // ---- requests ---------------------------------------------------------------------------------------------------

    fun addressJson(address: Address): JsonObject {
        val json = JsonObject()
        fun put(key: String, value: String?) {
            val v = value?.trim()
            if (!v.isNullOrEmpty()) json.put(key, v)
        }
        put("name", listOfNotNull(address.firstName, address.lastName).joinToString(" "))
        put("company", address.company)
        put("phone", address.phone)
        put("email", address.email)
        put("country", address.country?.uppercase())
        put("state", address.state)
        put("city", address.city)
        put("district", address.district)
        put("line1", address.line1)
        put("line2", address.line2)
        put("postalCode", address.postalCode)
        return json
    }

    /** [defaultMm] (length, width, height) fills in a parcel that came without dimensions; all three or none are sent. */
    fun parcelJson(parcel: Parcel, defaultMm: Triple<Int?, Int?, Int?> = Triple(null, null, null)): JsonObject {
        val json = JsonObject().put("weightKg", ExampleUnits.gramsToKg(parcel.weightGrams))
        val l = parcel.lengthMm ?: defaultMm.first
        val w = parcel.widthMm ?: defaultMm.second
        val h = parcel.heightMm ?: defaultMm.third
        if (l != null && w != null && h != null) {
            json.put("lengthCm", ExampleUnits.mmToCm(l)).put("widthCm", ExampleUnits.mmToCm(w)).put("heightCm", ExampleUnits.mmToCm(h))
                .put("desi", ExampleUnits.desi(l, w, h))
        }
        return json
    }

    fun itemJson(item: ShipItem): JsonObject {
        val json = JsonObject().put("name", item.name.take(120)).put("quantity", item.quantity).put("value", moneyJson(item.unitValue))
        item.sku?.takeIf { it.isNotBlank() }?.let { json.put("sku", it.take(60)) }
        if (item.unitWeightGrams > 0) json.put("weightKg", ExampleUnits.gramsToKg(item.unitWeightGrams))
        item.hsCode?.takeIf { it.isNotBlank() }?.let { json.put("hsCode", it) }
        item.originCountry?.takeIf { it.isNotBlank() }?.let { json.put("originCountry", it.uppercase()) }
        return json
    }

    fun ratesBody(request: QuoteRequest, defaultMm: Triple<Int?, Int?, Int?>): JsonObject {
        val body = JsonObject()
            .put("from", addressJson(request.from))
            .put("to", addressJson(request.to))
            .put("parcels", JsonArray(request.parcels.map { parcelJson(it, defaultMm) }))
            .put("declaredValue", moneyJson(request.orderValue))
        request.serviceCode?.let { body.put("serviceCode", it) }
        return body
    }

    fun draftBody(request: CreateShipmentRequest, defaultMm: Triple<Int?, Int?, Int?>): JsonObject {
        val body = JsonObject()
            .put("reference", request.merchantReference)
            .put("from", addressJson(request.from))
            .put("to", addressJson(request.to))
            .put("parcels", JsonArray(request.parcels.map { parcelJson(it, defaultMm) }))
            .put("items", JsonArray(request.items.map { itemJson(it) }))
            .put("declaredValue", moneyJson(request.declaredValue))
        request.serviceCode?.let { body.put("serviceCode", it) }
        request.note?.takeIf { it.isNotBlank() }?.let { body.put("note", it.take(200)) }
        return body
    }

    fun purchaseBody(request: CreateShipmentRequest, format: LabelFormat): JsonObject {
        val body = JsonObject().put("labelFormat", format.name.lowercase())
        request.rateRef?.let { body.put("rateId", it) }
        request.serviceCode?.let { body.put("serviceCode", it) }
        return body
    }

    // ---- responses --------------------------------------------------------------------------------------------------

    /** One entry of `rates`, or null when it has no usable service, a non-positive price or a currency the SPI refuses. */
    fun rateOption(rate: JsonObject): RateOption? {
        val service = rate.str("service") ?: return null
        val price = moneyOrNull(rate.long("amount"), rate.str("currency")) ?: return null
        if (price.amount < 0L) return null
        return RateOption(service, rate.str("name") ?: service, price).also {
            it.carrierName = rate.str("carrier")
            it.rateRef = rate.str("rateId")
            it.minDays = rate.long("minDays")?.toInt()
            it.maxDays = rate.long("maxDays")?.toInt()
            it.expiresAt = rate.long("expiresAt")?.let { s -> s * 1000L }
            it.priceIncludesTax = rate.bool("includesTax") ?: true
        }
    }

    fun unavailable(json: JsonObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (code in json.fieldNames()) (json.getValue(code) as? String)?.let { out[code] = it.take(200) }
        return out
    }

    /** The carrier's status word to the normalised status, or null for a word this plugin does not know (see VERIFICATION.md). */
    fun status(raw: String?): ShipmentStatus? = when (raw?.trim()?.lowercase()) {
        "draft", "created" -> ShipmentStatus.CREATED
        "purchased", "label_ready" -> ShipmentStatus.LABEL_READY
        "picked_up", "in_transit", "at_hub" -> ShipmentStatus.IN_TRANSIT
        "out_for_delivery" -> ShipmentStatus.OUT_FOR_DELIVERY
        "delivered" -> ShipmentStatus.DELIVERED
        "delivery_failed", "exception", "held", "customs_hold" -> ShipmentStatus.EXCEPTION
        "returning", "return_to_sender" -> ShipmentStatus.RETURNING
        "returned" -> ShipmentStatus.RETURNED
        "cancelled", "voided" -> ShipmentStatus.CANCELLED
        "lost" -> ShipmentStatus.LOST
        else -> null
    }

    /** One carrier tracking event, or null when its status word is unknown or it carries no time. */
    fun trackingEvent(event: JsonObject): TrackingEvent? {
        val raw = event.str("status")
        val status = status(raw) ?: return null
        val time = event.long("time") ?: return null
        if (time <= 0L) return null
        return TrackingEvent(status, time * 1000L).also {
            it.rawStatus = raw
            it.description = event.str("description")?.take(500)
            it.location = event.str("location")?.take(200)
            it.eventId = event.str("id")
        }
    }

    /**
     * One carrier shipment object (`GET /v1/shipments/{id}` or one entry of the batch answer) to a tracking update for
     * [target]. Events with an unknown status or no time are dropped; the shipment-level tracking number, url and
     * estimate ride along. Null when the object tells nothing at all.
     */
    fun trackingUpdate(shipment: JsonObject, target: ShipmentTarget): TrackingUpdate? {
        val events = (shipment.getValue("events") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::trackingEvent) } ?: emptyList()
        val number = shipment.str("trackingNumber")
        if (events.isEmpty() && number == null) return null
        return TrackingUpdate(target, events).also {
            it.trackingNumber = number
            it.trackingUrl = shipment.str("trackingUrl")
            it.estimatedDelivery = shipment.long("estimatedDelivery")?.let { s -> s * 1000L }
        }
    }

    /** Carrier error code (and HTTP status) of a refused create / purchase call to the SPI's error code. */
    fun errorCode(status: Int, code: String?): ShipmentErrorCode = when {
        code == "insufficient_balance" || status == 402 -> ShipmentErrorCode.INSUFFICIENT_BALANCE
        code == "address_invalid" -> ShipmentErrorCode.ADDRESS_INVALID
        code == "service_unavailable" -> ShipmentErrorCode.SERVICE_UNAVAILABLE
        code == "rate_expired" -> ShipmentErrorCode.RATE_EXPIRED
        code == "weight_limit" -> ShipmentErrorCode.WEIGHT_LIMIT
        else -> ShipmentErrorCode.REJECTED
    }

    /** Parses `label.data` (base64) of a purchase answer; null when absent or not valid base64. */
    fun inlineLabel(answer: JsonObject): Pair<LabelFormat, ByteArray>? {
        val label = answer.obj("label") ?: return null
        val format = labelFormat(label.str("format")) ?: return null
        val data = label.str("data") ?: return null
        if (data.length > MAX_LABEL_BASE64_CHARS) return null
        val bytes = try {
            java.util.Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (bytes.isEmpty()) null else format to bytes
    }

    fun labelFormat(raw: String?): LabelFormat? = when (raw?.trim()?.lowercase()) {
        "pdf" -> LabelFormat.PDF
        "zpl" -> LabelFormat.ZPL
        else -> null
    }

    const val MAX_LABEL_BYTES = 4 * 1024 * 1024
    private const val MAX_LABEL_BASE64_CHARS = MAX_LABEL_BYTES / 3 * 4 + 8
}

/** Tolerant readers: a wrong JSON type is "absent", never a `ClassCastException`. */
internal fun JsonObject.str(key: String): String? = (getValue(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** Whole numbers only: a fractional JSON number (`5.9`) is "absent", it is never rounded or truncated into an amount. */
internal fun JsonObject.long(key: String): Long? = when (val value = getValue(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal fun JsonObject.bool(key: String): Boolean? = getValue(key) as? Boolean

internal fun JsonObject.obj(key: String): JsonObject? = getValue(key) as? JsonObject
