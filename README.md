# Pano Market shipping plugin template

A complete, compiling, tested shipping carrier plugin for [Pano Market](https://panomc.com), written for an imaginary
carrier called **Example Carrier**. Copy it, run `scripts/rename.sh`, replace the example protocol with your carrier's and
you have a plugin: the build, the license wiring, the quality gates and the 12 shipping provider tests come with it.

It is the sibling of the payment template (`pano-plugin-market-payment-template`): the build, the license package, the gates, the
locale rules and the release configuration are identical; only the provider differs.

## What this is

- `src/main/kotlin/com/panomc/plugins/marketship/example/`: the plugin class, the extension, the provider and the files
  around it (settings, endpoints, client, mapper, units, texts).
- `src/test/`: the tests every shipping plugin must have (S-01 to S-12 of the Pano Market provider test plan), a vector
  file, and the verification record. They run against `spi.testkit.FakeGateway`, a small HTTP server that speaks the
  protocol of the example carrier.
- `build.gradle.kts` (managed, identical in every provider plugin, payment and shipping) with two gates: `checkImports` for
  the sources and `verifyPluginJar` for the finished jar.
- `scripts/rename.sh`, `store/`, `.github/workflows/ci.yml`, `.releaserc.json`, `AGENT.md`, `VERIFICATION.md`.

The example carrier shows every pattern a carrier plugin needs: a token exchange cached in `ctx.state`, rates, a two-step
shipment (draft, then the paid purchase) that survives a failed purchase, labels that come inline or later, cancel, pull
tracking in batches, an unsigned webhook that is only a trigger, a prepaid balance and address validation.

## Requirements

- JDK 21 to run Gradle (the plugin itself is compiled for Java 11 bytecode, which is what Pano runs on; the tests use a
  JDK 21 launcher).
- A Pano release at or above `panoVersion` and Pano Market at or above `marketApiVersion` (both in `gradle.properties`).
- Network access to the GitHub releases of `PanoMC/Pano` and `PanoMC/pano-plugin-market` on the first build, unless you
  pass local jars (below).

## Quick start

```sh
scripts/rename.sh my-carrier "My Carrier"   # once, in a fresh copy
./gradlew build
```

The slug is lower case letters, digits and single hyphens, 2 to 20 characters, starting with a letter. It becomes:

| Thing | Value for `my-carrier` |
|---|---|
| provider id | `my-carrier` |
| plugin id, store resource id, jar name | `pano-plugin-market-shipping-my-carrier` |
| root package | `com.panomc.plugins.marketship.mycarrier` |
| classes | `MyCarrierPlugin`, `MyCarrierExtension`, `MyCarrierProvider`, ... |
| i18n namespace | `plugins.pano-plugin-market-shipping-my-carrier.*` |

The jar is `build/libs/pano-plugin-market-shipping-my-carrier-<version>.jar`. Put it into the `plugins` folder of a Pano that has
Pano Market installed. The store resource is named `Market Shipping: <name>`.

## How dependencies resolve

| Context | Platform classes | Market classes | Command |
|---|---|---|---|
| Template / third party (this repository) | Ivy: `Pano-<panoVersion>.jar` from the `PanoMC/Pano` release | Ivy: `pano-plugin-market-api-<marketApiVersion>.jar` from the `PanoMC/pano-plugin-market` release | `./gradlew build` |
| Offline or an unreleased combination | `-PpanoJar=<path>` | `-PmarketApiJar=<path>` | `./gradlew build -PpanoJar=... -PmarketApiJar=...` |
| Platform fallback | `-PpanoSource=jitpack` | as above | |
| Embedded in a Pano checkout (`bootstrap=true`) | `project(":Pano")` | the market project's classes | from the platform root: `./gradlew :plugins:<folder>:jar` |

Market classes and the platform are `compileOnly`: they come from the host at run time and must never be in your jar.
Only `com.panomc.plugins.market.spi.*` may be imported.

## What you may and may not do

A shipping provider never:

1. trusts the body of an unsigned webhook: it only learns which shipment changed and then re-reads the shipment from the
   carrier (`capabilities.webhookSigned = false`, enforced by the contract test);
2. sets `eventKey` for a notification that is only a trigger (the body is the same for every state);
3. spends money twice: every call that buys a label carries an idempotency key derived from `merchantReference`, and a
   `Failed` result of a two-step carrier hands the object created before the failure back as `carrierReference`;
4. shades or copies market, Kotlin, coroutines, Vert.x or Gson classes, or uses a root package inside
   `com.panomc.plugins.market`;
5. declares routes, DAOs for market tables, timers, or its own SMTP / database access for shipment state;
6. blocks the event loop (no blocking HTTP client, no `Thread.sleep`);
7. logs or returns secrets, access tokens, or the buyer's address and phone number (the example logs a call as method, path
   and body size);
