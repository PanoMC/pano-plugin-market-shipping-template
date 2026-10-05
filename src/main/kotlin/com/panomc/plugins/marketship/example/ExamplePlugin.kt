package com.panomc.plugins.marketship.example

import com.panomc.platform.api.PanoPlugin
import com.panomc.plugins.license.PluginLicenseClient

class ExamplePlugin : PanoPlugin() {
    private val license by lazy { PluginLicenseClient(this) }
    private val extension by lazy { ExampleExtension(LicenseCheck { license.assertStillLicensed() }) }

    override suspend fun onStart() {
        license.requireValidLicense() // no-op in a FREE build; throws LicenseRequiredException otherwise
        register(extension) // only after the check (02 section 2)
    }

    override suspend fun onStop() {
        unRegister(extension)
    }

    override suspend fun verifyLicense() {
        license.requireValidLicense()
    }
}
