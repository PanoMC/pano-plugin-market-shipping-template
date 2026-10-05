package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S-12: the verification level the descriptor claims equals `verification.json` and satisfies the condition of spec 16
 * section 8.4. The rule itself is a plain function, tested here against synthetic cases so that a gateway plugin that
 * raises its level without evidence fails.
 */
class VerificationLevelTest {
    class Evidence(val what: String, val kind: String, val vector: String)

    /** One problem per entry; empty = the level is allowed. */
    object Rules {
        fun violations(
            level: Verification,
            evidence: List<Evidence>,
            vectorKinds: Map<String, String>,
            sandbox: JsonObject?,
            cancel: Boolean,
            signsRequests: Boolean,
            verificationMd: String
        ): List<String> {
            val problems = ArrayList<String>()
            for (e in evidence) {
                val actual = vectorKinds[e.vector]
                if (actual == null) problems += "evidence '${e.what}' points at a missing vector file ${e.vector}"
                else if (actual != e.kind) problems += "evidence '${e.what}' says ${e.kind} but ${e.vector} is $actual"
            }
            fun official(what: String) = evidence.any { it.what == what && it.kind == "OFFICIAL" && vectorKinds[it.vector] == "OFFICIAL" }
            if (level >= Verification.DOC_SAMPLES) {
                if (!official("tracking response")) problems += "DOC_SAMPLES needs an OFFICIAL vector for the tracking answer the webhook re-fetch trusts ('tracking response')"
                if (signsRequests && !official("request signature")) problems += "DOC_SAMPLES needs an OFFICIAL vector for the outbound request signature"
            }
            if (level >= Verification.SANDBOX) {
                if (sandbox == null) problems += "SANDBOX needs a sandbox block"
                else {
                    val flows = sandbox.getJsonArray("flows", io.vertx.core.json.JsonArray()).map { it.toString() }
                    for (flow in listOf("quote", "create", "label", "tracking") + (if (cancel) listOf("cancel") else emptyList())) {
                        if (flow !in flows) problems += "the sandbox run must cover '$flow'"
                    }
                    if (sandbox.getString("date").isNullOrBlank() || sandbox.getString("environment").isNullOrBlank()) problems += "the sandbox block needs a date and an environment"
                }
            }
            if (level == Verification.LIVE && !Regex("(?im)^\\s*[-*]?\\s*level:?\\s*LIVE\\b").containsMatchIn(verificationMd)) {
                problems += "LIVE must be recorded in VERIFICATION.md"
            }
            return problems
        }
    }

    private fun text(path: String): String = javaClass.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun evidenceOf(json: JsonObject) = json.getJsonArray("evidence").map { it as JsonObject }.map { Evidence(it.getString("what"), it.getString("kind"), it.getString("vector")) }

    private fun vectorKinds(evidence: List<Evidence>) = evidence.associate { it.vector to JsonObject(text("/${it.vector}")).getString("kind") }

    @Test
    fun `S-12 the descriptor level equals verification json and meets its condition`() = env<Unit> { env ->
        val json = JsonObject(text("/verification.json"))
        val level = Verification.valueOf(json.getString("level"))
        assertEquals(level, env.provider.descriptor.verification, "descriptor.verification must equal verification.json")
        val evidence = evidenceOf(json)
        val capabilities: ShippingCapabilities = env.provider.capabilities(env.ctx.settings)
        val md = File("VERIFICATION.md").takeIf { it.isFile }?.readText().orEmpty()
        assertTrue(md.isNotBlank(), "VERIFICATION.md is missing")
        val problems = Rules.violations(level, evidence, vectorKinds(evidence), json.getJsonObject("sandbox"), capabilities.cancel, signsRequests = false, verificationMd = md)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        // Every vector file used is declared, nothing is claimed twice.
        assertEquals(evidence.size, evidence.map { it.what }.toSet().size)
        // VERIFICATION.md states the level in words.
        assertTrue(Regex("(?im)^\\s*[-*]?\\s*level:?\\s*${level.name}\\b").containsMatchIn(md), "VERIFICATION.md must state 'Level: ${level.name}'")
    }

    @Test
    fun `S-12 the rule rejects a level without its evidence`() {
        val self = listOf(Evidence("tracking response", "SELF_DERIVED", "vectors/tracking.json"))
        val official = listOf(Evidence("tracking response", "OFFICIAL", "vectors/tracking.json"))
        val kinds = mapOf("vectors/tracking.json" to "SELF_DERIVED")
        val officialKinds = mapOf("vectors/tracking.json" to "OFFICIAL")
        val sandbox = JsonObject("""{"date":"2026-10-05","environment":"test","flows":["quote","create","label","tracking","cancel"]}""")
        fun check(level: Verification, evidence: List<Evidence>, kinds: Map<String, String>, sandbox: JsonObject? = null, cancel: Boolean = true, signs: Boolean = false, md: String = "") =
            Rules.violations(level, evidence, kinds, sandbox, cancel, signs, md)

        assertTrue(check(Verification.UNVERIFIED, self, kinds).isEmpty())
        assertTrue(check(Verification.UNVERIFIED, emptyList(), emptyMap()).isEmpty())
        assertTrue(check(Verification.DOC_SAMPLES, self, kinds).isNotEmpty(), "self-derived vectors do not earn DOC_SAMPLES")
        assertTrue(check(Verification.DOC_SAMPLES, official, officialKinds).isEmpty())
        assertTrue(check(Verification.DOC_SAMPLES, official, kinds).isNotEmpty(), "evidence kind must match the vector file")
        assertTrue(check(Verification.DOC_SAMPLES, official, officialKinds, signs = true).isNotEmpty(), "a gateway that signs requests needs that vector too")
        assertTrue(check(Verification.SANDBOX, official, officialKinds).isNotEmpty(), "no sandbox block")
        assertTrue(check(Verification.SANDBOX, official, officialKinds, sandbox).isEmpty())
        assertTrue(
            check(Verification.SANDBOX, official, officialKinds, JsonObject("""{"date":"2026-10-05","environment":"test","flows":["quote","create","label","tracking"]}""")).isNotEmpty(),
            "cancel is offered but was not run in the sandbox"
        )
        assertTrue(check(Verification.SANDBOX, official, officialKinds, JsonObject("""{"date":"2026-10-05","environment":"test","flows":["quote","create","label","tracking"]}"""), cancel = false).isEmpty())
        assertTrue(check(Verification.LIVE, official, officialKinds, sandbox).isNotEmpty(), "LIVE is not recorded in VERIFICATION.md")
        assertTrue(check(Verification.LIVE, official, officialKinds, sandbox, md = "Level: LIVE\n").isEmpty())
        assertTrue(check(Verification.UNVERIFIED, listOf(Evidence("tracking response", "OFFICIAL", "vectors/missing.json")), emptyMap()).isNotEmpty())
    }
}
