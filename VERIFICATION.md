# Verification record

Level: UNVERIFIED

This file says what was verified for this plugin, how and when (spec 16 section 8.4). The level written here must equal
`descriptor.verification` in the code and `level` in `src/test/resources/verification.json`; `VerificationLevelTest`
fails when they differ or when the evidence does not meet the condition of the level.

| Level | Allowed when |
|---|---|
| `UNVERIFIED` | default |
| `DOC_SAMPLES` | an `OFFICIAL` vector covers the answer the plugin trusts for tracking (evidence name `tracking response`: for an unsigned webhook that is the re-fetched shipment object; for a signed one add the signature vector `webhook signature`), and one covers the outbound request signature when the carrier signs requests |
| `SANDBOX` | a `sandbox` block in `verification.json`: quote, create, label and tracking (+ cancel when cancel is offered) ran against the carrier's test environment; who / when below |
| `LIVE` | the owner bought and tracked a real label; recorded below |

An agent may never raise the level without the evidence recorded here.

## What the example plugin has

- One vector file, `src/test/resources/vectors/tracking.json`, kind `SELF_DERIVED`: the status table below and two shipment
  objects with their expected tracking updates, written from the protocol below. The carrier of this template is imaginary, so
  there is no official documentation to take a sample from. These vectors prove that the code is stable and matches its own
  description; they prove nothing about a real carrier.
- The flow tests run against `spi.testkit.FakeGateway`, which speaks exactly the protocol below.
- No sandbox run, no live label. Level: `UNVERIFIED`.

## Protocol of the example carrier (the assumptions the code makes)

| Item | Value |
|---|---|
| Credentials | `POST /v1/auth/token` `{apiKey, apiSecret}` answers `{token, expiresIn}` (seconds); every other call sends `Authorization: Bearer <token>`. The token is cached in `ctx.state` for `expiresIn - 60` seconds under a key derived from a hash of the credentials and the mode; a 401 drops it, fetches a new one and repeats the call once |
| Services | `GET /v1/services` answers `{services: [{code, name, carrier?, international?}]}` |
| Rates | `POST /v1/rates` `{from, to, parcels, declaredValue, serviceCode?}` answers `{rates: [{service, name, carrier?, amount, currency, rateId?, minDays?, maxDays?, expiresAt?, includesTax?}], unavailable?: {<service>: <reason>}}` |
| Create, step 1 | `POST /v1/shipments` `{reference, from, to, parcels, items, declaredValue, serviceCode?, note?}` + `Idempotency-Key: draft-<merchantReference>` answers `{id, status: draft}`; a replay of the key answers the same id. 4xx `{error: {code, message}}`: `address_invalid`, `service_unavailable`, `weight_limit` |
| Create, step 2 | `POST /v1/shipments/{id}/purchase` `{labelFormat: pdf / zpl, rateId?, serviceCode?}` + `Idempotency-Key: buy-<merchantReference>` answers `{id, status, trackingNumber, trackingUrl, carrier, cost: {amount, currency}, label?: {format, data (base64)}}`; a replay of the key never charges twice. `402 insufficient_balance`, `409 rate_expired` |
| Read label | `GET /v1/shipments/{id}/label?format=pdf` answers the document (200), or `202 {status: pending}` while it is prepared |
| Cancel | `DELETE /v1/shipments/{id}` answers `{status: cancelled}`; `409 not_cancellable` when it was picked up |
| Read shipment | `GET /v1/shipments/{id}` answers `{id, status, trackingNumber?, trackingUrl?, estimatedDelivery?, events: [{id, status, time, description?, location?}]}`; the batch form is `GET /v1/shipments?ids=a,b,c` (at most 20) answering `{shipments: [...]}`; unknown = 404 |
| Account | `GET /v1/account` answers `{id, balance?: {amount, currency}}` |
| Address check | `POST /v1/addresses/validate` `{address}` answers `{valid, normalized?, message?}` |
| Webhook | `POST` JSON `{event: "shipment.updated", shipmentId}`, **unsigned**, the same body for every state of a shipment |
| Units | weight in kilograms with one decimal, billed in 100 g steps; sizes in whole centimetres; `desi` = ceil(L x W x H cm3 / 3000); the plugin rounds every value up |
| Amounts | integer ISO minor units; Turkish lira is written `TL` |
| Status words | `draft, created, purchased, label_ready, picked_up, in_transit, at_hub, out_for_delivery, delivered, delivery_failed, exception, held, customs_hold, returning, return_to_sender, returned, cancelled, voided, lost` (table in `vectors/tracking.json`); any other word is not mapped |
| Hosts | live and sandbox hosts in `ExampleEndpoints.kt` only |

### If an assumption turns out wrong

For a real carrier, every row of this table is an `UNVERIFIED` assumption until a sandbox run confirms it. Record each one that
the brief marks `UNVERIFIED` here, and say how the code behaves if the assumption is wrong:

| Assumption | Behaviour if wrong |
|---|---|
| the webhook is unsigned and a trigger only | nothing changes for the worse: the state is always read from the carrier, a webhook that cannot be confirmed reports nothing and answers 503 so the carrier delivers again, and market's polling (`track`) still finds the state |
| a status word is missing from the table | the event is dropped (never mapped to a guess) and the next poll sees the others; the shipment keeps its last known status until a known word arrives. Add the word to `ExampleMapper.status` and the vector file |
| the idempotency keys are honoured | a retry after a timeout may create a second draft (free) and, if the purchase key is ignored too, buy twice. The owner sees both in the carrier's account; no code path retries a purchase on its own |
| `expiresIn` is in seconds | a token that lives shorter than assumed is dropped by the 401 handling (one extra token call); one that lives longer is simply replaced early |
| the label answer is a document for 200 and JSON for 202 | a JSON answer to a 200 is read as "not ready" until the carrier sends a document |
| the batch answer has the same shape as one shipment | a shipment object without events and without a tracking number tells nothing and is skipped |

## Sandbox / live record

None.
