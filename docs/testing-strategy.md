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

- The hard merge scope is domain, repositories, and non-dev ViewModels: minimum 85% line and 70% branch coverage from JVM tests.
- JVM overall coverage is informational and includes only `src/test` execution. Its rankings include only the hard core scope; Android-only files are never presented as untested JVM-core files.
- API 29 Android instrumented coverage is a separate informational JaCoCo report. The API 29 test task remains a hard pass/fail gate regardless of its percentage.
- SonarQube combines JVM and Android XML reports for “covered by any suite” and new-code views. That combined percentage is never the only proof required for a feature.
- Generated Hilt, Dagger, Room, Android resource, manifest, and BuildConfig classes are excluded from percentage reporting.
- Compose, navigation, Room behavior, accessibility, journeys, and performance retain their explicit suite gates even when source lines are covered.

## SonarQube policy

- SonarQube Cloud OSS imports the JVM and managed API 29 JaCoCo XML reports after their producing test jobs pass.
- Sonar overall and new-code coverage answer whether source was executed by either imported suite; they do not replace layer-specific behavior gates.
- The built-in Sonar way gate is warning-only for this portfolio phase. Do not require its GitHub check and do not make the scanner wait for the server-side quality-gate result.
- Scanner or upload failure is a CI failure. A low Sonar percentage is visible but mergeable.
- Coverage on new code below 80% triggers review. Reviewers inspect branch/condition coverage and the applicable JVM, Room, Compose, navigation, accessibility, journey, and performance tests before deciding whether the gap is acceptable.
- Sonar secrets and account identifiers are repository settings. Fork pull requests never receive the token and skip Sonar upload.
- SonarScanner for Gradle 7.3.1.8318 runs under Java 21 and provides the required AGP 9 and task-backed KSP compatibility; 7.2.3.7755 fails against AGP 9.1's current Android DSL.
- The local scanner command in `AGENTS.md` and `CLAUDE.md` assumes the four Sonar values are exported and both XML reports already exist under `coverage/`. It is optional locally; required test gates remain independent of Sonar availability.

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
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport -PenableAndroidTestCoverage -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
./gradlew productionMatrixGroupDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
./gradlew pixel8Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.accessibility
./gradlew :app:generateReleaseBaselineProfile -PbaselineProfileUseConnectedDevices=true -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.BaselineProfileGenerator -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.StartupBenchmark,com.example.ironpath.benchmark.CriticalFlowBenchmark -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

CI adds `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` to every managed-device command. Local runs use the host GPU.

AGP 9.1 resolves managed-device coverage through the last registered device. The dedicated coverage property therefore registers only the managed API 29 target for that invocation; ordinary and nightly runs without the property retain the API 29 + 36 matrix.

## CI trigger policy

The production workflow runs for pull requests targeting `main` and pushes to `main`. Feature and bug branches are intentionally not also push-triggered: an earlier draft included `feat/**` and `bug/**`, but an open pull request then caused the same commit to run both push and pull-request workflows. The pull-request event supplies full branch validation, and the `main` push validates the merged result without duplicate paid CI work.

## Performance policy

- Generate the release Baseline Profile before benchmarking a profile-required compilation mode.
- API 36 emulator timing is retained as an informational trend artifact; it does not enforce a brittle absolute millisecond threshold.
- Benchmark task failure, profile-generation or packaging failure, crash, and ANR are blocking failures.
- A percentage regression becomes release-blocking only after repeatable measurement on the same controlled physical device. A regression over 20% requires before-and-after traces and explicit review.

## Reports and retention

- PR artifacts retain lint HTML/XML/text, unit-test XML/HTML, JVM JaCoCo XML/HTML as `jvm-unit-coverage-report`, API 29 JaCoCo XML/HTML as `api29-instrumented-coverage-report`, release obfuscation mappings, and managed-device HTML/XML/logcat/additional outputs for 14 days.
- Nightly artifacts retain matrix, accessibility, each of the three journey passes, generated Baseline Profiles, release APKs and obfuscation mappings, benchmark reports, and Perfetto traces for 30 days.
- Artifact upload is evidence collection, not a substitute for a failing Gradle task. Missing required reports fail their producing task; optional additional-output directories may be absent.

## Flake policy

- Quarantine is not a pass: a flaky test blocks release until fixed or removed with an approved replacement.
- Use test schedulers, idling, and observable state; never sleeps.
- Fix locale, timezone, clock, animation clock, and database state in test setup.
- The three nightly journey executions detect state leakage. Runs two and three are forced executions, never retries that convert a failure into green.
