package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.Parcel
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** S-07 (status mapping against the vector file, tracking objects), money and request mapping, error code table. */
class ExampleMapperTest {
    private fun vectors(): JsonObject = JsonObject(javaClass.getResourceAsStream("/vectors/tracking.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    @TestFactory
    fun `S-07 every status word of the carrier maps as the vector file says`(): List<DynamicTest> {
        val vectors = vectors()
        val prefix = if (vectors.getString("kind") == "OFFICIAL") "official" else "self-derived"
        return vectors.getJsonArray("statuses").map { it as JsonObject }.map { v ->
            DynamicTest.dynamicTest("$prefix - status '${v.getString("raw")}'") {
                val expected = v.getString("expected")?.let { ShipmentStatus.valueOf(it) }
                assertEquals(expected, ExampleMapper.status(v.getString("raw")))
            }
        }
    }

    @TestFactory
    fun `S-07 tracking objects map to updates as the vector file says`(): List<DynamicTest> {
        val vectors = vectors()
        return vectors.getJsonArray("shipments").map { it as JsonObject }.map { v ->
            DynamicTest.dynamicTest("self-derived - ${v.getString("name")}") {
                val update = ExampleMapper.trackingUpdate(v.getJsonObject("shipment"), ShipmentTarget.CarrierReference("shp_1"))
                assertNotNull(update)
                update!!
                val expected = v.getJsonObject("expected")
                assertEquals(expected.getString("trackingNumber"), update.trackingNumber)
                assertEquals(expected.getLong("estimatedDeliveryMs") as Long?, update.estimatedDelivery)
                val events = expected.getJsonArray("events").map { it as JsonObject }
                assertEquals(events.size, update.events.size)
                events.zip(update.events).forEach { (want, got) ->
                    assertEquals(want.getString("eventId"), got.eventId)
                    assertEquals(ShipmentStatus.valueOf(want.getString("status")), got.status)
                    assertEquals(want.getString("rawStatus"), got.rawStatus)
                    assertEquals(want.getLong("occurredAtMs"), got.occurredAt)
                }
            }
        }
    }

    @Test
    fun `S-07 an object with no usable event and no tracking number tells nothing`() {
        assertNull(ExampleMapper.trackingUpdate(JsonObject().put("id", "shp_1"), ShipmentTarget.Id(1)))
        assertNull(ExampleMapper.trackingUpdate(Carrier.shipment(events = listOf(Carrier.event("e", "teleported")), trackingNumber = null), ShipmentTarget.Id(1)))
        // a wrong JSON type is absent, never an exception
        val odd = JsonObject().put("id", "shp_1").put("events", JsonArray().add("x").add(1).add(JsonObject().put("status", 5).put("time", "now"))).put("trackingNumber", 12)
        assertNull(ExampleMapper.trackingUpdate(odd, ShipmentTarget.Id(1)))
        // a fractional or non-positive time is absent
        assertNull(ExampleMapper.trackingEvent(JsonObject().put("status", "delivered").put("time", 1.5)))
        assertNull(ExampleMapper.trackingEvent(JsonObject().put("status", "delivered").put("time", 0)))
    }

    @Test
    fun `money goes through minor units in both directions, TRY is TL, JPY has no decimals`() {
        assertEquals(JsonObject().put("amount", 1234L).put("currency", "EUR"), ExampleMapper.moneyJson(Money(1234, "EUR")))
        assertEquals(JsonObject().put("amount", 500L).put("currency", "JPY"), ExampleMapper.moneyJson(Money(50_000, "JPY")))
        assertEquals(JsonObject().put("amount", 9900L).put("currency", "TL"), ExampleMapper.moneyJson(Money(9900, "TRY")))
        assertEquals(Money(1234, "EUR"), ExampleMapper.moneyOrNull(1234, "EUR"))
        assertEquals(Money(50_000, "JPY"), ExampleMapper.moneyOrNull(500, "JPY"))
        assertEquals(Money(9900, "TRY"), ExampleMapper.moneyOrNull(9900, "tl"))
        assertNull(ExampleMapper.moneyOrNull(100, "XXX"), "an unknown currency is absent, not an exception")
        assertNull(ExampleMapper.moneyOrNull(Long.MAX_VALUE, "JPY"), "an overflowing amount is absent")
        assertNull(ExampleMapper.moneyOrNull(null, "EUR"))
        assertNull(ExampleMapper.moneyOrNull(100, null))
    }

    @Test
    fun `rate options keep the opaque rate id and drop unusable entries`() {
        val rate = ExampleMapper.rateOption(Carrier.rate())!!
        assertEquals("standard", rate.serviceCode)
        assertEquals(Money(590, "EUR"), rate.price)
        assertEquals("rate_1", rate.rateRef)
        assertEquals("Example Post", rate.carrierName)
        assertEquals(2, rate.minDays)
        assertEquals(4, rate.maxDays)
        assertEquals(TestContexts.START_MS / 1000L + 600, rate.expiresAt!! / 1000L)
        assertNull(ExampleMapper.rateOption(JsonObject().put("service", "x").put("amount", 5.9).put("currency", "EUR")), "a fractional amount is absent")
        assertNull(ExampleMapper.rateOption(JsonObject().put("service", "x").put("amount", 100).put("currency", "ZZZ")))
        assertNull(ExampleMapper.rateOption(JsonObject().put("amount", 100).put("currency", "EUR")), "no service code")
        assertNull(ExampleMapper.rateOption(Carrier.rate(amount = -1)))
    }

    @Test
    fun `parcel json carries kilograms, centimetres and desi, default dimensions fill a parcel without any`() {
        val full = ExampleMapper.parcelJson(Parcel(1001, 200, 150, 100))
        assertEquals("1.1", full.getString("weightKg"))
        assertEquals(20, full.getInteger("lengthCm"))
        assertEquals(15, full.getInteger("widthCm"))
        assertEquals(10, full.getInteger("heightCm"))
        assertEquals(1, full.getInteger("desi"))
        val bare = ExampleMapper.parcelJson(Parcel(500, null, null, null))
        assertEquals(setOf("weightKg"), bare.fieldNames())
        val filled = ExampleMapper.parcelJson(Parcel(500, null, null, null), Triple(300, 100, 100))
        assertEquals(30, filled.getInteger("lengthCm"))
        assertEquals(1, filled.getInteger("desi"))
        // two of three dimensions are not enough: all three or none are sent
        assertEquals(setOf("weightKg"), ExampleMapper.parcelJson(Parcel(500, 100, null, 100)).fieldNames())
    }

    @Test
    fun `address json joins the name, upper-cases the country and leaves blank parts out`() {
        val a = Address("Steve", "Miner", null, "+491701234567", "s@example.com", "de", null, "Berlin", null, null, " Example Street 1 ", "  ", "10115", null, null, "12345678901")
        val json = ExampleMapper.addressJson(a)
        assertEquals("Steve Miner", json.getString("name"))
        assertEquals("DE", json.getString("country"))
        assertEquals("Example Street 1", json.getString("line1"))
        assertTrue(!json.containsKey("line2") && !json.containsKey("company"))
        assertTrue(!json.encode().contains("12345678901"), "the identity number is never sent")
    }

    @Test
    fun `carrier error codes map to the SPI codes`() {
        assertEquals(ShipmentErrorCode.INSUFFICIENT_BALANCE, ExampleMapper.errorCode(402, null))
        assertEquals(ShipmentErrorCode.INSUFFICIENT_BALANCE, ExampleMapper.errorCode(422, "insufficient_balance"))
        assertEquals(ShipmentErrorCode.ADDRESS_INVALID, ExampleMapper.errorCode(422, "address_invalid"))
        assertEquals(ShipmentErrorCode.SERVICE_UNAVAILABLE, ExampleMapper.errorCode(422, "service_unavailable"))
        assertEquals(ShipmentErrorCode.RATE_EXPIRED, ExampleMapper.errorCode(409, "rate_expired"))
        assertEquals(ShipmentErrorCode.WEIGHT_LIMIT, ExampleMapper.errorCode(422, "weight_limit"))
        assertEquals(ShipmentErrorCode.REJECTED, ExampleMapper.errorCode(422, "something_new"))
        assertEquals(ShipmentErrorCode.REJECTED, ExampleMapper.errorCode(400, null))
    }

    @Test
    fun `inline labels decode base64 and reject garbage`() {
        val pdf = "%PDF-1.4 test".toByteArray()
        val (format, bytes) = ExampleMapper.inlineLabel(Carrier.purchased(pdf))!!
        assertEquals(LabelFormat.PDF, format)
        assertEquals(pdf.toList(), bytes.toList())
        assertNull(ExampleMapper.inlineLabel(Carrier.purchased(null)))
        assertNull(ExampleMapper.inlineLabel(JsonObject().put("label", JsonObject().put("format", "pdf").put("data", "***not base64***"))))
        assertNull(ExampleMapper.inlineLabel(JsonObject().put("label", JsonObject().put("format", "gif").put("data", "AAAA"))))
        assertNull(ExampleMapper.inlineLabel(JsonObject().put("label", JsonObject().put("format", "pdf").put("data", ""))))
    }

    @Test
    fun `S-03 the mapper source has no floating point either`() {
        val source = File("src/main/kotlin").walkTopDown().first { it.isFile && it.name.endsWith("Mapper.kt") }.readText()
        val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
        assertTrue(!Regex("\\b(Double|Float|toDouble|toFloat)\\b").containsMatchIn(code), "ExampleMapper uses floating point")
        assertNotNull(SampleData.parcel())
    }
}
