// MANAGED FILE: byte-identical in every Pano Market provider plugin (payment and shipping, template and
// private repos). Edit it only in pano-plugin-market-payment-template, then run the sync script.
// Everything plugin-specific lives in gradle.properties.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.jar.JarFile
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"            // must equal pano-web-platform/build.gradle.kts
    id("com.gradleup.shadow") version "8.3.8"
}

group = "com.panomc.plugins"
version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "local-build"

fun prop(name: String): String = (findProperty(name) as String?)?.trim().orEmpty()
fun csv(name: String): List<String> = prop(name).split(',').map { it.trim() }.filter { it.isNotEmpty() }

val bootstrap = prop("bootstrap").toBoolean()                 // true only inside the platform build
val noui = hasProperty("noui")
val pluginsDir: File? = if (rootProject.extra.has("pluginsDir")) rootProject.extra["pluginsDir"] as File else null // absent standalone: copyJar is a no-op
val pluginId = prop("pluginId")
val rootPackage = prop("rootPackage")
val licenseRequired = prop("licenseRequired").toBoolean()
val hasUi = file("rollup.config.js").exists()
val panoJar = prop("panoJar").ifEmpty { System.getenv("PANO_PLATFORM_JAR").orEmpty() }
val marketApiJar = prop("marketApiJar").ifEmpty { System.getenv("MARKET_API_JAR").orEmpty() }
val panoSource = prop("panoSource").ifEmpty { "github" }      // github | jitpack

// api-level (pano-api migrate-v1): "panoApiLevel" of the pano-web-platform tree this plugin is built in, else "current" from
// the pano-api-level.properties inside the Pano jar on compileClasspath. `apiLevel=` in gradle.properties lowers it.
val panoApiLevel: String? by lazy {
    (findProperty("apiLevel") as String?)
        ?: (rootProject.findProperty("panoApiLevel") as String?)
        ?: configurations.findByName("compileClasspath")?.files?.firstNotNullOfOrNull { jar ->
            if (!jar.isFile || !jar.name.endsWith(".jar")) null
            else ZipFile(jar).use { zip ->
                zip.getEntry("pano-api-level.properties")?.let { entry ->
                    Properties().apply { load(zip.getInputStream(entry)) }.getProperty("current")
                }
            }
        }
}

val marketSpiKind = when {
    rootPackage.contains(".marketpay.") -> "payment"
    rootPackage.contains(".marketship.") -> "shipping"
    else -> throw GradleException("MP-B03: rootPackage '$rootPackage' must contain .marketpay. or .marketship.")
}

repositories {
    mavenCentral()
    if (!bootstrap) {
        fun githubReleases(repo: String, group: String) = ivy {
            url = uri("https://github.com/$repo/releases/download")
            patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup(group) }
        }
        if (panoJar.isEmpty() && panoSource == "github") githubReleases("PanoMC/Pano", "panomc.platform")
        if (panoJar.isEmpty() && panoSource == "jitpack") maven("https://jitpack.io") { content { includeGroup("com.github.panomc") } }
        if (marketApiJar.isEmpty()) githubReleases("PanoMC/pano-plugin-market", "panomc.market")
    }
}

val hostLibs = listOf(
    "org.pf4j:pf4j:${prop("pf4jVersion")}",
    "io.vertx:vertx-core:${prop("vertxVersion")}",
    "io.vertx:vertx-web:${prop("vertxVersion")}",
    "io.vertx:vertx-web-client:${prop("vertxVersion")}",
    "io.vertx:vertx-lang-kotlin:${prop("vertxVersion")}",
    "io.vertx:vertx-lang-kotlin-coroutines:${prop("vertxVersion")}",
    "com.google.code.gson:gson:${prop("gsonVersion")}",
    "org.springframework:spring-context:${prop("springContextVersion")}",
)

