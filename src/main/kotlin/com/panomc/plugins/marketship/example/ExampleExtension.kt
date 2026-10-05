package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.shipping.ShippingProvider

class ExampleExtension(license: LicenseCheck) : MarketExtension {
    override val spiVersion: Int = MarketSpi.VERSION

    private val providers = listOf<ShippingProvider>(ExampleProvider(license))

    override fun shippingProviders(): List<ShippingProvider> = providers
}
