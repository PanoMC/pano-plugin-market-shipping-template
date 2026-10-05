# Agent brief — <Display name> (`<slug>`)

Read first, in this order: this file → `pano-market-plugin-spec/shipping/<slug>.md` (or the carrier's section of
`global-carriers.md` / `tr-carriers.md` / `aggregators.md`; the only source of protocol truth, do not browse beyond it
unless it says UNVERIFIED and you need the detail) → `pano-market-plugin-spec/design/03-shipping-spi.md` §4, §5, §9 → the
`geliver/` folder as the worked example.

You own only `<slug>/`. Never edit: build.gradle.kts, anything under com/panomc/plugins/license/, another
folder, the repo root, market, the platform. If the SPI cannot express something, stop and report it — do not
work around it.

Fixed values: provider id `<slug>`; plugin id `<plugin id>`; package `<package>`; old settings keys to keep:
<list from the brief>.

Do, in order:
1. Settings + schema (sender address and default parcel through `senderAddress()` / `defaultParcel()`) + locales (tr, en-US, ru)
   + descriptor + capabilities (conservative where UNVERIFIED).
2. Units and mapper as pure functions, with vector tests first (OFFICIAL where the docs give samples): weights, sizes,
   statuses, rates, tracking events.
3. Client (ctx.http only, 15 s timeouts, ctx.log.exchange, error table of spec 16 §8.5, token cache in ctx.state when the
   carrier hands out tokens).
4. quote, createShipment (idempotent on merchantReference; two-step carriers hand the object created before a failed paid
   step back as carrierReference), fetchLabel, cancelShipment, track, handleInbound (unsigned webhook: re-fetch before reporting),
   balance — only what the brief documents.
5. Flow test against FakeGateway; contract test; verification.json + VERIFICATION.md.
6. store/store.json + description.html; logo.png (≤ 64 KB).

Done means: `./gradlew :plugins:pano-plugin-market-shipping:<slug>:build -Pnoui` is green from the platform
root (one Gradle run at a time, wrapped in `systemd-run --user --scope -p MemoryMax=6G`), every test S-01 to S-12 of spec 16
§13.3 exists and passed in a run you actually made, no TODO left, VERIFICATION.md lists each UNVERIFIED item
from the brief and how the code behaves if the assumption is wrong.

Commit: one commit touching only `<slug>/`, message `feat: added <Display name> shipping provider`. No push.
Report: capabilities chosen, verification level, deviations from the brief, SPI gaps.

Rules the brief relies on (also in the repo-root `AGENT.md`): English only; no secrets in code, tests or logs;
never `pkill`; never push; domain `panomc.com`; customer-facing text names only the carrier itself.

## How this folder maps to the steps above

The code in this folder is a complete, tested plugin for an imaginary carrier; replace its protocol, keep its shape.

| Step | Files |
|---|---|
| 1 | `<Cls>Provider.kt` (`settingsSchema`, `capabilities`, `descriptor`), `<Cls>Settings.kt` (every key as a constant, the token state key), `<Cls>Texts.kt` (every text as an i18n key), `src/main/resources/locales/{en-US,tr,ru}.json` |
| 2 | `<Cls>Units.kt`, `<Cls>Mapper.kt` (pure: no `ctx`, no I/O, never a `Double`), `src/test/resources/vectors/*.json`, `<Cls>UnitsTest.kt`, `<Cls>MapperTest.kt` |
| 3 | `<Cls>Client.kt` (all outbound HTTP; the error mapping of spec 16 §8.5 and the token cache live in it), `<Cls>Endpoints.kt` (the only file with a host name) |
| 4 | `<Cls>Provider.kt` (guard → settings → client → map → return) |
| 5 | `<Cls>FlowTest.kt`, `<Cls>InboundTest.kt`, `<Cls>ProviderTest.kt`, `<Cls>ContractTest.kt`, `VerificationLevelTest.kt`, `src/test/resources/verification.json`, `VERIFICATION.md` |
| 6 | `store/store.json`, `store/description.html`, `src/main/resources/logo.png` |

If the carrier signs its webhooks, add `<Cls>Signature.kt` (pure `sign` / `verify`, vectors) as the payment template does and
set `webhookSigned = true`.

Rules of thumb that the tests enforce (do not weaken a test to make it pass; fix the code or report the SPI gap):

- `license.assertLicensed()` is the first statement of `quote`, `createShipment`, `handleInbound` and `track`, never of the
  pure entry points (S-11).
- An unsigned webhook is never believed: read the shipment id from it, check the id against `ctx.shipments`, re-read the
  shipment from the carrier and report only that answer. `eventKey` stays unset (S-08, contract suite).
- `createShipment` carries an idempotency key derived from `merchantReference` on every call that spends money. A paid step that
  fails returns `Failed` with the `carrierReference` of the object created before it, and a retry with
  `previousCarrierReference` resumes at the paid step (S-05, contract suite).
- Weights and sizes are converted by pure integer functions that round up (S-03). A parcel without weight, or more parcels than
  `maxParcels`, is `INVALID_REQUEST` before any call.
- A cancellation is `cancelled` only when the carrier says so; everything else is `refused` with the carrier's reason (S-09).
- No secret value or access token in a log line, an exchange record or an error text; the buyer's address and phone are not
  logged either (S-11). No host name outside `<Cls>Endpoints.kt`. Every i18n key exists in all three locale files and every key
  in the files is used (S-02).
- A case of S-01 to S-12 that does not apply to the carrier stays as a test that says why it does not apply
  (`CoverageTest` fails when an id has no test). Every `@Test` returns `Unit` (write `env<Unit> { ... }`), otherwise JUnit skips it
  silently (`CoverageTest` checks).
- The verification level is `UNVERIFIED` until the evidence for the next level exists (S-12).

Not covered by the unit tests, because the platform classes are not on the test run time classpath: the plugin class and
the extension class (three lines each, spec 16 §7.1). They are exercised when the plugin is built into the platform
(`SHP <slug>`, embedded mode) and by `verifyPluginJar`.