dependencies {
    // Explicit compileOnly stdlib: stops the Kotlin plugin from adding it to `implementation` (it would be shaded).
    compileOnly(kotlin("stdlib-jdk8")); testImplementation(kotlin("stdlib-jdk8"))
    hostLibs.forEach { compileOnly(it); testImplementation(it) }

    if (bootstrap) {
        val market = findProject(":plugins:pano-plugin-market")
            ?: throw GradleException("MP-B01: pano-plugin-market must be checked out under plugins/ to build $pluginId")
        evaluationDependsOn(market.path)
        compileOnly(project(":Pano")); testCompileOnly(project(":Pano"))
        compileOnly(market)
        // Classes only (no resources, so no bun build of market's UI and no dependency on market's disabled jar task).
        testImplementation(market.extensions.getByType<SourceSetContainer>()["main"].output.classesDirs)
    } else {
        val pano: Any = when {
            panoJar.isNotEmpty() -> files(panoJar)
            panoSource == "jitpack" -> "com.github.panomc:pano:v${prop("panoVersion")}"
            else -> "panomc.platform:Pano:${prop("panoVersion")}"
        }
        val marketApi: Any = if (marketApiJar.isNotEmpty()) files(marketApiJar)
                             else "panomc.market:pano-plugin-market-api:${prop("marketApiVersion")}"
        add("compileOnly", pano); add("testCompileOnly", pano)
        add("compileOnly", marketApi); add("testImplementation", marketApi)
    }

    csv("shadedDependencies").forEach { implementation(it) }

    testImplementation("org.junit.jupiter:junit-jupiter-api:${prop("junitVersion")}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${prop("junitVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
}

// ---------------------------------------------------------------------------------------------
// License key (embedded into the jar as PluginBuildConstants)
// ---------------------------------------------------------------------------------------------

val generatedLicenseSrcDir = layout.buildDirectory.dir("generated/license-source")

/**
 * Resolves the panomc.com license verification public key for build-time embedding.
 *
 * Property / env semantics (highest priority first):
 *   - `-PpanoLicensePublicKey=<base64|PEM>`  explicit override
 *   - `PANO_LICENSE_PUBLIC_KEY`             same when property unset
 *   - `-PlicenseServer=dev|prod|<url>`       auto-fetch from the license server
 *   - `PANO_LICENSE_SERVER`                 same when property unset (e.g. CI)
 *   - (none)                                 empty key, the plugin builds as FREE
 *
 * The result is cached under `build/license-key-cache/`.
 */
fun resolveLicensePublicKey(): String {
    val key = resolveLicensePublicKeyUnchecked()
    if (key.isNotEmpty()) requireDecodableLicenseKey(key)
    return key
}

/**
 * MP-B02: a non-empty key must decode exactly the way PluginLicenseClient.decodePublicKey() decodes it at run time
 * (Base64 X.509 SubjectPublicKeyInfo, RSA). The runtime swallows decode errors and then treats the plugin as FREE, so a
 * malformed key would silently ship a "premium" jar that runs without a license. Fail the build instead.
 */
fun requireDecodableLicenseKey(key: String) {
    try {
        val der = Base64.getDecoder().decode(key)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    } catch (e: Exception) {
        throw GradleException(
            "MP-B02: $pluginId: the license public key is not a Base64 X.509 RSA key (${e.message}). " +
                "Check -PpanoLicensePublicKey / PANO_LICENSE_PUBLIC_KEY / the key served by the license server."
        )
    }
}

fun resolveLicensePublicKeyUnchecked(): String {
    val explicitProp = stripPemAndWhitespace(prop("panoLicensePublicKey")).takeIf { it.isNotEmpty() }
    val explicitEnv = System.getenv("PANO_LICENSE_PUBLIC_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(::stripPemAndWhitespace)?.takeIf { it.isNotEmpty() }
    val explicit = explicitProp ?: explicitEnv
    if (explicit != null) return explicit

    val serverProp = prop("licenseServer")
    val serverEnv = System.getenv("PANO_LICENSE_SERVER")?.trim().orEmpty()
    val server = serverProp.ifEmpty { serverEnv }
    if (server.isEmpty()) return ""

    val baseUrl = when (server.lowercase()) {
        "dev" -> "https://api-dev.panomc.com"
        "prod", "production" -> "https://api.panomc.com"
        else -> server.removeSuffix("/")
    }
    val cacheRoot = layout.buildDirectory.dir("license-key-cache").get().asFile
    cacheRoot.mkdirs()
    val cacheFile = cacheRoot.resolve(baseUrl.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".pem")

    if (!cacheFile.exists() || cacheFile.length() == 0L) {
        logger.lifecycle("Fetching license public key from $baseUrl...")
        val payload = try {
            URI("$baseUrl/platform/api/licenses/public-key").toURL()
                .openConnection()
                .apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                }
                .getInputStream()
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            throw GradleException(
                "Failed to fetch license public key from $baseUrl/platform/api/licenses/public-key: " +
                    "${e.message}. Either bring the license server up, drop the -PlicenseServer flag " +
                    "(builds the plugin as FREE), or set -PpanoLicensePublicKey=<base64> manually.",
                e
            )
        }
        cacheFile.writeText(payload)
    }
    return parsePublicKeyResponse(cacheFile.readText())
}

fun parsePublicKeyResponse(rawJson: String): String {
    val keyField = Regex("\"publicKeyBase64\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1)
    if (!keyField.isNullOrBlank()) return stripPemAndWhitespace(keyField)
    val pemField = Regex("\"publicKey\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        .find(rawJson)?.groupValues?.get(1)
    if (!pemField.isNullOrBlank()) {
        val unescaped = pemField.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        return stripPemAndWhitespace(unescaped)
    }
    throw GradleException(
        "License server response did not contain `publicKeyBase64` or `publicKey`. Got: ${rawJson.take(200)}"
    )
}

fun stripPemAndWhitespace(input: String): String =
    input
        .replace(Regex("-----BEGIN [^-]+-----"), "")
        .replace(Regex("-----END [^-]+-----"), "")
        .replace(Regex("\\s+"), "")

// ---------------------------------------------------------------------------------------------
// Source gate: checkImports (MP-I01 to MP-I05)
// ---------------------------------------------------------------------------------------------

val pluginClassSimpleName = prop("pluginClass").substringAfterLast('.')
val classPrefix = pluginClassSimpleName.removeSuffix("Plugin")
val licensePackage = "com.panomc.plugins.license"

val forbiddenImport = Regex(
    "^\\s*import\\s+(io\\.vertx\\.ext\\.web\\.(Route|Router|RoutingContext|\\*)(\\s|;|$|\\.)|" +
        "java\\.net\\.http\\.|okhttp3\\.|org\\.apache\\.http|java\\.sql\\.|io\\.vertx\\.sqlclient\\.|io\\.vertx\\.mysqlclient\\.)"
)

/** Code part of a source line: comment-only lines give an empty string, a trailing // comment is cut off. */
fun codeOf(line: String): String {
    val t = line.trim()
    if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) return ""
    val cut = Regex("(^|\\s)//").find(line)
    return if (cut != null) line.substring(0, cut.range.first) else line
}

fun checkImportsOf(root: File): List<String> {
    val violations = mutableListOf<String>()
    val rootPath = root.absoluteFile
    rootPath.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
        val rel = file.relativeTo(rootPath).invariantSeparatorsPath
        val inLicense = rel.startsWith("com/panomc/plugins/license/")
        val platformAllowed = inLicense || file.name == "${classPrefix}Plugin.kt" || file.name == "${classPrefix}Extension.kt"
        var packageSeen = false
        file.readLines().forEachIndexed { index, raw ->
            val at = "$rel:${index + 1}"
            val line = codeOf(raw)
            if (line.isBlank()) return@forEachIndexed
            val trimmed = line.trim()
            if (trimmed.startsWith("package ") && !packageSeen) {
                packageSeen = true
                val pkg = trimmed.removePrefix("package ").removeSuffix(";").trim()
                val ok = pkg == rootPackage || pkg.startsWith("$rootPackage.") || pkg == licensePackage || pkg.startsWith("$licensePackage.")
                if (!ok) violations += "MP-I05 $at: package '$pkg' is outside $rootPackage and $licensePackage"
            }
            if (Regex("^\\s*import\\s+com\\.panomc\\.plugins\\.market\\.(?!spi\\.)").containsMatchIn(line))
                violations += "MP-I01 $at: only com.panomc.plugins.market.spi.* may be imported: ${trimmed}"
            if (Regex("^\\s*import\\s+com\\.panomc\\.platform\\.").containsMatchIn(line) && !platformAllowed)
                violations += "MP-I02 $at: com.panomc.platform.* is only allowed in ${classPrefix}Plugin.kt, ${classPrefix}Extension.kt and the license package: ${trimmed}"
            if (forbiddenImport.containsMatchIn(line))
                violations += "MP-I03 $at: forbidden import: ${trimmed}"
            if (line.contains("Thread.sleep(")) violations += "MP-I04 $at: Thread.sleep is forbidden"
            if (Regex("\\brunBlocking\\b").containsMatchIn(line)) violations += "MP-I04 $at: runBlocking is forbidden in main sources"
            if (line.contains("System.currentTimeMillis()")) violations += "MP-I04 $at: use ctx.now() instead of System.currentTimeMillis()"
        }
        if (!packageSeen) violations += "MP-I05 $rel: no package declaration"
    }
    return violations
}

tasks.register("checkImports") {
    group = "verification"
    description = "Source gate MP-I01 to MP-I05: imports and package of the provider sources."
    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    val marker = layout.buildDirectory.file("checkImports.ok")
    inputs.files(fileTree(sourceDir) { include("**/*.kt") })
    inputs.property("rootPackage", rootPackage)
    inputs.property("classPrefix", classPrefix)
    outputs.file(marker)
    doLast {
        marker.get().asFile.delete()
        val violations = checkImportsOf(sourceDir.asFile)
        if (violations.isNotEmpty()) {
            throw GradleException("checkImports failed:\n" + violations.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}

// ---------------------------------------------------------------------------------------------
// Artifact gate: verifyPluginJar (MP-J01 to MP-J09)
// ---------------------------------------------------------------------------------------------

val forbiddenJarPrefixes = listOf(
    "com/panomc/plugins/market/", "com/panomc/platform/", "kotlin/", "kotlinx/", "io/vertx/", "io/netty/",
    "com/google/gson/", "org/springframework/", "org/pf4j/", "org/slf4j/",
)
val requiredJarEntries = listOf("config.conf", "logo.png", "locales/en-US.json", "locales/tr.json", "locales/ru.json")

fun verifyJarFile(jar: File, expectUi: Boolean, premiumExpected: Boolean): List<String> {
    val violations = mutableListOf<String>()
    val rootPath = rootPackage.replace('.', '/') + "/"
    ZipFile(jar).use { zip ->
        val entries = zip.entries().toList()
        val names = entries.map { it.name }.toSet()
        val j01 = mutableListOf<String>()
        val j02 = mutableListOf<String>()
        val j05 = mutableListOf<String>()
        entries.filter { !it.isDirectory }.forEach { e ->
            val n = e.name
            if (forbiddenJarPrefixes.any { n.startsWith(it) }) j01 += n
            if (n.endsWith(".class")) {
                if (!(n.startsWith(rootPath) || n.startsWith("com/panomc/plugins/license/"))) j02 += n
                val header = zip.getInputStream(e).use { it.readNBytes(8) }
                if (header.size < 8) {
                    j05 += "$n (truncated class file)"
                } else {
                    val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                    if (major > 55) j05 += "$n (major $major)"
                }
            }
        }
        j01.sorted().take(20).forEach { violations += "MP-J01 forbidden entry (host library or market class bundled): $it" }
        j02.sorted().take(20).forEach { violations += "MP-J02 class outside $rootPath and com/panomc/plugins/license/: $it" }
        requiredJarEntries.filter { it !in names }.forEach { violations += "MP-J03 required entry missing: $it" }
        val hasUiZip = "plugin-ui.zip" in names
        if (expectUi && !hasUiZip) violations += "MP-J04 plugin-ui.zip is missing but rollup.config.js exists"
        if (!expectUi && hasUiZip) violations += "MP-J04 plugin-ui.zip is bundled but there is no rollup.config.js"
        j05.sorted().take(20).forEach { violations += "MP-J05 class file newer than Java 11: $it" }
        if (jar.length() > 20_000_000L) violations += "MP-J06 jar is ${jar.length()} bytes, the limit is 20000000"

        val attrs = JarFile(jar).use { it.manifest?.mainAttributes }
        if (attrs == null) {
            violations += "MP-J07 jar has no manifest"
        } else {
            if (attrs.getValue("id") != pluginId) violations += "MP-J07 manifest id '${attrs.getValue("id")}' != pluginId '$pluginId'"
            val deps = attrs.getValue("dependencies").orEmpty()
            if (deps.split(',').map { it.trim() }.none { it == "pano-plugin-market" })
                violations += "MP-J07 manifest dependencies '$deps' must contain pano-plugin-market"
            if (deps.contains('@')) violations += "MP-J07 manifest dependencies '$deps' must not carry a version constraint"
            if (attrs.getValue("freemium") != null) violations += "MP-J07 manifest must not carry a freemium attribute"
            if (premiumExpected && attrs.getValue("license-mode") != "premium")
                violations += "MP-J08 licenseRequired=true and version=$version but license-mode is '${attrs.getValue("license-mode")}'"
        }
        val logo = entries.firstOrNull { it.name == "logo.png" }
        if (logo != null && logo.size > 65_536L) violations += "MP-J09 logo.png is ${logo.size} bytes, the limit is 65536"
    }
    return violations
}

// ---------------------------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------------------------

val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val isWindows = os.contains("win")
val isMac = os.contains("mac")
val isLinux = os.contains("nix") || os.contains("nux") || os.contains("linux")
val isAarch64 = arch.contains("aarch64") || arch.contains("arm64")
val isX64 = arch.contains("x86_64") || arch.contains("amd64")

tasks {
    register("generatePluginBuildConstants") {
        val outputDir = generatedLicenseSrcDir
        val versionString = version.toString()
        val required = licenseRequired

        outputs.dir(outputDir)
        // Cache invalidation: any input that can change the embedded key or the identity re-generates the file.
        inputs.property("licenseServer", prop("licenseServer"))
        inputs.property("panoLicensePublicKey", prop("panoLicensePublicKey"))
        inputs.property("envLicenseServer", System.getenv("PANO_LICENSE_SERVER").orEmpty())
        inputs.property("envLicensePublicKey", System.getenv("PANO_LICENSE_PUBLIC_KEY").orEmpty())
        inputs.property("licenseRequired", required)
        inputs.property("version", versionString)

        doLast {
            // Single-line Base64 SubjectPublicKeyInfo body, or empty for a free build.
            val cleanedPubKey = resolveLicensePublicKey()
            if (cleanedPubKey.isEmpty() && required && versionString != "local-build") {
                throw GradleException(
                    "MP-B02: $pluginId has licenseRequired=true and version $versionString but no license key source " +
                        "was given (set PANO_LICENSE_SERVER=dev|prod, -PlicenseServer=dev|prod or -PpanoLicensePublicKey)."
                )
            }
            val file = outputDir.get().asFile.resolve("com/panomc/plugins/license/PluginBuildConstants.kt")
            file.parentFile.mkdirs()
            val escapedPubKey = cleanedPubKey.replace("\\", "\\\\").replace("\"", "\\\"")
            file.writeText(
                """package com.panomc.plugins.license

internal object PluginBuildConstants {
    const val VERSION: String = "$versionString"
    const val PANO_PUBLIC_KEY_BASE64: String = "$escapedPubKey"
    const val LICENSED: Boolean = ${cleanedPubKey.isNotEmpty()}
}
"""
            )
        }
    }

    if (hasUi) {
        val bunPlatform = when {
            isWindows && isX64 -> "bun-windows-x64"
            isMac && isX64 -> "bun-darwin-x64"
            isMac && isAarch64 -> "bun-darwin-aarch64"
            isLinux && isX64 -> "bun-linux-x64"
            isLinux && isAarch64 -> "bun-linux-aarch64"
            else -> throw RuntimeException("Unsupported OS or Architecture")
        }
        val bunVersion = "1.2.0"
        val bunUrl = "https://github.com/oven-sh/bun/releases/download/bun-v$bunVersion/$bunPlatform.zip"
        val bunDir = File(layout.buildDirectory.asFile.get().absolutePath, "bun")
        val bunBinDir = File(bunDir, bunPlatform)
        val bunBin = if (isWindows) File(bunBinDir, "bun.exe") else File(bunBinDir, "bun")

        register("installBun") {
            doLast {
                if (!bunBin.exists()) {
                    println("Couldn't find Bun, downloading: $bunUrl")
                    val zipFile = File(bunDir, "$bunPlatform.zip")
                    zipFile.parentFile.mkdirs()
                    URI(bunUrl).toURL().openStream().use { input -> zipFile.outputStream().use { output -> input.copyTo(output) } }
                    copy {
                        from(zipTree(zipFile))
                        into(bunDir)
                    }
                    if (!isWindows) bunBin.setExecutable(true)
                    zipFile.delete()
                    println("Bun successfully downloaded: ${bunBin.absolutePath}")
                } else {
                    println("Bun is downloaded already: ${bunBin.absolutePath}")
                }
            }
        }

        register("installPluginUIDependencies", Exec::class) {
            dependsOn("installBun")
            commandLine(bunBin.absolutePath, "install")
        }

        register("buildUI", Exec::class) {
            dependsOn("installPluginUIDependencies")
            commandLine(bunBin.absolutePath, "run", "build")
        }

        register("zipPluginUI", Zip::class) {
            dependsOn("buildUI")
            from("src/main/resources/plugin-ui")
            archiveFileName.set("plugin-ui.zip")
            destinationDirectory.set(file("src/main/resources"))
            doLast {
                val pluginUIFolder = file("src/main/resources/plugin-ui")
                if (pluginUIFolder.exists()) pluginUIFolder.deleteRecursively()
            }
            outputs.upToDateWhen { false }
        }

        if (!noui) {
            named("build") { dependsOn("zipPluginUI") }
            named("processResources") { dependsOn("zipPluginUI") }
        }
    }

    shadowJar {
        manifest {
            attributes["id"] = pluginId
            panoApiLevel?.let { attributes["api-level"] = it }
            attributes["name"] = prop("pluginName")
            prop("pluginDescription").takeIf { it.isNotEmpty() }?.let { attributes["description"] = it }
            attributes["pano-version"] = prop("pluginPanoVersion")
            attributes["main-class"] = prop("pluginClass")
            attributes["version"] = version
            attributes["developer"] = prop("pluginDeveloper")
            prop("pluginLicense").takeIf { it.isNotEmpty() }?.let { attributes["license"] = it }
            prop("pluginSourceUrl").takeIf { it.isNotEmpty() }?.let { attributes["source-url"] = it }
            prop("pluginDependencies").takeIf { it.isNotEmpty() }?.let { attributes["dependencies"] = it }
            prop("pluginRequires").takeIf { it.isNotEmpty() }?.let { attributes["requires"] = it }
            // Read at execution time through the generated constants task: computed from the same key source.
            attributes["market-spi"] = marketSpiKind
        }
        // The embedded key decides the mode. A provider input keeps the jar from being reused when the key source changes.
        inputs.property("licenseMode", provider { if (resolveLicensePublicKey().isNotEmpty()) "premium" else "free" })
        doFirst {
            manifest.attributes["license-mode"] = if (resolveLicensePublicKey().isNotEmpty()) "premium" else "free"
        }

        archiveFileName.set("$pluginId-$version.jar")

        dependencies {
            exclude(dependency("io.vertx:vertx-core"))
            exclude { it.moduleGroup == "io.netty" || it.moduleGroup == "org.slf4j" }
            // Annotation-only artifacts that shaded libraries drag in (Gson: error_prone_annotations). They are not needed at
            // run time and would otherwise have to be listed in shadedRelocations.
            exclude(dependency("com.google.errorprone:error_prone_annotations"))
            exclude(dependency("org.jetbrains:annotations"))
            exclude(dependency("org.checkerframework:checker-qual"))
            exclude(dependency("com.google.code.findbugs:jsr305"))
        }
        // Module descriptors of shaded libraries are wrong after relocation; signature files would make the jar unverifiable.
        exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        csv("shadedRelocations").forEach { relocate(it, "$rootPackage.shaded.$it") }
    }

    val verifyPluginJar = register("verifyPluginJar") {
        group = "verification"
        description = "Artifact gate MP-J01 to MP-J09 on the shadow jar."
        val jarProvider = named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
        dependsOn("shadowJar")
        inputs.file(jarProvider)
        inputs.property("rootPackage", rootPackage)
        inputs.property("hasUi", hasUi)
        inputs.property("licenseRequired", licenseRequired)
        inputs.property("version", version.toString())
        doLast {
            val jar = jarProvider.get().asFile
            val premiumExpected = licenseRequired && version.toString() != "local-build"
            val violations = verifyJarFile(jar, hasUi, premiumExpected)
            if (violations.isNotEmpty()) {
                throw GradleException("verifyPluginJar failed for ${jar.name}:\n" + violations.joinToString("\n") { "  $it" })
            }
            logger.lifecycle("verifyPluginJar: ${jar.name} ok")
        }
    }

    register("copyJar") {
        val dir = pluginsDir
        val jarProvider = named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
        dependsOn(verifyPluginJar)
        if (dir != null) {
            doLast {
                dir.mkdirs()
                val src = jarProvider.get().asFile
                src.copyTo(File(dir, src.name), overwrite = true)
            }
        }
        outputs.upToDateWhen { false }
        mustRunAfter("shadowJar")
    }

    jar {
        enabled = false
        dependsOn("shadowJar")
        dependsOn("copyJar")
    }

    named("build") { dependsOn(verifyPluginJar) }

    test {
        useJUnitPlatform()
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
    }

    named("compileKotlin") {
        dependsOn("generatePluginBuildConstants")
        dependsOn("checkImports")
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(11)) }
}

kotlin {
    jvmToolchain(11)
    sourceSets.named("main") { kotlin.srcDir(generatedLicenseSrcDir) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
