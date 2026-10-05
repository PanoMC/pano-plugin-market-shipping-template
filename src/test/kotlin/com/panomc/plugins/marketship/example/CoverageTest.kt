package com.panomc.plugins.marketship.example

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The template implements S-01 to S-12 of spec 16 section 13.3 for its example carrier. A test of that case is named
 * `S-nn ...`; this one fails when an id has no test, so a plugin built from the template cannot silently drop one. A case
 * that does not apply to a carrier stays as a test that says why (for example S-09 for a carrier without cancel), never deleted.
 */
class CoverageTest {
    @Test
    fun `S-01 to S-12 each have at least one test`() {
        val dir = File("src/test/kotlin")
        assertTrue(dir.isDirectory, "run the tests from the project directory")
        val named = HashSet<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CoverageTest.kt" }.forEach { file ->
            Regex("fun\\s+`(S-\\d\\d)\\b").findAll(file.readText()).forEach { named.add(it.groupValues[1]) }
            // The contract suite and the dynamic factory are named by class / factory comment.
        }
        // S-01 is the inherited contract suite: a test class must extend ShippingProviderContractTest.
        val contract = dir.walkTopDown().any { it.isFile && it.name.endsWith("ContractTest.kt") && it.readText().contains(": ShippingProviderContractTest()") }
        if (contract) named.add("S-01")
        val missing = (1..12).map { "S-%02d".format(it) }.filter { it !in named }
        assertTrue(missing.isEmpty(), "no test for: $missing")
    }

    /**
     * JUnit silently skips a `@Test` method that returns a value: a Kotlin test written as `fun x() = env { ... }` whose last
     * expression is not `Unit` is never run and never reported. Every `@Test` in the template must return void, and the number
     * of `@Test` annotations in the sources must equal the number of methods JUnit can see.
     */
    @Test
    fun `S-01 every test method returns void so that JUnit really runs it`() {
        val dir = File("src/test/kotlin")
        val bad = ArrayList<String>()
        var declared = 0
        var seen = 0
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            declared += Regex("^\\s*@Test\\b", RegexOption.MULTILINE).findAll(text).count()
            val packageName = Regex("^package\\s+(\\S+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@forEach
            for (match in Regex("^(?:internal\\s+|abstract\\s+|open\\s+)*class\\s+(\\w+)", RegexOption.MULTILINE).findAll(text)) {
                val cls = try {
                    Class.forName("$packageName.${match.groupValues[1]}")
                } catch (e: ClassNotFoundException) {
                    continue
                }
                for (m in cls.declaredMethods) {
                    if (m.isAnnotationPresent(org.junit.jupiter.api.Test::class.java)) {
                        seen++
                        if (m.returnType != Void.TYPE) bad.add("${cls.simpleName}.${m.name} returns ${m.returnType.simpleName}")
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "JUnit will not run these (they return a value): $bad")
        // the suite classes that inherit tests from the testkit are not counted in the sources, so only a lower bound can be exact
        assertTrue(seen >= declared, "$declared @Test annotations in the sources but only $seen methods found by reflection")
    }
}
