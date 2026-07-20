# IronPath on-device AI spike

## Decision

IronPath uses the ML Kit GenAI Prompt API as its first on-device planning provider:

- `com.google.mlkit:genai-prompt:1.0.0-beta3`
- `com.google.mlkit:genai-schema-compiler:1.0.0-alpha1`
- Gemini Nano supplied by Android through AICore on supported devices
- typed structured output mapped into IronPath-owned domain models

The ML Kit adapter is isolated in `data/ai`. Prompt construction, response mapping,
repair policy, validation, timeout behavior, and fallback orchestration remain owned,
JVM-testable application code.

This is a capability spike, not a claim that every Android 10+ device can run the
model. The app's minimum SDK remains 29, while on-device generation is selected only
when the runtime provider reports that the feature and structured output are
available.

Official references:

- [ML Kit Prompt API setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [ML Kit GenAI device support](https://developers.google.com/ml-kit/genai)
- [Structured output](https://developers.google.com/ml-kit/genai/prompt/android/structured-output)
- [Model selection](https://developers.google.com/ml-kit/genai/prompt/android/select-model)
- [AICore developer preview](https://developers.google.com/ml-kit/genai/aicore-dev-preview)

## Runtime behavior

The generation request has one 90-second budget covering capability detection,
initial generation, mapping, validation, and an optional repair attempt.

| ML Kit state | IronPath behavior |
| --- | --- |
| Available with structured output | Generate locally and validate the typed draft |
| Downloadable | Do not start a download; continue through the fallback chain |
| Downloading | Do not wait for setup; continue through the fallback chain |
| Unavailable or provider exception | Continue through the fallback chain |
| Deadline exceeded | Return a timeout and continue through the fallback chain |

Debug builds use this order:

1. on-device provider
2. deterministic debug fake
3. rule-based generator

Release builds use this order:

1. on-device provider
2. rule-based generator

The winning provider type is used for final validation and displayed in Plan Review.
When a lower-priority provider wins, the review also explains the fallback in fixed,
sanitized app copy. Provider exceptions, model output, and user-authored intake text
are never echoed into that explanation.

## Safety boundary

The model receives a bounded summary containing the selected week, intake fields,
summarized recent training, and only the eligible exercise catalog entries. User text
is delimited as untrusted data. The prompt and response are bounded, and dates are
calculated by the app rather than trusted from model output.

Every proposal must map to known catalog IDs and pass the deterministic
`PlanValidator`. An invalid first proposal may receive one repair request containing
sanitized validation messages. There is no repair loop. A second invalid or malformed
response falls back without persisting a partial draft.

Inference through this provider stays on device. IronPath does not bundle or commit
model weights, does not embed an API key, and does not send planning intake to a
remote model in release builds.

## Device evidence

The Solana Seeker test device runs API 36 but is not on ML Kit's supported-device
list and does not expose the Google AICore package. Its expected result is therefore
`UNAVAILABLE` followed by the deterministic debug provider. This is the supported
fallback demonstration for the current portfolio hardware, not a failed live-model
test.

A live Gemini Nano generation remains optional physical-device evidence when a
supported device is available. CI and normal development never download model
weights or depend on live inference.

## Verification

Provider behavior is covered with deterministic fakes for available, downloadable,
downloading, unavailable, timeout, cancellation, provider exception, malformed
output, unknown catalog IDs, one repair, and repair failure. The API 29 suite verifies
that the dependency and Hilt graph remain compatible below the provider's supported
hardware range.

Useful commands:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease lintDebug
./gradlew pixel2Api29DebugAndroidTest
ANDROID_SERIAL=<seeker-serial> ./gradlew connectedDebugAndroidTest
```

For the Seeker demo, install the debug build, choose at least one training day, and
tap **Generate with AI**. Plan Review should identify `DEBUG FAKE AI` and explain that
on-device AI was unavailable. A release build on the same device should identify the
rule-based generator instead.
