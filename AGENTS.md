# IronPath Repository Instructions

## Sources of Truth

- Before changing product behavior, read `README.md`, the PRD named by the task or active feature, and any baseline PRD needed to understand inherited behavior. The merged baseline named by the current `README.md` is `docs/ironpath-v4-ai-planning-prd.md`; read the predecessor PRDs it identifies when the touched behavior is inherited. A task-approved later PRD supersedes older scope only where it explicitly changes it.
- Before visual or interaction changes, also read `docs/IronPath Kinetic v2.md` completely. Use the active PRD for user-facing text and navigation when Figma or Stitch exports contain garbled or stale literals.
- Before implementing any feature or bug fix, read `docs/testing-strategy.md` completely and follow its feature risk matrix, Definition of Done, execution matrix, and applicable coverage/accessibility/performance policies.
- Dependency and plugin versions are owned by `gradle/libs.versions.toml`. Add or update versions there rather than inline in module build files.

## Build & Verification

- Use the Gradle wrapper with JDK 21. Android Studio is optional; the command-line SDK, platform-tools/adb, and managed devices are sufficient.
- Build or install debug: `./gradlew assembleDebug` or `./gradlew installDebug`
- Run the static and build gates: `./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease assembleDebug assembleRelease`
- Run the unit and core-coverage gates: `./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage`
- Run the reproducible API 29 suite: `./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest`
- Detailed focused, coverage, API 36, Baseline Profile, benchmark, and nightly commands live in `docs/testing-strategy.md`; select the commands required by its risk and execution matrices rather than running every device suite for every change.
- Local Sonar analysis is optional and assumes both coverage XML reports already exist plus `SONAR_TOKEN`, `SONAR_HOST_URL`, `SONAR_PROJECT_KEY`, and `SONAR_ORGANIZATION` are exported:

  ```bash
  ./gradlew :app:assembleDebug sonar -Dsonar.projectKey="$SONAR_PROJECT_KEY" -Dsonar.organization="$SONAR_ORGANIZATION" -Dsonar.coverage.jacoco.xmlReportPaths=coverage/unit/report.xml,coverage/android/report.xml
  ```

## Architecture & Test Seams

- Owned production classes use constructor injection. Reserve Hilt modules for third-party objects and interface bindings.
- JVM tests construct subjects directly. Use Hilt for graph/startup and instrumented integration tests, not as the default JVM test harness.
- Shared Hilt bindings must compile in debug and release. Release-only bindings must compile and resolve in release; debug-only bindings must compile and resolve in debug. Applicable debug bindings must also resolve through API 29 startup or journey coverage.
- Keep debug-only providers, transports, and configuration UI in debug sources; test fixtures and overrides in test or androidTest sources; and benchmark presentation or seeding hooks in benchmark-only source sets. Put any temporary store in the narrow non-production source set that owns its workflow. All must remain absent from the production release variant; only an explicitly shared seam may use an inert release implementation.

## Device Policy

- Seeker is the default physical target for local instrumented and smoke tests. Confirm it appears as `device` in `adb devices -l`; if multiple targets are attached, set `ANDROID_SERIAL` for the command and never hardcode the serial in the repository.
- If Seeker is absent, offline, or unauthorized, use `./gradlew pixel2Api29DebugAndroidTest`. API 36 accessibility, adaptive-layout, and performance coverage follows the broader matrix in `docs/testing-strategy.md` rather than the default feature loop.

## PR & Branch Naming

- Preserve an established feature ID for follow-ups and use its sub-feature numbering instead of incrementing the main feature ID.
