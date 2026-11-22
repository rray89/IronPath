# IronPath debug remote AI experiment

## Candidate status and scope

October 4, 2026: feat9.6.1 / feat9.4.1 implementation candidate. Product acceptance
and live inference are pending. BOSS approved the combined DeepSeek → OpenRouter →
final-accept work and moved intermediate product checks to the final candidate.
No provider request, credit purchase, device installation or user-data operation is
part of the technical verification. Existing Gemini live evidence below is historical.

The experiment is Debug-only, off by default and uses developer-supplied process-only
keys. One remote registry entry chooses a dedicated adapter from this allowlist:

| Selection | Request model | Endpoint | Upstream route | Output format |
| --- | --- | --- | --- | --- |
| Gemini | `gemini-3.5-flash` | Google `/v1/interactions` | Google | Interactions structural schema |
| DeepSeek | `deepseek-flash` | DeepSeek `/chat/completions` | DeepSeek direct | JSON object, thinking disabled |
| OpenRouter baseline | `openai/gpt-4.1-mini` | OpenRouter `/api/v1/chat/completions` | `openai` only | Strict JSON schema |
| OpenRouter comparison | `qwen/qwen3.8-flash` | OpenRouter `/api/v1/chat/completions` | `alibaba` only | Strict JSON schema, reasoning disabled |

Official DeepSeek and OpenRouter docs/unauthenticated catalog metadata were checked
on October 4, 2026. DeepSeek currently maps Flash to DeepSeek-V4.1-Flash; OpenRouter
catalog canonical identifiers were `openai/gpt-4.1-mini-2025-04-14` and
`qwen/qwen3.8-flash-20260826`. Request IDs and fixed providers do not promise immutable
model weights or guarantee account access, available capacity, credits, or live validity.
No quality, latency, savings or cost improvement is claimed before measurements.

## Protocol and trust boundaries

DeepSeek Chat Completions supports `json_object`, not the Responses API schema
contract. Its system prompt explicitly requests JSON and includes the expected shape.
OpenRouter uses `provider.only` / `order`, `allow_fallbacks: false` and
`require_parameters: true`; it never automatically selects another provider/model.
Requests explicitly disable documented optional OpenRouter plugins. A key/account
with enforced “Prevent overrides” plugins is unsuitable for this controlled experiment;
those account settings cannot be overridden by the client. Confirm they are off before
live testing. Gemini preserves `store: false`, its `x-goog-api-key` header and the structural schema
without numeric/collection bounds established by feat9.8.

All routes use a single non-streaming request with 4,096 output tokens, a 60-second
application deadline, cancellable HTTP, no redirects or automatic HTTP retries and
a 256 KiB response limit. Invalid status, empty/refused/truncated output, malformed
JSON or schema mismatches fail closed. Error bodies and provider exceptions never
become user-visible strings. There is no automatic paid repair/retry. Failure proceeds
to the deterministic debug fake and then rule-based fallback, never another remote
request. Normal selection remains on-device → enabled remote → debug fake → rules.

The shared codec establishes JSON structure; app mapping and `PlanValidator` still
check catalog IDs, selected dates, equipment, movement exclusions, exercise bounds,
weekly volume, rest and progression. Remote requests omit free-text injury notes,
preferences/dislikes and all workout/record/load history. They send only goal, days,
experience, equipment, structured movement exclusions and eligible catalog entries.
The validator retains the original local intake/history, so omitted history never
weakens progression checks. Use synthetic intake for the final live smoke.

Configuration is frozen per generation. Changing a key, disabling, or selecting
another route invalidates old requests and unaccepted drafts. Switching routes clears
the key and opt-in. Late responses cannot replace current state. Final acceptance
revalidates the current draft using the current clock before mapping and persistence;
only the same newly validated draft/context can reuse retry IDs. Expired/invalid
drafts cause zero writes. Profile-generation guards, Room atomic replacement,
previous-plan retention, cancellation and duplicate-accept protection remain intact.
No Room migration is needed.

Successful remote reviews show the configured provider/model and pinned route,
generation duration, and input/output token counts when reported. Missing usage is
shown as unreported, not zero. These are ephemeral observations, not billing totals.
The existing source badge and sanitized fallback explanation distinguish remote,
fake and rules. No keys, request/response bodies or raw errors are logged or persisted.

## Secret and variant boundary

