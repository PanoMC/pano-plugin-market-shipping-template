package com.panomc.plugins.marketship.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** S-03: grams to kilograms, millimetres to centimetres and desi, with the values on both sides of every boundary. */
class ExampleUnitsTest {
    @Test
    fun `S-03 grams become kilograms rounded up to the next 100 g`() {
        val expected = mapOf(
            1 to "0.1", 99 to "0.1", 100 to "0.1", 101 to "0.2", 199 to "0.2", 200 to "0.2", 500 to "0.5", 900 to "0.9",
            999 to "1.0", 1000 to "1.0", 1001 to "1.1", 1999 to "2.0", 2000 to "2.0", 2001 to "2.1", 29_999 to "30.0", 30_000 to "30.0", 30_001 to "30.1"
        )
        for ((grams, kg) in expected) assertEquals(kg, ExampleUnits.gramsToKg(grams), "$grams g")
    }

    @Test
    fun `S-03 the largest weight does not overflow`() {
        assertEquals("2147483.7", ExampleUnits.gramsToKg(Int.MAX_VALUE))
    }

    @Test
    fun `S-03 a weight of zero or less is refused, never sent as 0 kg`() {
        for (bad in listOf(0, -1, Int.MIN_VALUE)) assertThrows(IllegalArgumentException::class.java) { ExampleUnits.gramsToKg(bad) }
    }

    @Test
    fun `S-03 millimetres become whole centimetres rounded up, at least 1`() {
        val expected = mapOf(1 to 1, 9 to 1, 10 to 1, 11 to 2, 19 to 2, 20 to 2, 21 to 3, 200 to 20, 201 to 21, 99_999 to 10_000, 100_000 to 10_000)
        for ((mm, cm) in expected) assertEquals(cm, ExampleUnits.mmToCm(mm), "$mm mm")
    }

    @Test
    fun `S-03 a dimension outside 1 to 100000 mm is refused`() {
        for (bad in listOf(0, -5, 100_001, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { ExampleUnits.mmToCm(bad) }
    }

    @Test
    fun `S-03 desi is the volume in cm3 over 3000 rounded up, at least 1`() {
        // 30 x 10 x 10 cm = 3000 cm3 = exactly 1 desi; one more centimetre on any side makes it 2
        assertEquals(1, ExampleUnits.desi(300, 100, 100))
        assertEquals(2, ExampleUnits.desi(310, 100, 100))
        assertEquals(2, ExampleUnits.desi(301, 100, 100))
        // 20 x 15 x 10 = 3000 cm3 -> 1; the sample parcel of the testkit (200 x 150 x 100 mm)
        assertEquals(1, ExampleUnits.desi(200, 150, 100))
        // tiny parcel: never 0
        assertEquals(1, ExampleUnits.desi(1, 1, 1))
        // 60 x 40 x 40 = 96 000 cm3 -> 32
        assertEquals(32, ExampleUnits.desi(600, 400, 400))
        // 60.1 cm rounds the side up to 61: 61 x 40 x 40 = 97 600 -> 33 (32.53 rounded up)
        assertEquals(33, ExampleUnits.desi(601, 400, 400))
    }

    @Test
    fun `S-03 the converters use integer arithmetic only`() {
        val source = File("src/main/kotlin").walkTopDown().first { it.isFile && it.name.endsWith("Units.kt") }.readText()
        val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
        assertTrue(!Regex("\\b(Double|Float|toDouble|toFloat)\\b|Math\\.(ceil|round|floor)").containsMatchIn(code), "ExampleUnits uses floating point")
    }
}
