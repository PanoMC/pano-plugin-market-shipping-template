package com.panomc.plugins.marketship.example

/**
 * Pure unit conversion between the SPI (grams, millimetres) and the carrier (kilograms in 0.1 steps, whole centimetres,
 * desi). No I/O, never a `Double`: every conversion is integer arithmetic that rounds UP, because the carrier bills by
 * the next full step and a rounded-down declaration is a bill that arrives later.
 */
object ExampleUnits {
    /** Heaviest parcel the carrier takes, grams. The carrier enforces it; this is the value its documentation names. */
    const val MAX_WEIGHT_GRAMS = 30_000

    /** Largest single dimension the converters accept, millimetres (a guard against nonsense, not a carrier rule). */
    const val MAX_DIMENSION_MM = 100_000

    private const val DESI_DIVISOR = 3_000L

    /** Kilograms as a decimal string with one decimal, rounded up to the next 100 g: 1 -> "0.1", 100 -> "0.1", 101 -> "0.2", 1001 -> "1.1". */
    fun gramsToKg(grams: Int): String {
        require(grams > 0) { "weight must be positive, was $grams g" }
        val tenths = (grams.toLong() + 99L) / 100L
        return "${tenths / 10}.${tenths % 10}"
    }

    /** Whole centimetres, rounded up, at least 1: 1 -> 1, 10 -> 1, 11 -> 2. */
    fun mmToCm(mm: Int): Int {
        require(mm in 1..MAX_DIMENSION_MM) { "dimension must be between 1 and $MAX_DIMENSION_MM mm, was $mm" }
        return ((mm + 9) / 10)
    }

    /** Volumetric weight: `ceil(L x W x H in cm^3 / 3000)` with each side rounded up to whole centimetres, at least 1. */
    fun desi(lengthMm: Int, widthMm: Int, heightMm: Int): Int {
        val volume = mmToCm(lengthMm).toLong() * mmToCm(widthMm).toLong() * mmToCm(heightMm).toLong()
        return maxOf(1L, (volume + DESI_DIVISOR - 1L) / DESI_DIVISOR).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
