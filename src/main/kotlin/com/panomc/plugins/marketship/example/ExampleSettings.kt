package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.shipping.LabelFormat
import java.security.MessageDigest

/** Typed view over [ProviderSettings]. Defaults here must equal the `default` of the schema. */
class ExampleSettings(private val raw: ProviderSettings) {
    object Keys {
        const val API_KEY = "apiKey"
        const val API_SECRET = "apiSecret"
        const val WEBHOOK_URL = "webhookUrl"
        const val LABEL_FORMAT = "labelFormat"
        const val ACTION_TEST_CONNECTION = "test-connection"
        const val GROUP_CREDENTIALS = "credentials"
        const val GROUP_SENDER = "sender"
        const val GROUP_OPTIONS = "options"
    }

    /** Throws `ProviderException(CONFIGURATION)` when absent. */
    val apiKey: String get() = raw.require(Keys.API_KEY)

    /** Throws `ProviderException(CONFIGURATION)` when absent. */
    val apiSecret: String get() = raw.require(Keys.API_SECRET)

    /** The label format the admin chose; PDF unless the setting says `zpl`. */
    val labelFormat: LabelFormat get() = if (raw.string(Keys.LABEL_FORMAT) == "zpl") LabelFormat.ZPL else LabelFormat.PDF

    /** Everything a call needs: both credentials. */
    fun requireCredentials() {
        apiKey
        apiSecret
    }

    /** The secret values that may never appear in a log line or an error text. */
    fun secretValues(): List<String> =
        listOfNotNull(raw.string(Keys.API_KEY), raw.string(Keys.API_SECRET)).filter { it.length >= 4 }

    /**
     * Key of the cached access token in `ctx.state` (spec 03 section 5): scoped to the mode and to a hash of the
     * credentials, so a changed key never reuses the token of the old one. The hash is one-way; no secret is stored in a key.
     */
    fun tokenStateKey(testMode: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$apiKey\u0000$apiSecret".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        return "token:${if (testMode) "test" else "live"}:$hex"
    }
}
