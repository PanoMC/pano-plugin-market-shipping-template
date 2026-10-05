package com.panomc.plugins.marketship.example

import com.panomc.plugins.market.spi.common.LocalizedText
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S-02: every i18n key the plugin uses exists in en-US, tr and ru; the three files hold the same keys; no value is empty. */
class LocaleCompletenessTest {
    private val locales = listOf("en-US", "tr", "ru")

    private fun load(locale: String): Map<String, String> {
        val text = javaClass.getResourceAsStream("/locales/$locale.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, json: JsonObject) {
            for (name in json.fieldNames()) {
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                when (val value = json.getValue(name)) {
                    is JsonObject -> walk(path, value)
                    is String -> out[path] = value
                    else -> throw AssertionError("$locale: '$path' is neither a string nor an object")
                }
            }
        }
        walk("", JsonObject(text))
        return out
    }

    /** `{key, fallback}` objects anywhere in a serialised schema. */
    private fun keysIn(json: Any?, into: MutableMap<String, String>) {
        when (json) {
            is JsonObject -> {
                val key = json.getValue("key")
                val fallback = json.getValue("fallback")
                if (key is String && fallback is String && key.startsWith("plugins.")) into[key] = fallback
                json.fieldNames().forEach { keysIn(json.getValue(it), into) }
            }
            is JsonArray -> json.forEach { keysIn(it, into) }
        }
    }

    private fun usedKeys(): Map<String, String> {
        val used = LinkedHashMap<String, String>()
        env { env ->
            keysIn(env.provider.settingsSchema().toJson(), used)
            val descriptor = env.provider.descriptor
            listOfNotNull(descriptor.displayName, descriptor.description, descriptor.checkoutHint).forEach { text(it, used) }
            descriptor.storefrontNotices.forEach { text(it.label, used) }
        }
        ExampleTexts.all.forEach { text(it, used) }
        return used
    }

    private fun text(t: LocalizedText, into: MutableMap<String, String>) {
        val key = requireNotNull(t.key) { "'${t.fallback}' is a literal text: use ExampleTexts" }
        into[key] = t.fallback
    }

    @Test
    fun `S-02 every used key exists in all three locales with the English fallback`() {
        val used = usedKeys()
        assertTrue(used.size >= 18, "only ${used.size} keys found; the collector is broken")
        val prefix = "plugins.${ExampleTexts.PLUGIN_ID}."
        assertTrue(used.keys.all { it.startsWith(prefix) }, "keys outside the plugin namespace: ${used.keys.filterNot { it.startsWith(prefix) }}")
        for (locale in locales) {
            val file = load(locale)
            val missing = used.keys.filter { it.removePrefix(prefix) !in file }
            assertTrue(missing.isEmpty(), "$locale is missing: $missing")
        }
        val english = load("en-US")
        for ((key, fallback) in used) assertEquals(fallback, english[key.removePrefix(prefix)], "the en-US text of $key differs from its fallback in the code")
    }

    @Test
    fun `S-02 the three files have identical key sets and no empty value`() {
        val files = locales.associateWith { load(it) }
        val reference = files.getValue("en-US").keys
        for (locale in locales) {
            val keys = files.getValue(locale).keys
            assertEquals(reference - keys, emptySet<String>(), "$locale lacks keys of en-US")
            assertEquals(keys - reference, emptySet<String>(), "$locale has keys en-US lacks")
            files.getValue(locale).forEach { (key, value) -> assertTrue(value.isNotBlank(), "$locale: '$key' is empty") }
        }
    }

    @Test
    fun `S-02 no locale key is orphaned`() {
        val prefix = "plugins.${ExampleTexts.PLUGIN_ID}."
        val used = usedKeys().keys.map { it.removePrefix(prefix) }.toSet()
        val orphans = load("en-US").keys - used
        assertTrue(orphans.isEmpty(), "locale keys nothing uses: $orphans")
    }

    @Test
    fun `translations are real translations, not copies of the English text`() {
        val english = load("en-US")
        for (locale in listOf("tr", "ru")) {
            val file = load(locale)
            // The gateway's own name is legitimately identical in every language; every sentence must differ.
            val name = ExampleTexts.displayName.fallback
            val copies = english.filter { (key, value) -> value != name && value.trim().contains(' ') && file[key] == value }.keys
            assertTrue(copies.isEmpty(), "$locale repeats the English text for: $copies")
        }
    }
}
