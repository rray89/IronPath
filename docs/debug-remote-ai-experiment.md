# IronPath debug remote AI experiment

## Decision

IronPath includes an optional remote planning provider in debug builds for comparing
small on-device output with a current hosted model. As of July 26, 2026, the spike
uses Google's Interactions API, `gemini-3.5-flash`, and structured JSON output:

- `POST https://generativelanguage.googleapis.com/v1/interactions`
- API key authentication through the `x-goog-api-key` header
- `store: false` to opt out of provider-side Interaction resource retention
- a top-level `response_format` JSON schema that owns the required response shape
- the same bounded prompt, owned draft mapper, deterministic validator, and fallback
  coordinator used by the on-device provider

The provider schema deliberately limits itself to JSON types, required fields, and
closed objects. It does not repeat numeric or collection bounds. A live contract
check found that Gemini rejected IronPath's combined nested bounds with
`invalid_request`, while the same schema shape without those provider-side bounds
completed successfully. `PlanValidator` remains the authoritative safety boundary
for workout counts, day values, sets, reps, loads, weekly volume, rest, equipment,
movement limits, and progression.

The prompt still states every value and collection limit to reduce avoidable invalid
drafts. The transport also caps model output at 4,096 tokens and rejects warning,
workout, or exercise collections outside app limits before mapping them into an owned
proposal.

Official references:

- [Gemini Interactions API](https://ai.google.dev/api/interactions-api-v1)
- [Gemini API versions](https://ai.google.dev/gemini-api/docs/api-versions)
- [Migrating structured output to Interactions](https://ai.google.dev/gemini-api/docs/migrate-to-interactions)
- [Using and securing Gemini API keys](https://ai.google.dev/gemini-api/docs/generate-content/api-key)

Google's API-version guide identifies the Interactions API as generally available in
stable `v1` as of June 2026, so the `/v1/interactions` endpoint is deliberate even
though some reference examples continue to show beta paths.

Google explicitly advises against exposing provider keys in production mobile apps.
This direct client experiment is therefore portfolio and local-development code, not
a production architecture. Shipping remote AI would require authenticated backend
routing, server-side secret management, quotas, abuse controls, monitoring, and cost
controls.

## Debug setup

1. Create a restricted Gemini API key for local testing in Google AI Studio.
2. Install and launch an IronPath debug build.
3. Open **Plan**, then scroll to **Remote AI Lab**.
4. Read the off-device disclosure and enable **Use Google Gemini**.
5. Enter the key. It is masked, retained only in process memory, and cleared when the
   experiment is disabled or the app process ends.
6. Complete the planning intake and tap **Generate with AI**.

The normal debug provider order is:

1. on-device AI
2. configured remote AI experiment
3. deterministic debug fake
4. rule-based generator

On Seeker, on-device AI is unavailable. A configured remote experiment therefore gets
the first live attempt. A successful review identifies `REMOTE AI EXPERIMENT`; a timeout,
provider error, malformed response, or locally invalid draft continues through the
normal fallback chain with sanitized fixed copy.

## Privacy and secret boundary

Enabling the experiment sends the selected planning intake, injury notes, exercise
preferences, and summarized 28-day training context to Google Gemini. It does not
send raw Room entities or complete workout logs. The prompt remains bounded and
contains only eligible catalog exercises.

The API key is never committed, persisted in Room or `SavedStateHandle`, included in
the request URL or body, echoed in an error, or logged by IronPath. Redirects are not
followed, unsuccessful response bodies are ignored, and successful response bodies
are size-bounded before parsing.

Every request sets `store: false`, which opts out of provider-side Interaction
resource retention and disables server-side conversation state for this single-turn
experiment. The planning context still leaves the device and is processed by Google
under the applicable Gemini API terms; this is not an on-device privacy boundary.

Release builds contain no remote transport, provider engine, candidate binding, or
configurable remote state. The release-disabled settings binding is a no-op, so the
Remote AI Lab is absent and no code path can issue a Gemini request. The release APK
already inherits Android's `INTERNET` permission from Google DataTransport; permission
presence alone is therefore not used as evidence of remote-provider inclusion.

## Verification

Automated tests use a fake HTTP transport. CI never needs a key, spends provider
quota, or depends on a live network response. Coverage includes request construction,
the bounds-free provider schema contract, structured response mapping, malformed and
non-success responses, secret redaction, opt-in state, process-only key behavior,
local validation, provider timeout, external cancellation, debug Hilt bindings,
Compose semantics, and 200% font scale.

Useful commands:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease :app:assembleDebugAndroidTest
ANDROID_SERIAL=<seeker-serial> ./gradlew connectedDebugAndroidTest
```

A real Gemini request is optional for routine development and CI. A provider-contract
change such as `feat9.8` additionally requires one authorized post-fix Seeker smoke
that reaches `REMOTE AI EXPERIMENT`. Any live request should use the developer's own
restricted key after reviewing possible quota or billing impact. The reproducible
default demo and provider comparison paths are in `docs/v4-ai-planning-demo.md`.

### Live acceptance evidence

On July 26, 2026, the post-fix debug build completed an authorized request from a
physical Seeker to `gemini-3.5-flash` through stable `/v1/interactions`. The intake
was fully synthetic, selected one Monday workout, and had empty recent history. Plan
Review displayed `REMOTE AI EXPERIMENT · GENERATED PLAN`; the draft was not accepted
or persisted by IronPath. The verified request opted out of provider-side Interaction
resource retention. A process restart reset Remote AI Lab to disabled and removed the
API-key field. No key or raw provider payload was added to the repository or test
evidence.
