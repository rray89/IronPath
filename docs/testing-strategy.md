# IronPath Testing Strategy

## Test pyramid

- Pure JVM tests: domain rules, reducers and validators, ViewModel state, error paths, and concurrency branches.
- Room integration tests: every DAO query, constraint, cascade, migration, and cross-DAO transaction.
- Compose tests: every user-visible state, validation message, enabled or disabled action, semantics, and callback contract.
- Navigation tests: real NavHost selection, route arguments, back-stack behavior, and state restoration.
- Real-app journey tests: only critical navigation and persistence paths; keep them few and deterministic.
- Performance and accessibility: release startup, critical scrolling and input, semantics, font scale, adaptive layouts, and touch targets.

## Feature risk matrix

| Change | Required proof |
|---|---|
| Domain or ViewModel | Failing JVM test, then success, error, and boundary cases |
| Entity, DAO, query, or transaction | Real Room test; migration test for schema changes |
| Screen or component | Isolated Compose state/action test and accessibility semantics |
| Route or back stack | Real NavHost test |
| Critical user journey | Real-app persistence and recreation test |
| Time or date behavior | Fixed timezone and boundary-date tests through `TimeProvider` |
| Layout affected by font or window size | Adaptive test including 200% font scale |
| Performance-sensitive flow | Macrobenchmark or explicit performance evidence |

## Definition of Done

1. Product contract and acceptance criteria are written before code.
2. Tests fail for the intended reason before production code changes.
3. Happy, empty, validation, failure, boundary-time, duplicate or concurrent action, and recreation behavior are covered where applicable.
4. Relevant JVM, Room, Compose, navigation, and journey tests pass locally.
5. Every new interactive component exposes a semantic label, role, and state; layout-changing UI has 200% font-scale evidence.
6. Spotless, lint, debug and release assembly, core coverage, and applicable API 29 or API 36 device gates pass.
7. Schema changes include exported schema JSON plus every supported migration path test.
8. Performance-sensitive changes update or run the relevant benchmark without introducing a crash, ANR, or missing Baseline Profile.
9. No disabled, ignored, order-dependent, sleeping, or retry-masked test is merged.

## Coverage policy

- Scope: domain, repositories, and non-dev ViewModels.
- Minimum: 85% line and 70% branch.
- Changed production logic must not reduce either metric.
- Reviewers compare the scoped percentages in the coverage report; the automated threshold remains the hard merge floor.
- Compose and generated code are assessed through behavior tests, not JaCoCo percentage.

## Migration policy

Every database version increment must export the new schema and add both a direct previous-to-current migration test and an oldest-supported-to-current all-migrations test. The test must assert representative data, constraints, and indexes; schema validation alone is insufficient.

## Accessibility and adaptive-layout policy

- Every actionable icon or control must expose an accessible label or an adjacent merged semantic label.
- Selected, completed, loading, disabled, error, and read-only states must be discoverable semantically; color alone is not state.
- Validation errors must identify or be associated with the relevant field.
- Interactive controls must retain production touch-target sizes.
- Any new component that changes layout must have at least one test at 200% font scale. Representative screens also cover compact portrait widths and landscape.
- API 29 runs the compatible semantic-contract and adaptive-layout suites. The API-34+ platform accessibility checker and the full accessibility package also run on the managed API 36 device.

## Execution matrix

| When | Required gates |
|---|---|
| Local feature work | Focused JVM or device tests first; Seeker is the default physical target |
| Pull request | `Static & Build`, `Unit Tests & Coverage`, and `API 29 Hilt Smoke` |
| Push to `main` | The same production PR workflow against the merge result |
| Nightly or manual dispatch | API 29 and 36 compatibility matrix, API 36 accessibility, three fresh journey executions, release Baseline Profile generation, and API 36 benchmarks |
| Release-sensitive performance change | Nightly emulator execution plus controlled physical-device evidence when setting a regression threshold |

The principal local gates are:

```bash
./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease assembleDebug assembleRelease
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
./gradlew productionMatrixGroupDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
./gradlew pixel8Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.accessibility
./gradlew :app:generateReleaseBaselineProfile -PbaselineProfileUseConnectedDevices=true -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.BaselineProfileGenerator -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.StartupBenchmark,com.example.ironpath.benchmark.CriticalFlowBenchmark -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

CI adds `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` to every managed-device command. Local runs use the host GPU.

## CI trigger policy

The production workflow runs for pull requests targeting `main` and pushes to `main`. Feature and bug branches are intentionally not also push-triggered: an earlier draft included `feat/**` and `bug/**`, but an open pull request then caused the same commit to run both push and pull-request workflows. The pull-request event supplies full branch validation, and the `main` push validates the merged result without duplicate paid CI work.

## Performance policy

- Generate the release Baseline Profile before benchmarking a profile-required compilation mode.
- API 36 emulator timing is retained as an informational trend artifact; it does not enforce a brittle absolute millisecond threshold.
- Benchmark task failure, profile-generation or packaging failure, crash, and ANR are blocking failures.
- A percentage regression becomes release-blocking only after repeatable measurement on the same controlled physical device. A regression over 20% requires before-and-after traces and explicit review.

## Reports and retention

- PR artifacts retain lint HTML/XML/text, unit-test XML/HTML, JaCoCo XML/HTML, release obfuscation mappings, and managed-device HTML/XML/logcat/additional outputs for 14 days.
- Nightly artifacts retain matrix, accessibility, each of the three journey passes, generated Baseline Profiles, release APKs and obfuscation mappings, benchmark reports, and Perfetto traces for 30 days.
- Artifact upload is evidence collection, not a substitute for a failing Gradle task. Missing required reports fail their producing task; optional additional-output directories may be absent.

## Flake policy

- Quarantine is not a pass: a flaky test blocks release until fixed or removed with an approved replacement.
- Use test schedulers, idling, and observable state; never sleeps.
- Fix locale, timezone, clock, animation clock, and database state in test setup.
- The three nightly journey executions detect state leakage. Runs two and three are forced executions, never retries that convert a failure into green.
