package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.LocalizedText

/**
 * Every admin-facing text of the plugin as an i18n key (16 section 8.2): the key is `plugins.<pluginId>.<path>`, the
 * same path inside `locales/{en-US,tr,ru}.json`, and the English fallback lives here. [all] lists every text so the
 * locale test can prove that the three files hold exactly these keys. The sender address and default parcel fields come
 * from the SPI helpers `senderAddress()` / `defaultParcel()` and carry their own literal translations.
 */
object ExampleTexts {
    const val PLUGIN_ID = "pano-plugin-market-shipping-example"

    private val registry = ArrayList<LocalizedText>()

    private fun text(path: String, fallback: String): LocalizedText =
        LocalizedText.key("plugins.$PLUGIN_ID.$path", fallback).also { registry.add(it) }

    val displayName = text("descriptor.name", "Example Carrier")
    val description = text("descriptor.description", "Ship orders with Example Carrier: live rates, labels and tracking.")

    val groupCredentials = text("settings.groups.credentials", "Credentials")
    val groupSender = text("settings.groups.sender", "Sender and parcel")
    val groupOptions = text("settings.groups.options", "Options")

    val apiKeyLabel = text("settings.fields.apiKey.label", "API key")
    val apiKeyHelp = text(
        "settings.fields.apiKey.help",
        "API key from the developer section of your Example Carrier account. Use a sandbox key together with test mode."
    )
    val apiSecretLabel = text("settings.fields.apiSecret.label", "API secret")
    val apiSecretHelp = text(
        "settings.fields.apiSecret.help",
        "The secret that belongs to the API key. Pano Market exchanges both for a short-lived access token."
    )
    val webhookUrlLabel = text("settings.fields.webhookUrl.label", "Webhook URL")
    val webhookUrlHelp = text(
        "settings.fields.webhookUrl.help",
        "Add this URL as a tracking webhook in your Example Carrier account. The webhook is not signed: Pano Market asks Example Carrier for the real state before it believes a notification."
    )
    val labelFormatLabel = text("settings.fields.labelFormat.label", "Label format")
    val labelFormatHelp = text(
        "settings.fields.labelFormat.help",
        "PDF prints on any printer; ZPL is for thermal label printers."
    )
    val labelFormatPdf = text("settings.fields.labelFormat.options.pdf", "PDF")
    val labelFormatZpl = text("settings.fields.labelFormat.options.zpl", "ZPL (thermal printer)")

    val testConnectionLabel = text("settings.actions.test-connection.label", "Test connection")

    val errorMissing = text("errors.missing", "This field is required.")
    val errorAuthentication = text("errors.authentication", "Example Carrier rejected the API key or secret.")
    val errorUnreachable = text("errors.unreachable", "Example Carrier could not be reached. Try again in a moment.")
    val connectionOk = text("messages.connectionOk", "The connection to Example Carrier works.")

    /** Every text above, in declaration order. */
    val all: List<LocalizedText> get() = registry.toList()
}
