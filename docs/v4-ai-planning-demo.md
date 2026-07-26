# IronPath V4 AI planning demo

This guide demonstrates V4's complete AI-assisted planning loop and its provider
boundaries. It uses the deterministic debug provider by default, so the walkthrough
is reproducible without a model download, network request, API key, or provider
quota.

## What the demo proves

- AI planning is a replaceable domain capability, not ViewModel or UI logic.
- Model output stays a draft until catalog mapping and deterministic validation pass.
- Review edits remain catalog-backed and are revalidated before acceptance.
- Unsupported, slow, malformed, or invalid providers fall back without persisting a
  partial plan.
- Release builds contain neither the debug fake provider nor the remote experiment.

## Provider outcomes

| Environment | Expected Plan Review label | Expected behavior |
| --- | --- | --- |
| Seeker debug, Remote AI Lab off | `DEBUG FAKE AI` | On-device capability is unavailable, then the deterministic fake produces a valid draft. |
| Supported AICore device, debug or release | `ON-DEVICE AI` | Gemini Nano proposes a structured draft locally, subject to normal validation. |
| Debug with Remote AI Lab configured | `REMOTE AI EXPERIMENT` | Gemini is attempted after on-device AI and before the debug fake. |
| Seeker release | `RULE-BASED` | On-device capability is unavailable, so generation honestly falls back to the local planner. |

The Solana Seeker runs API 36 but does not expose the required AICore capability. Its
debug fake and release rule-based outcomes are expected fallback evidence, not failed
on-device inference.

## Prepare Seeker

Confirm that the device is connected and authorized:

```bash
adb devices -l
```

Install the debug build. Set `ANDROID_SERIAL` when more than one target is attached.

```bash
ANDROID_SERIAL=<seeker-serial> ./gradlew installDebug
```

For a clean demo with no accepted plan, clear IronPath's local app data and relaunch:

```bash
adb -s <seeker-serial> shell pm clear com.example.ironpath
adb -s <seeker-serial> shell am start -n com.example.ironpath/.MainActivity
```

Clearing app data removes all local IronPath plans, workout logs, and records on that
device. Skip that command when the existing data matters.

## 60-second walkthrough

1. Tap **Get Started**, then open **Plan**.
2. Choose a goal and Monday, Wednesday, and Friday.
3. Choose a training experience and enough equipment to produce a useful catalog,
   such as Bodyweight, Dumbbell, and Bench.
4. Select one movement limit, such as Shoulder, and add a short preference or injury
   note. Point out that structured limits drive validation while notes are bounded
   context, not medical advice.
5. Leave **Remote AI Lab** off and tap **Generate with AI**.
6. In Plan Review, show `DEBUG FAKE AI`, the sanitized fallback explanation, the
   one-week rationale, and the enabled **Accept Plan** action.
7. Tap an exercise, change its prescription or select another eligible catalog
   exercise, and confirm it. The edited draft is revalidated immediately.
8. Tap **Accept Plan** and show the accepted week on Home.

The interview summary is: IronPath lets a model propose a plan inside a normal
Android architecture, but catalog ownership, safety constraints, fallback, review,
and persistence stay deterministic.

## Fallback demonstration

The simplest fallback proof on Seeker requires no failure injection:

1. Keep **Remote AI Lab** disabled.
2. Tap **Generate with AI**.
3. Point out that the app attempted the higher-priority on-device provider first.
4. Show the `DEBUG FAKE AI` provider label and fixed fallback explanation in review.

For release behavior, install a release-signed local build according to the local
signing setup and repeat the flow. On Seeker, Plan Review should show `RULE-BASED`.
The release result is intentionally not presented as AI output.

Automated provider tests cover timeout, cancellation, provider exception, malformed
output, unknown catalog IDs, invalid drafts, one repair attempt, and repair failure.
The demo does not need a deliberately unreliable live model to prove those paths.

## Optional remote comparison

The remote path is a debug-only developer experiment. It sends structured planning
intake, injury notes, preferences, and summarized 28-day training context to Google
Gemini. Do not use personal or sensitive text in a portfolio demonstration.

1. Create a restricted Gemini API key and review possible quota or billing impact.
2. Open **Remote AI Lab** and enable **Use Google Gemini**.
3. Enter the key in the masked field.
4. Tap **Generate with AI**.
5. On success, show `REMOTE AI EXPERIMENT` in Plan Review.
6. Disable the experiment afterward to clear the in-memory key.

`DEBUG FAKE AI` is fallback evidence, not a successful remote smoke. If it appears,
verify key/model access and the current structured-output contract before presenting
the remote comparison.

The key is held only in process memory. IronPath does not write it to Room,
`SavedStateHandle`, logs, request URLs, request bodies, or release code. A production
hosted provider would require authenticated backend routing and server-side secret,
quota, abuse, monitoring, and cost controls. The debug transport sends `store: false`
to opt out of provider-side Interaction resource retention, but planning context
still leaves the device for Google processing.

See [debug-remote-ai-experiment.md](debug-remote-ai-experiment.md) for the complete
privacy and transport boundary.

## Optional on-device proof

A live on-device demo requires hardware listed as supported by ML Kit GenAI, AICore
availability, and downloaded model capability. No repository test or CI gate depends
on those conditions.

1. Install the appropriate debug or release build on a supported physical device.
2. Keep the remote experiment disabled.
3. Complete the same structured intake and tap **Generate with AI**.
4. Confirm that Plan Review identifies `ON-DEVICE AI`.
5. Edit and accept the draft to prove that local inference still crosses the same
   validator and persistence boundary.

See [on-device-ai-spike.md](on-device-ai-spike.md) for capability states, timeout,
repair, and fallback behavior.

## Reproducible verification

Run the non-device quality gates:

```bash
./gradlew spotlessCheck test verifyCoreCoverage lintDebug assembleDebugAndroidTest assembleRelease -PenableCoverage
./gradlew :app:assembleBenchmarkRelease :app:assembleNonMinifiedRelease
```

Run the physical-device suite on Seeker:

```bash
ANDROID_SERIAL=<seeker-serial> ./gradlew connectedDebugAndroidTest
```

The managed API 29 fallback is:

```bash
./gradlew pixel2Api29DebugAndroidTest
```

CI and normal verification use deterministic providers. They never require an API
key, live network output, model weights, AICore, or provider quota.

## Talking points

- **Local-first:** Room remains the source of truth; remote sync and auth are outside
  V4.
- **Provider isolation:** build variants keep debug experiments out of release.
- **Deterministic safety:** typed drafts, stable exercise IDs, and explicit validation
  stand between provider output and persistence.
- **Failure design:** cancellation, one bounded repair, sanitized errors, and an
  always-available local fallback are part of the normal architecture.
- **Honest capability:** unsupported hardware shows fallback rather than simulated
  on-device output.
- **Testability:** provider doubles make AI flows deterministic across JVM, Compose,
  navigation, Room, accessibility, and real-app journey tests.
