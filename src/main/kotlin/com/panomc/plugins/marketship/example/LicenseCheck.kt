package com.panomc.plugins.marketship.example

/**
 * What the provider classes know about the license (16 section 7.1). The plugin class hands the extension a lambda
 * that calls `PluginLicenseClient.assertStillLicensed()`, so no provider class references a platform type and the
 * provider unit tests need no platform jar at run time.
 */
fun interface LicenseCheck {
    /** Throws when the plugin may no longer move money or goods. A FREE build never throws. */
    suspend fun assertLicensed()
}