The key is masked in a Debug-only composable and never stored in Room,
`SavedStateHandle`, preferences, backup, source, APK constants, URLs or request bodies.
Disable, route switch and process death clear it. Keys are supplied explicitly by the
user; credentials from other tools/agents are never reused. In-process strings are
not a promise of secure memory erasure.

Gemini `store: false` disables Interaction resource retention, not provider processing.
DeepSeek/OpenRouter processing follows their applicable service terms; no equivalent
retention flag or on-device privacy claim is made. OpenRouter documents that
non-streaming requests continue processing and billing after disconnect. Cancellation
stops local waiting/HTTP and drops stale results; it cannot promise remote execution
or billing stops on any route.

Release and authpreview compile inert settings/UI seams with no allowlist, adapters,
engine binding, HTTP implementation or remote selector. The OkHttp dependency is
`debugImplementation` only, with its version in `gradle/libs.versions.toml`.
INTERNET permission alone is not proof of remote AI inclusion or exclusion.

## Final combined manual check (not yet run)

1. On an authorized disposable environment, open Plan → Remote AI Lab. Confirm it
   starts disabled and the allowlist identifies the intended provider/model/route.
2. Select a route, read the off-device/cost disclosure, enable, and provide its own
   test key. Switching routes must hide and clear the old key and disable the switch.
3. Use one synthetic Monday workout, no actual notes/history. Generate, inspect the
   provider label, duration/tokens, local validation and editable catalog-backed review.
4. Cancel during generation or change configuration: no late draft may appear. Reopen
   the process: the experiment is disabled and no key survives.
5. Accept a valid synthetic draft only in the authorized isolated test environment;
   confirm one plan. An expired/invalid draft must remain unaccepted with violations.
6. Exercise unavailable/invalid-key fallback and verify fake/rule provenance is clear.

Live verification requires BOSS to name the provider(s), supply their own restricted
keys through the app, and set a small request-count/spend cap. Buying credits and
running live requests remain separate authorization. Hand testing is a final product
gate; automatic test passes do not count as that approval.

## Automated verification

Tests use synthetic fake transports and loopback HTTP fixtures. They cover route
payloads, schema differences, token parsing, privacy, 401/429/5xx, response size,
truncation, no redirect/retry, cancellation during headers/body, deadlines, config
isolation, late results, clock advance, stable retry IDs and zero writes. Isolated
in-memory Room tests cover replacement/rollback/expired retry. Compose and adaptive
suites cover selector actions, labels, roles, selection and 200% font scale.

Use JDK 21; local device/emulator runs are paused for this task:

```bash
./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease assembleDebug assembleRelease :app:assembleAuthpreview :app:assembleDebugAndroidTest
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
```

API 29 and API 36 evidence comes from authorized CI. A built instrumentation APK is
compilation evidence only, not a passing device suite. Exact final-head results and
remaining gates belong in the PR/execution receipt.

## Official references

- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- [DeepSeek JSON mode](https://api-docs.deepseek.com/guides/json_mode/)
- [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [DeepSeek current models](https://api-docs.deepseek.com/quick_start/pricing/)
- [OpenRouter OpenAI endpoint metadata](https://openrouter.ai/api/v1/models/openai/gpt-4.1-mini/endpoints)
- [OpenRouter Qwen endpoint metadata](https://openrouter.ai/api/v1/models/qwen/qwen3.8-flash/endpoints)
- [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)
- [OpenRouter plugin overrides](https://openrouter.ai/docs/guides/features/plugins)
- [OpenRouter structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs)
- [Alibaba structured output support](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)
- [OpenRouter cancellation/billing](https://openrouter.ai/docs/api_reference/streaming)
- [Gemini Interactions API](https://ai.google.dev/api/interactions-api-v1)
- [Gemini API keys](https://ai.google.dev/gemini-api/docs/generate-content/api-key)
- [OkHttp 5.3.2 changelog](https://lysine.dev/okhttp/changelogs/changelog/#version-532)

## Historical Gemini acceptance evidence

On July 26, 2026, feat9.8 completed an authorized synthetic one-day Gemini request on
Seeker and reached `REMOTE AI EXPERIMENT · GENERATED PLAN`. No draft was accepted or
persisted. Process restart cleared the opt-in/key. This demonstrates that historical
Gemini candidate, not the current multi-provider build or either new provider.