8. keeps correctness-relevant state only in memory (use `providerData` or `ctx.state`);
9. throws for an inbound request it merely does not understand (return `ignored` with the reply the carrier expects);
10. builds a redirect target or an outbound URL from inbound request input (a shipment id from a webhook is checked against a
    strict pattern and against market's own shipments before it goes into a URL);
11. sends a parcel weight or size without converting it: weights and sizes go through `ExampleUnits` (integer arithmetic that
    rounds up).

Libraries:

| Need | Use | Never |
|---|---|---|
| Outbound HTTP | `ctx.http` with `.timeout(15_000)` on every request and `ctx.log.exchange(...)` | vendor SDKs, OkHttp, Apache HttpClient, `java.net.http`, `HttpURLConnection`, your own `WebClient` |
| JSON | `io.vertx.core.json.JsonObject` / `JsonArray` | Jackson annotations, kotlinx.serialization, a shaded Gson |
| HMAC / hashes / RSA / ECDSA | JDK `javax.crypto.Mac`, `MessageDigest`, `java.security.Signature`; constant-time compare with `MessageDigest.isEqual` | BouncyCastle (it breaks the jar size limit) |
| Anything else | `shadedDependencies` + `shadedRelocations` in `gradle.properties`, Java 11 bytecode | un-relocated shading |

`checkImports` fails the build on the import rules (MP-I01 to MP-I05) and `verifyPluginJar` on the jar rules (MP-J01 to
MP-J09: nothing from the host inside the jar, the three locale files and the logo present, class files at most Java 11,
the jar at most 9 500 000 bytes, a manifest with `market-spi: shipping` and no version constraint on the dependency).

If your carrier signs its webhooks, add a `<Cls>Signature.kt` with pure `sign` / `verify` functions (see the payment
template's `ExampleSignature.kt`), set `webhookSigned = true`, and the unsigned-webhook tests of the contract suite stop
applying.

## Settings schema and locales

The settings form is described in code (`settingsSchema { }` in the provider): secret fields with `secret(...)`, the
read-only webhook URL with `webhookUrl(...)`, the sender address and the default parcel with the SPI helpers
`senderAddress()` / `defaultParcel()` (market reads those reserved keys to fill `QuoteRequest.from`), an action
`test-connection`. Every label, help text, group name, action label and error text of the plugin itself is
`LocalizedText.key("plugins.<pluginId>.<path>", "<English text>")`, all declared in `ExampleTexts.kt`, and the same path
exists in `src/main/resources/locales/en-US.json`, `tr.json` and `ru.json`. `LocaleCompletenessTest` fails when a key is
missing in one file, when the files differ, when a value is empty, when an English text differs from its fallback in the
code, or when a key is not used by anything.

## The slot view (optional UI)

`src/theme/views/ShippingNote.svelte` is a complete example of a view a carrier plugin puts into the Market's checkout: one file,
and its `<script module>` says where it goes.

```svelte
<script module>
  export const view = { slot: 'market:checkout:shipping', id: "example" };
</script>
```

The Market renders the slot with `quote`, `methodId`, `address` and `onchange` (call it with a patch of the checkout draft, e.g.
`{ shippingMethodId }`) and shows every item, so compare `methodId` first when the carrier owns only some of the methods;
a pickup point picker starts from this view. The build is the kit preset in `rollup.config.js` (`bun run build`,
`bunx pano-plugin check --strict --styles badge`); a plugin that needs no UI deletes `rollup.config.js`, the `package.json`
dependencies and `src/theme/`, and builds as a Kotlin-only plugin. `scripts/rename.sh` renames the namespace of the view along
with the rest.

## Testing

```sh
./gradlew test        # also part of ./gradlew build
```

- **Vectors** in `src/test/resources/vectors/*.json` hold sample answers of the carrier: `OFFICIAL` (copied from the
  carrier's documentation, `source` names the page) or `SELF_DERIVED` (written from the documented protocol). Test names
  start with `official -` or `self-derived -`.
- **FakeGateway** (`com.panomc.plugins.market.spi.testkit`) is a Vert.x HTTP server on a loopback port that records
  every request (raw bytes included) and answers what a test scripts. The provider is pointed at it through the
  optional `endpoints` constructor argument; nothing in the tests contacts a real host.
- **The contract suite** (`ShippingProviderContractTest`) checks capabilities, garbage inbound traffic, secrets in logs, the
  re-fetch of an unsigned webhook, identical unsigned bodies with different states, and the `carrierReference` of a failed
  create. A plugin supplies the two scenarios it needs; none of the checks may end up skipped.
- **Verification levels** (`UNVERIFIED`, `DOC_SAMPLES`, `SANDBOX`, `LIVE`) are written in
  `src/test/resources/verification.json` and `VERIFICATION.md`, must equal `descriptor.verification`, and are checked
  by `VerificationLevelTest`: a plugin cannot claim a level without the evidence for it.
- `CoverageTest` fails when one of S-01 to S-12 has no test, and when a `@Test` method returns a value: JUnit silently
  skips such a method (a Kotlin test written as `fun x() = env { ... }` whose last expression is not `Unit`; write
  `env<Unit> { ... }`).

| Id | What |
|---|---|
| S-01 | the shared contract suite |
| S-02 | id, descriptor, schema, locales |
| S-03 | unit conversion (grams to kg, mm to cm, desi) with boundary values |
| S-04 | `quote`: rates, unavailable services, timeout |
| S-05 | `createShipment`: idempotent on `merchantReference`, insufficient balance, invalid address, resume after a failed purchase |
| S-06 | labels inline or `fetchLabel` not ready, then ready |
| S-07 | tracking: every carrier status mapped, batch size |
| S-08 | the unsigned webhook is re-fetched before anything is reported |
| S-09 | `cancelShipment` cancelled / refused |
| S-10 | the access token cache in `ctx.state` (reuse, expiry, early revocation, `compareAndSet`) |
| S-11 | redaction, license guard, test-mode endpoints, no host literal outside `ExampleEndpoints` |
| S-12 | the verification level |

## Making it a paid plugin

Set `licenseRequired=true` in `gradle.properties` (and `pluginLicense` to your licence). A release build then fails
(`MP-B02`) unless a license key was embedded: pass `-PlicenseServer=dev|prod` or set `PANO_LICENSE_SERVER` in CI. The store
resource id must equal the plugin id. A local premium test uses `-PlicenseServer=dev` against `api-dev.panomc.com`. The
license classes in `com.panomc.plugins.license` are managed files: never edit them. A free build (the default) makes the
license code a no-op.

## Release configuration

`.releaserc.json` holds the semantic-release setup: prerelease branch `dev`, stable `main`, upload to the store with
`@PanoMC/semantic-release-pano` (resource id = plugin id), and the jar as a GitHub release asset. It uses
`semantic-release-monorepo` because the template is also the source of the folders of a multi-plugin repository; in a
repository of its own, remove the `extends` line and the `repositoryUrl`. `store/store.json` and `store/description.html`
describe the store resource. The commit messages must be conventional commits; a `build:` commit releases nothing.

## Licence

This template is MIT licensed (`LICENSE`). The licence of the plugin you build from it is yours: change `pluginLicense`
in `gradle.properties`, `license` in `store/store.json` and the `LICENSE` file.
