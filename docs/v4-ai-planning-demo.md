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
| Debug with Remote AI Lab configured | `REMOTE AI EXPERIMENT` | The selected fixed route is attempted after on-device AI and before the debug fake. |
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

The October 4 multi-provider candidate supports fixed Gemini, DeepSeek and OpenRouter
routes. It awaits combined product acceptance and authorized live verification.
It sends only structured goal/days/experience/equipment/movement exclusions and the
eligible catalog; notes, preferences and local history stay on device for validation.

1. Confirm the provider, restricted test-key source and a small request/spend budget.
2. Open **Remote AI Lab**, select the fixed model/provider route and read the disclosure.
3. Enable **Use selected remote provider**, then enter its key in the masked field.
4. Use identical synthetic intake for each comparison and tap **Generate with AI**.
5. Show `REMOTE AI EXPERIMENT`, configured route, duration and reported token usage.
6. Disable or switch the route; the old key, outstanding request and draft become invalid.

`DEBUG FAKE AI` is fallback evidence, not a successful remote smoke. Record failures
and sample size honestly; do not infer model quality or billed cost from a few calls.
Each user generation allows one remote attempt, with no automatic paid retry/repair.
OpenRouter plugins must not be forced by account settings; requests disable documented
optional plugins, but cannot override account-level enforcement.

The key stays in process memory and clears on disable, route switch or process end.
Gemini keeps `store: false`; this is not an off-device processing opt-out. Other
provider terms still apply. Cancel stops local waiting and invalidates late output;
it cannot guarantee upstream processing or charges stop.

See [debug-remote-ai-experiment.md](debug-remote-ai-experiment.md) for protocol evidence,
privacy limits, synthetic final checks and the historical Gemini smoke.

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

For a future authorized physical-device run, follow the isolated direct-ADB procedure
in [testing-strategy.md](testing-strategy.md). Never use the connected Gradle runner
on a Seeker containing retained data. The current multi-provider task prohibits local
installation/emulator execution and uses existing cloud CI for device evidence.

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
- **Failure design:** cancellation, one bounded on-device repair, no automatic paid remote retry, sanitized errors, and an
  always-available local fallback are part of the normal architecture.
- **Honest capability:** unsupported hardware shows fallback rather than simulated
  on-device output.
- **Testability:** provider doubles make AI flows deterministic across JVM, Compose,
  navigation, Room, accessibility, and real-app journey tests.
