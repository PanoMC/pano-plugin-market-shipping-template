package com.panomc.plugins.marketship.example

/**
 * The only place that holds a host name of the carrier (S-11 scans the other sources for one). The provider takes an
 * optional `(Boolean) -> ExampleEndpoints` constructor argument that tests use to point it at a FakeGateway.
 */
class ExampleEndpoints(val api: String) {
    companion object {
        const val LIVE_API = "https://api.examplecarrier.example"
        const val SANDBOX_API = "https://sandbox.api.examplecarrier.example"
        const val DOCS_URL = "https://docs.examplecarrier.example"

        /** `testMode` is `ctx.testMode`: the method flag or the store-wide test mode. */
        fun of(testMode: Boolean): ExampleEndpoints = ExampleEndpoints(if (testMode) SANDBOX_API else LIVE_API)
    }
}
