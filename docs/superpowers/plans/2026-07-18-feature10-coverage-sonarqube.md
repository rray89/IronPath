# Feature 10.5-10.6 Honest Coverage and SonarQube Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace misleading JVM-only zero-coverage rankings with honest layer-specific reporting, publish informational API 29 instrumented coverage, and add a non-blocking SonarQube Cloud OSS view of coverage on changed code.

**Architecture:** Keep the existing JVM core coverage gate as the only percentage-based hard merge gate. Generate a separate JaCoCo report from the existing managed API 29 suite, compose one tested PR comment from the JVM and Android XML reports, and let SonarQube Cloud import both reports for its cross-suite and new-code views. Keep test-suite pass/fail gates separate so a combined percentage never substitutes for Room, Compose, navigation, accessibility, journey, or performance evidence.

**Tech Stack:** Android Gradle Plugin 9.1.0, Gradle 9.4.1, Kotlin 2.3.20/JVM 11, JaCoCo 0.8.14 supplied by AGP, Gradle Managed Devices, Node.js 20 built-in test runner, GitHub Actions, SonarScanner for Gradle 7.3.1.8318, SonarQube Cloud OSS.

## Global Constraints

- Deliver this work as two independently reviewable pull requests: `feat10.5: report honest multi-layer coverage` followed by `feat10.6: add SonarQube Cloud OSS analysis`.
- Use branches `feat/feat10.5-honest-coverage` and `feat/feat10.6-sonarqube-cloud`.
- Do not start `feat10.6` until `feat10.5` is merged and the SonarQube Cloud organization, project, repository variables, and token are available.
- Do not upgrade AGP, Gradle, Kotlin, JaCoCo, Hilt, Room, or the Android SDK as part of either pull request.
- Preserve the hard JVM core gate at 85% line and 70% branch coverage for domain, repositories, and non-dev ViewModels.
- Preserve `Static & Build`, `Unit Tests & Coverage`, and the API 29 instrumentation suite as hard pull-request gates.
- Keep API 36 compatibility, accessibility, journeys, Baseline Profile generation, and benchmarks in the nightly/release workflow.
- Treat Android instrumented percentage, Sonar overall coverage, and Sonar new-code coverage as informational; low percentages must not make GitHub reject a merge.
- A coverage-production failure is still blocking: missing XML, missing execution data, malformed reports, failed Sonar upload, or failed tests must fail their producing job.
- Do not enable `sonar.qualitygate.wait`; do not add the Sonar quality-gate check to GitHub required status checks during this feature.
- Use the SonarQube Cloud OSS plan for this public repository. Do not deploy or maintain a self-hosted SonarQube server.
- Use CI-based Sonar analysis. Automatic analysis cannot import JaCoCo coverage and must be disabled when the CI scanner becomes active.
- Never commit `SONAR_TOKEN`, project credentials, organization credentials, or generated coverage execution files.
- Keep GitHub fork pull requests safe: tests still run, but coverage comments and Sonar upload run only for same-repository pull requests or pushes to `main`.
- The default physical device remains Seeker for local smoke testing. The API 29 managed Pixel 2 remains the reproducible CI coverage target.
- `AGENTS.md` and `CLAUDE.md` must remain synchronized for coverage commands and policy.

---

## Pull-Request Decomposition

| Pull request | Deliverable | Hard gates added or changed | Informational output |
|---|---|---|---|
| `feat10.5: report honest multi-layer coverage` | Tested coverage-comment renderer, scoped JVM rankings, API 29 Android JaCoCo report | Report-generation failures become blocking; existing test gates remain | Separate JVM and Android percentages in one PR comment and two HTML/XML artifacts |
| `feat10.6: add SonarQube Cloud OSS analysis` | CI-based Kotlin/Android analysis importing both reports | Scanner/upload health is blocking; Sonar quality gate is not required | Sonar dashboard, PR decoration, overall coverage, new-code coverage, issues, hotspots, duplication |

## File Map

### Pull request `feat10.5`

- Modify `app/build.gradle.kts`: conditionally instrument Android tests and register the API-29-only JaCoCo report task without pulling API 36 into the PR workflow.
- Create `.github/scripts/coverage-report.mjs`: parse JaCoCo XML, calculate the existing core gate view, rank only JVM-core packages and files, and render the combined PR comment.
- Create `.github/scripts/coverage-report.test.mjs`: verify scope, rounding, ordering, missing-report behavior, generated/UI exclusion, and combined output with Node's built-in test runner.
- Create `.github/scripts/fixtures/unit-coverage.xml`: deterministic JVM fixture containing covered core code plus zero-covered UI and generated code.
- Create `.github/scripts/fixtures/android-coverage.xml`: deterministic instrumented fixture containing Room, Compose, navigation, and partial branches.
- Modify `.github/workflows/android-ci.yml`: run renderer tests, produce and upload Android coverage, download both reports in a summary job, and post one tested comment.
- Modify `docs/testing-strategy.md`: define hard versus informational metrics, report scope, artifact paths, and commands.
- Modify `AGENTS.md` and `CLAUDE.md`: add the exact local coverage commands and the rule that report headings must identify their test layer.

### Pull request `feat10.6`

- Modify `gradle/libs.versions.toml`: add the SonarScanner for Gradle plugin version and alias.
- Modify `build.gradle.kts`: apply and configure the scanner with stable source, test, coverage, and exclusion policy; keep account identifiers outside source control.
- Modify `.github/workflows/android-ci.yml`: add the same-repository-only Sonar job after both coverage producers.
- Modify `docs/testing-strategy.md`: document the Sonar ownership boundary, warning-only quality gate, account variables, bootstrap behavior, and failure policy.
- Modify `AGENTS.md` and `CLAUDE.md`: add the exact local Sonar verification command without secrets.

---

### Task 1: Lock the coverage-comment contract with fixture tests

**Files:**
- Create: `.github/scripts/coverage-report.mjs`
- Create: `.github/scripts/coverage-report.test.mjs`
- Create: `.github/scripts/fixtures/unit-coverage.xml`
- Create: `.github/scripts/fixtures/android-coverage.xml`

**Interfaces:**
- Consumes: JaCoCo XML strings generated by AGP's unit and Android coverage tasks.
- Produces: `parseJacocoReport(xml: string): CoverageReport`, `isCoreJvmPath(path: string): boolean`, and `buildCoverageComment(input: { unitXml: string, androidXml: string }): string`.
- `CoverageReport` is a plain JavaScript object containing `root`, `packages`, and `files`; every counter has `{ missed, covered, total, pct }`.

- [ ] **Step 1: Add deterministic JaCoCo fixtures**

Create `unit-coverage.xml` with these source-file outcomes:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="unit">
  <package name="com/example/ironpath/domain/planner">
    <sourcefile name="PlanGenerator.kt">
      <counter type="INSTRUCTION" missed="10" covered="90"/>
      <counter type="BRANCH" missed="2" covered="8"/>
      <counter type="LINE" missed="2" covered="18"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="10" covered="90"/>
    <counter type="BRANCH" missed="2" covered="8"/>
    <counter type="LINE" missed="2" covered="18"/>
  </package>
  <package name="com/example/ironpath/ui/screens/home">
    <sourcefile name="HomeScreen.kt">
      <counter type="INSTRUCTION" missed="100" covered="0"/>
      <counter type="BRANCH" missed="10" covered="0"/>
      <counter type="LINE" missed="20" covered="0"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="100" covered="0"/>
    <counter type="BRANCH" missed="10" covered="0"/>
    <counter type="LINE" missed="20" covered="0"/>
  </package>
  <package name="com/example/ironpath/data/local">
    <sourcefile name="IronPathDatabase_Impl.java">
      <counter type="INSTRUCTION" missed="50" covered="0"/>
      <counter type="BRANCH" missed="4" covered="0"/>
      <counter type="LINE" missed="10" covered="0"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="50" covered="0"/>
    <counter type="BRANCH" missed="4" covered="0"/>
    <counter type="LINE" missed="10" covered="0"/>
  </package>
  <counter type="INSTRUCTION" missed="160" covered="90"/>
  <counter type="BRANCH" missed="16" covered="8"/>
  <counter type="LINE" missed="32" covered="18"/>
</report>
```

Create `android-coverage.xml` with Room, Compose, and navigation source files, including one partially covered branch:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="android">
  <package name="com/example/ironpath/data/local/dao">
    <sourcefile name="WorkoutDao.kt">
      <counter type="INSTRUCTION" missed="5" covered="95"/>
      <counter type="BRANCH" missed="0" covered="0"/>
      <counter type="LINE" missed="1" covered="19"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="5" covered="95"/>
    <counter type="BRANCH" missed="0" covered="0"/>
    <counter type="LINE" missed="1" covered="19"/>
  </package>
  <package name="com/example/ironpath/ui/screens/home">
    <sourcefile name="HomeScreen.kt">
      <counter type="INSTRUCTION" missed="20" covered="80"/>
      <counter type="BRANCH" missed="1" covered="3"/>
      <counter type="LINE" missed="4" covered="16"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="20" covered="80"/>
    <counter type="BRANCH" missed="1" covered="3"/>
    <counter type="LINE" missed="4" covered="16"/>
  </package>
  <package name="com/example/ironpath/ui/navigation">
    <sourcefile name="NavGraph.kt">
      <counter type="INSTRUCTION" missed="10" covered="40"/>
      <counter type="BRANCH" missed="1" covered="1"/>
      <counter type="LINE" missed="2" covered="8"/>
    </sourcefile>
    <counter type="INSTRUCTION" missed="10" covered="40"/>
    <counter type="BRANCH" missed="1" covered="1"/>
    <counter type="LINE" missed="2" covered="8"/>
  </package>
  <counter type="INSTRUCTION" missed="35" covered="215"/>
  <counter type="BRANCH" missed="2" covered="4"/>
  <counter type="LINE" missed="7" covered="43"/>
</report>
```

- [ ] **Step 2: Write failing Node tests for the public contract**

Create `.github/scripts/coverage-report.test.mjs` using only `node:test`, `node:assert/strict`, and `node:fs`:

```javascript
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  buildCoverageComment,
  isCoreJvmPath,
  parseJacocoReport,
} from "./coverage-report.mjs";

const fixture = (name) =>
  readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8");

test("core JVM scope includes owned logic and excludes Android-only and generated files", () => {
  assert.equal(
    isCoreJvmPath("com/example/ironpath/domain/planner/PlanGenerator.kt"),
    true,
  );
  assert.equal(
    isCoreJvmPath("com/example/ironpath/data/repository/PlanRepository.kt"),
    true,
  );
  assert.equal(
    isCoreJvmPath("com/example/ironpath/ui/screens/home/HomeViewModel.kt"),
    true,
  );
  assert.equal(
    isCoreJvmPath("com/example/ironpath/ui/screens/home/HomeScreen.kt"),
    false,
  );
  assert.equal(
    isCoreJvmPath("com/example/ironpath/data/local/IronPathDatabase_Impl.java"),
    false,
  );
});

test("parser uses report-level counters and preserves exact percentages", () => {
  const report = parseJacocoReport(fixture("unit-coverage.xml"));
  assert.deepEqual(report.root.line, {
    missed: 32,
    covered: 18,
    total: 50,
    pct: 36,
  });
  assert.equal(report.files.length, 3);
});

test("combined comment labels both test layers and never ranks Android-only zeroes as JVM gaps", () => {
  const body = buildCoverageComment({
    unitXml: fixture("unit-coverage.xml"),
    androidXml: fixture("android-coverage.xml"),
  });

  assert.match(body, /### Core JVM merge gate/);
  assert.match(body, /### Overall JVM unit-test coverage/);
  assert.match(body, /### Android instrumented coverage — managed API 29/);
  assert.match(body, /JVM and Android percentages are reported separately/);
  assert.match(body, /PlanGenerator\.kt/);
  assert.match(
    body,
    /### Lowest JVM core packages[\s\S]*com\.example\.ironpath\.domain\.planner/,
  );
  assert.doesNotMatch(body, /Lowest JVM[\s\S]*HomeScreen\.kt/);
  assert.doesNotMatch(body, /Lowest JVM[\s\S]*IronPathDatabase_Impl\.java/);
  assert.doesNotMatch(
    body,
    /### Lowest JVM core packages[\s\S]*com\.example\.ironpath\.ui\.screens\.home/,
  );
});

test("missing or malformed counters fail instead of publishing a false zero", () => {
  assert.throws(
    () => parseJacocoReport("<report name=\"empty\"></report>"),
    /JaCoCo report has no root LINE counter/,
  );
});
```

- [ ] **Step 3: Run the tests and verify the intended failure**

Run:

```bash
node --test .github/scripts/coverage-report.test.mjs
```

Expected: FAIL because `coverage-report.mjs` does not exist or does not export the three required functions.

- [ ] **Step 4: Implement the pure report parser and renderer**

Create `.github/scripts/coverage-report.mjs` with no network calls and no GitHub-specific state. The complete implementation must:

```javascript
const COUNTER_TYPES = ["INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS"];

const counter = (block, type) => {
  const expression = new RegExp(
    `<counter type="${type}" missed="(\\d+)" covered="(\\d+)"\\/>`,
    "g",
  );
  const matches = [...block.matchAll(expression)];
  const match = matches.at(-1);
  if (!match) return null;
  const missed = Number.parseInt(match[1], 10);
  const covered = Number.parseInt(match[2], 10);
  const total = missed + covered;
  return {
    missed,
    covered,
    total,
    pct: total === 0 ? 0 : Math.round((covered / total) * 100),
  };
};

export const isCoreJvmPath = (path) =>
  path.endsWith(".kt") &&
  (path.startsWith("com/example/ironpath/domain/") ||
    path.startsWith("com/example/ironpath/data/repository/") ||
    (path.startsWith("com/example/ironpath/ui/screens/") &&
      path.endsWith("ViewModel.kt") &&
      !path.endsWith("DevToolsViewModel.kt")));

export function parseJacocoReport(xml) {
  const root = Object.fromEntries(
    COUNTER_TYPES.map((type) => [type.toLowerCase(), counter(xml, type)]),
  );
  if (!root.line) throw new Error("JaCoCo report has no root LINE counter");

  const packages = [];
  const files = [];
  const packageMatches = [
    ...xml.matchAll(/<package name="([^"]+)">([\s\S]*?)<\/package>/g),
  ];
  for (const [, packageName, packageBlock] of packageMatches) {
    const line = counter(packageBlock, "LINE");
    if (line && line.total > 0) {
      packages.push({ name: packageName.replaceAll("/", "."), ...line });
    }
    const sourceMatches = [
      ...packageBlock.matchAll(
        /<sourcefile name="([^"]+)">([\s\S]*?)<\/sourcefile>/g,
      ),
    ];
    for (const [, fileName, fileBlock] of sourceMatches) {
      const fileLine = counter(fileBlock, "LINE");
      if (!fileLine || fileLine.total === 0) continue;
      files.push({
        name: `${packageName}/${fileName}`,
        line: fileLine,
        branch: counter(fileBlock, "BRANCH"),
      });
    }
  }
  return { root, packages, files };
}

const sum = (rows, type) => {
  const totals = rows.reduce(
    (result, row) => {
      const value = row[type];
      if (value) {
        result.covered += value.covered;
        result.missed += value.missed;
      }
      return result;
    },
    { covered: 0, missed: 0 },
  );
  const total = totals.covered + totals.missed;
  return {
    ...totals,
    total,
    pct: total === 0 ? 0 : (totals.covered / total) * 100,
  };
};

const metricTable = (report) => {
  const labels = {
    instruction: "Instructions",
    branch: "Branches",
    line: "Lines",
    method: "Methods",
    class: "Classes",
  };
  const rows = [
    "| Metric | Covered | Total | % |",
    "|---|---:|---:|---:|",
  ];
  for (const [type, label] of Object.entries(labels)) {
    const value = report.root[type];
    if (value) rows.push(`| ${label} | ${value.covered} | ${value.total} | **${value.pct}%** |`);
  }
  return rows.join("\n");
};

export function buildCoverageComment({ unitXml, androidXml }) {
  const unit = parseJacocoReport(unitXml);
  const android = parseJacocoReport(androidXml);
  const coreFiles = unit.files.filter((file) => isCoreJvmPath(file.name));
  const coreLine = sum(coreFiles, "line");
  const coreBranch = sum(coreFiles, "branch");
  if (coreLine.total === 0 || coreBranch.total === 0) {
    throw new Error("Core JVM scope has no line or branch counters");
  }

  const filesByPackage = new Map();
  for (const file of coreFiles) {
    const packageName = file.name.slice(0, file.name.lastIndexOf("/"));
    const packageFiles = filesByPackage.get(packageName) ?? [];
    packageFiles.push(file);
    filesByPackage.set(packageName, packageFiles);
  }
  const corePackages = [...filesByPackage].map(([name, files]) => ({
    name: name.replaceAll("/", "."),
    line: sum(files, "line"),
  }));
  const lowestCorePackages = [...corePackages]
    .sort((left, right) =>
      left.line.pct === right.line.pct
        ? right.line.missed - left.line.missed
        : left.line.pct - right.line.pct,
    )
    .slice(0, 20);

  const lowestCoreFiles = [...coreFiles]
    .sort((left, right) =>
      left.line.pct === right.line.pct
        ? right.line.missed - left.line.missed
        : left.line.pct - right.line.pct,
    )
    .slice(0, 20);

  const body = [
    "## Coverage Report",
    "",
    "> JVM and Android percentages are reported separately. A file covered only by device tests is not an uncovered JVM-core file.",
    "",
    "### Core JVM merge gate",
    "",
    "Scope: domain, repositories, and non-dev ViewModels.",
    "",
    "| Metric | Covered | Total | % | Minimum |",
    "|---|---:|---:|---:|---:|",
    `| Lines | ${coreLine.covered} | ${coreLine.total} | **${coreLine.pct.toFixed(2)}%** | 85% |`,
    `| Branches | ${coreBranch.covered} | ${coreBranch.total} | **${coreBranch.pct.toFixed(2)}%** | 70% |`,
    "",
    "### Overall JVM unit-test coverage",
    "",
    "Informational: Android instrumented execution is not included in this table.",
    "",
    metricTable(unit),
    "",
    "### Lowest JVM core packages",
    "",
    "| Package | Covered | Total | % |",
    "|---|---:|---:|---:|",
    ...lowestCorePackages.map(
      (pkg) =>
        `| \`${pkg.name}\` | ${pkg.line.covered} | ${pkg.line.total} | ${pkg.line.pct.toFixed(2)}% |`,
    ),
    "",
    "### Lowest JVM core files",
    "",
    "| File | Covered | Total | % |",
    "|---|---:|---:|---:|",
    ...lowestCoreFiles.map(
      (file) =>
        `| \`${file.name}\` | ${file.line.covered} | ${file.line.total} | ${file.line.pct}% |`,
    ),
    "",
    "### Android instrumented coverage — managed API 29",
    "",
    "Informational percentage; the Android test task itself remains a hard pass/fail gate.",
    "",
    metricTable(android),
    "",
    "<sub>Download `jvm-unit-coverage-report` and `api29-instrumented-coverage-report` for source-level HTML reports.</sub>",
  ];
  return body.join("\n");
}
```

- [ ] **Step 5: Run renderer tests**

Run:

```bash
node --test .github/scripts/coverage-report.test.mjs
```

Expected: four tests pass and no files outside `.github/scripts` are modified.

- [ ] **Step 6: Commit the tested renderer**

```bash
git add .github/scripts/coverage-report.mjs .github/scripts/coverage-report.test.mjs .github/scripts/fixtures/unit-coverage.xml .github/scripts/fixtures/android-coverage.xml
git commit -m "feat10.5: test honest coverage summaries"
```

---

### Task 2: Generate API-29-only Android coverage without changing the PR device matrix

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `-PenableAndroidTestCoverage`, `pixel2Api29DebugAndroidTest`, and only AGP's execution data under `app/build/outputs/managed_device_code_coverage/debug/pixel2Api29/`.
- Produces: Gradle task `createPixel2Api29DebugAndroidTestCoverageReport` and `app/build/reports/coverage/androidTest/debug/pixel2Api29/report.xml` plus HTML.
- The custom task is necessary because AGP 9.1's `createManagedDeviceDebugAndroidTestCoverageReport` depends on every configured managed device, including API 36.

- [ ] **Step 1: Verify the new report task does not exist**

Run:

```bash
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport --dry-run -PenableAndroidTestCoverage
```

Expected: FAIL with “task not found.”

- [ ] **Step 2: Enable the JaCoCo task type and conditional Android instrumentation**

In `app/build.gradle.kts`, add the import and plugin:

```kotlin
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    jacoco
    // Existing aliases remain unchanged.
}

jacoco {
    toolVersion = "0.8.14"
}
```

Define the property once before `android {}` and wire it only to debug Android tests:

```kotlin
val androidTestCoverageRequested =
    providers.gradleProperty("enableAndroidTestCoverage").isPresent

android {
    buildTypes {
        debug {
            enableUnitTestCoverage = project.hasProperty("enableCoverage")
            enableAndroidTestCoverage = androidTestCoverageRequested
        }
    }
}
```

- [ ] **Step 3: Register the narrowly scoped report task**

After the existing dependencies block, register the task only when coverage is requested:

```kotlin
if (androidTestCoverageRequested) {
    val api29ExecutionDataDirectory =
        layout.buildDirectory.dir(
            "outputs/managed_device_code_coverage/debug/pixel2Api29",
        )
    val generatedCoverageClasses =
        listOf(
            "**/R.class",
            "**/R${'$'}*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*_Factory.*",
            "**/*_MembersInjector.*",
            "**/*_Impl.*",
            "**/Hilt_*.*",
            "**/Dagger*.*",
            "**/hilt_aggregated_deps/**",
            "**/dagger/hilt/internal/aggregatedroot/codegen/**",
        )

    tasks.register<JacocoReport>("createPixel2Api29DebugAndroidTestCoverageReport") {
        group = "verification"
        description = "Creates API 29 managed-device JaCoCo XML and HTML reports."
        dependsOn("pixel2Api29DebugAndroidTest")

        executionData.setFrom(
            fileTree(api29ExecutionDataDirectory) {
                include("**/*.ec", "**/*.exec")
            },
        )
        classDirectories.setFrom(
            listOf(
                "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
                "intermediates/javac/debug/compileDebugJavaWithJavac/classes",
            ).map { path ->
                fileTree(layout.buildDirectory.dir(path)) {
                    exclude(generatedCoverageClasses)
                }
            },
        )
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))

        reports {
            xml.required.set(true)
            xml.outputLocation.set(
                layout.buildDirectory.file(
                    "reports/coverage/androidTest/debug/pixel2Api29/report.xml",
                ),
            )
            html.required.set(true)
            html.outputLocation.set(
                layout.buildDirectory.dir(
                    "reports/coverage/androidTest/debug/pixel2Api29/html",
                ),
            )
            csv.required.set(false)
        }

        doFirst {
            val executionFiles = executionData.files.filter { it.isFile }
            check(executionFiles.isNotEmpty()) {
                "API 29 Android coverage execution data was not produced"
            }
            val api29Root = api29ExecutionDataDirectory.get().asFile.toPath()
            check(executionFiles.all { it.toPath().startsWith(api29Root) }) {
                "Android coverage report contains execution data outside pixel2Api29"
            }
        }
    }
}
```

- [ ] **Step 4: Verify the dependency graph contains API 29 but not API 36**

Run:

```bash
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport --dry-run -PenableAndroidTestCoverage
```

Expected: the graph includes `pixel2Api29DebugAndroidTest`, does not include `pixel8Api36DebugAndroidTest`, and ends with `createPixel2Api29DebugAndroidTestCoverageReport`.

- [ ] **Step 5: Run the focused managed-device report**

Run:

```bash
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport \
  -PenableAndroidTestCoverage \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
```

Expected: the API 29 suite passes; `report.xml` and `html/index.html` exist; the XML has non-zero root `LINE` and `BRANCH` counters; every resolved execution-data file is under `debug/pixel2Api29`; stale `debug/pixel8Api36` data is ignored; generated Room and Hilt classes are absent from the HTML class tree. Record the task wall-clock time and preserve at least ten minutes of headroom under the CI timeout.

- [ ] **Step 6: Preserve the existing JVM gate**

Run:

```bash
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
```

Expected: the current 85% line and 70% branch gate passes unchanged.

- [ ] **Step 7: Commit Android report generation**

```bash
git add app/build.gradle.kts
git commit -m "feat10.5: report API 29 instrumented coverage"
```

---

### Task 3: Publish one layer-aware PR comment from two coverage artifacts

**Files:**
- Modify: `.github/workflows/android-ci.yml`

**Interfaces:**
- Consumes: `jvm-unit-coverage-report/report.xml`, `api29-instrumented-coverage-report/report.xml`, and `buildCoverageComment`.
- Produces: one bot comment identified by `## Coverage Report`, updated in place on later pushes.

- [ ] **Step 1: Add the renderer test to `unit-and-coverage`**

Add Node 20 setup after JDK setup and run the pure tests before Gradle. Node 20 intentionally matches the runtime bundled by `actions/github-script@v7`, so fixture tests exercise the same JavaScript language level as the production PR-comment step:

```yaml
      - name: Set up Node 20
        uses: actions/setup-node@v4
        with:
          node-version: "20"

      - name: Test coverage report renderer
        run: node --test .github/scripts/coverage-report.test.mjs
```

Remove `pull-requests: write` from `unit-and-coverage`; the summary job will own comments.

- [ ] **Step 2: Rename the JVM artifact without changing its contents**

Use:

```yaml
      - name: Upload JVM unit coverage report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: jvm-unit-coverage-report
          path: app/build/reports/coverage/test/debug/
          if-no-files-found: error
          retention-days: 14
```

Remove the inline `Post coverage summary` step completely.

- [ ] **Step 3: Make the API 29 job produce coverage in the same test execution**

Raise `api29-hilt-smoke` from `timeout-minutes: 30` to `timeout-minutes: 40` to absorb online-instrumentation overhead, then replace its Gradle command with:

```yaml
      - name: Run API 29 production instrumentation with coverage
        run: >-
          ./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport
          -PenableAndroidTestCoverage
          -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
          -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Add a blocking artifact upload:

```yaml
      - name: Upload API 29 instrumented coverage report
        uses: actions/upload-artifact@v4
        with:
          name: api29-instrumented-coverage-report
          path: app/build/reports/coverage/androidTest/debug/pixel2Api29/
          if-no-files-found: error
          retention-days: 14
```

Keep the existing API 29 test-result, logcat, and additional-output uploads.

- [ ] **Step 4: Add a deterministic summary job**

Append this job after `api29-hilt-smoke`:

```yaml
  coverage-summary:
    name: Coverage Summary
    needs: [unit-and-coverage, api29-hilt-smoke]
    if: >-
      github.event_name == 'pull_request' &&
      github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write

    steps:
      - uses: actions/checkout@v4

      - name: Download JVM unit coverage
        uses: actions/download-artifact@v4
        with:
          name: jvm-unit-coverage-report
          path: coverage/unit

      - name: Download API 29 instrumented coverage
        uses: actions/download-artifact@v4
        with:
          name: api29-instrumented-coverage-report
          path: coverage/android

      - name: Post layer-aware coverage summary
        uses: actions/github-script@v7
        with:
          script: |
            const fs = require('fs');
            const path = require('path');
            const { pathToFileURL } = require('url');
            const modulePath = path.join(
              process.env.GITHUB_WORKSPACE,
              '.github/scripts/coverage-report.mjs',
            );
            const { buildCoverageComment } = await import(
              pathToFileURL(modulePath).href
            );
            const body = buildCoverageComment({
              unitXml: fs.readFileSync('coverage/unit/report.xml', 'utf8'),
              androidXml: fs.readFileSync('coverage/android/report.xml', 'utf8'),
            });
            const { data: comments } = await github.rest.issues.listComments({
              owner: context.repo.owner,
              repo: context.repo.repo,
              issue_number: context.issue.number,
            });
            const existing = comments.find((comment) =>
              comment.user.type === 'Bot' &&
              comment.body.includes('## Coverage Report'),
            );
            if (existing) {
              await github.rest.issues.updateComment({
                owner: context.repo.owner,
                repo: context.repo.repo,
                comment_id: existing.id,
                body,
              });
            } else {
              await github.rest.issues.createComment({
                owner: context.repo.owner,
                repo: context.repo.repo,
                issue_number: context.issue.number,
                body,
              });
            }
```

This job intentionally publishes only after both producer jobs pass. If API 29 fails, GitHub shows the blocking instrumentation failure and retains the JVM artifact, but it does not publish a partial combined comment that could be mistaken for complete multi-layer evidence.

- [ ] **Step 5: Validate workflow syntax and renderer locally**

Run:

```bash
node --test .github/scripts/coverage-report.test.mjs
./gradlew spotlessCheck testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
```

Expected: Node tests pass, Spotless passes, JVM tests pass, and the core coverage gate passes. On the first CI run, record the API 29 job duration and confirm at least ten minutes of timeout headroom; if it does not have that headroom, reduce unrelated setup overhead or raise the explicit timeout again in this PR before merge.

- [ ] **Step 6: Commit the CI composition**

```bash
git add .github/workflows/android-ci.yml
git commit -m "feat10.5: publish layer-aware coverage evidence"
```

---

### Task 4: Document, review, and merge `feat10.5`

**Files:**
- Modify: `docs/testing-strategy.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Produces: one coverage policy shared by humans, Codex, Claude Code, local commands, and CI.

- [ ] **Step 1: Replace the coverage-policy section**

Document these exact rules in `docs/testing-strategy.md`:

```markdown
## Coverage policy

- The hard merge scope is domain, repositories, and non-dev ViewModels: minimum 85% line and 70% branch coverage from JVM tests.
- JVM overall coverage is informational and includes only `src/test` execution. Its rankings include only the hard core scope; Android-only files are never presented as untested JVM-core files.
- API 29 Android instrumented coverage is a separate informational JaCoCo report. The API 29 test task remains a hard pass/fail gate regardless of its percentage.
- SonarQube may later combine JVM and Android XML reports for “covered by any suite” and new-code views. That combined percentage is never the only proof required for a feature.
- Generated Hilt, Dagger, Room, Android resource, manifest, and BuildConfig classes are excluded from percentage reporting.
- Compose, navigation, Room behavior, accessibility, journeys, and performance retain their explicit suite gates even when source lines are covered.
```

Add the exact Android report command and artifact locations under execution and retention:

```bash
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport -PenableAndroidTestCoverage -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
```

Update the retention text at the current `docs/testing-strategy.md` artifact table so the JVM artifact is named `jvm-unit-coverage-report` and the Android artifact is named `api29-instrumented-coverage-report`; both retain XML and HTML for 14 days.

- [ ] **Step 2: Synchronize agent guidance**

Add this command to both `AGENTS.md` and `CLAUDE.md` Build Commands:

```bash
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport -PenableAndroidTestCoverage # API 29 instrumented XML/HTML coverage
```

Add this convention to both files:

```markdown
- Coverage reports must identify their producing layer. JVM rankings are limited to the hard core scope; API 29 instrumented coverage and future unified/new-code views are informational and never replace behavior-suite gates.
```

- [ ] **Step 3: Verify synchronized files**

Run:

```bash
diff -u <(tail -n +4 AGENTS.md) <(tail -n +4 CLAUDE.md)
```

Expected: no output from the shared body beginning at Build Commands. The first three lines intentionally differ because each file names its own agent. Also verify the new coverage command and layer-labeling convention are byte-identical in both files with:

```bash
for pattern in "createPixel2Api29DebugAndroidTestCoverageReport" "Coverage reports must identify their producing layer"; do
  agents_match="$(rg -F "$pattern" AGENTS.md)"
  claude_match="$(rg -F "$pattern" CLAUDE.md)"
  test -n "$agents_match"
  test -n "$claude_match"
  test "$agents_match" = "$claude_match"
done
```

- [ ] **Step 4: Run the complete local PR gate**

Run:

```bash
node --test .github/scripts/coverage-report.test.mjs
./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease assembleDebug assembleRelease
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport -PenableAndroidTestCoverage -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
```

Expected: all commands pass, both XML reports exist, and API 36 is not started.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/testing-strategy.md AGENTS.md CLAUDE.md
git commit -m "docs: define multi-layer coverage policy"
```

- [ ] **Step 6: Request Claude Code review and address verified findings**

Use the `consulting-claude-code` skill in the named Feature 10 coverage review session. Ask for severity-ordered read-only findings covering Gradle task correctness, report semantics, fixture adequacy, generated-code filters, GitHub permissions, fork safety, artifact paths, and accidental API 36 execution. Codex verifies every finding and makes any accepted edits.

- [ ] **Step 7: Raise and merge the pull request**

Use `gh-raise-pr` with:

```text
Title: feat10.5: report honest multi-layer coverage
Head: feat/feat10.5-honest-coverage
Base: main
```

The PR description must include both report paths, exact local commands, the unchanged hard thresholds, the informational Android status, API 29 device evidence, and Claude review disposition. Merge only after all required checks pass and the rendered PR comment contains no Android-only zeroes in the JVM ranking.

---

### Task 5: Provision the SonarQube Cloud OSS boundary

**Files:**
- No repository files change in this task.

**Interfaces:**
- Produces GitHub repository secret `SONAR_TOKEN` and variables `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, and `SONAR_HOST_URL`.
- `SONAR_HOST_URL` is the exact regional URL shown during Sonar onboarding.

- [ ] **Step 1: Create the public OSS organization and project**

Sign in to SonarQube Cloud with the GitHub owner of IronPath, select the OSS plan, install the Sonar GitHub App only for the IronPath repository, and import the repository.

- [ ] **Step 2: Record the non-secret project identifiers as GitHub Actions variables**

Create:

```text
SONAR_PROJECT_KEY
SONAR_ORGANIZATION
SONAR_HOST_URL
```

Use the exact values displayed by SonarQube Cloud. Do not commit them because the same plan must remain usable if the Sonar organization is renamed.

- [ ] **Step 3: Store the scanner credential**

Create the GitHub Actions repository secret:

```text
SONAR_TOKEN
```

Use a Sonar token scoped to analysis of the IronPath project. Never paste its value into a terminal transcript, plan, PR, issue, or source file.

- [ ] **Step 4: Confirm the account boundary before coding `feat10.6`**

Verify that the Sonar project is public, the OSS subscription is active, pull-request analysis is available, and the GitHub App has access only to IronPath. If any condition is false, stop before creating the `feat10.6` branch.

---

### Task 6: Configure SonarScanner for the Android/Kotlin build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: the two downloaded JaCoCo XML paths supplied through `sonar.coverage.jacoco.xmlReportPaths`.
- Produces: Gradle task `sonar` using SonarScanner for Gradle 7.3.1.8318.

- [ ] **Step 1: Verify the scanner task is absent**

Run:

```bash
./gradlew sonar --dry-run
```

Expected: FAIL because no `sonar` task exists.

- [ ] **Step 2: Add the scanner to the version catalog**

Add:

```toml
[versions]
sonarqube = "7.3.1.8318"

[plugins]
sonarqube = { id = "org.sonarqube", version.ref = "sonarqube" }
```

- [ ] **Step 3: Apply and configure the root scanner**

Add the plugin alias to the root `build.gradle.kts` and configure stable properties only:

```kotlin
plugins {
    alias(libs.plugins.sonarqube)
    // Existing plugins remain unchanged.
}

sonar {
    properties {
        property("sonar.projectName", "IronPath")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.kotlin.source.version", "2.3")
        property(
            "sonar.coverage.exclusions",
            listOf(
                "**/R.*",
                "**/BuildConfig.*",
                "**/Manifest*.*",
                "**/*_Factory.*",
                "**/*_MembersInjector.*",
                "**/*_Impl.*",
                "**/Hilt_*.*",
                "**/Dagger*.*",
                "**/hilt_aggregated_deps/**",
                "**/benchmark/**",
            ).joinToString(","),
        )
    }
}
```

Do not put the project key, organization, host URL, or token in this file; CI supplies them.

Keep `:benchmark` source analysis enabled because benchmark quality still matters, while `**/benchmark/**` remains excluded from coverage percentages. The verification steps must confirm that the `com.android.test` module neither breaks scanner configuration nor adds generated benchmark noise; if it does, set `sonar.skipProject` for `:benchmark` in this same PR before merge and record that disposition in the PR description.

- [ ] **Step 4: Verify scanner configuration without uploading**

Run:

```bash
./gradlew sonar --dry-run
```

Expected: plugin version `7.3.1.8318` resolves, Gradle recognizes `sonar`, configuration succeeds under Java 21 for both `:app` and `:benchmark`, and no network analysis is uploaded by the dry run. The original 7.2.3.7755 pin was replaced during implementation after the required red check reproduced its AGP 9.1 `AppExtension` incompatibility; 7.3.1 is the current patch with AGP 9 and KSP fixes.

- [ ] **Step 5: Commit scanner configuration**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "feat10.6: configure SonarQube Cloud scanner"
```

---

### Task 7: Upload both coverage layers and decorate pull requests

**Files:**
- Modify: `.github/workflows/android-ci.yml`

**Interfaces:**
- Consumes: GitHub secret/variables from Task 5 and both coverage artifacts from `feat10.5`.
- Produces: Sonar analysis for same-repository pull requests and pushes to `main`.

- [ ] **Step 1: Add the Sonar job after all hard producing jobs**

Append:

```yaml
  sonar-analysis:
    name: SonarQube Analysis
    needs: [static-and-build, unit-and-coverage, api29-hilt-smoke]
    if: >-
      github.event_name == 'push' ||
      github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    permissions:
      contents: read

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: gradle

      - name: Grant gradlew execute permission
        run: chmod +x gradlew

      - name: Create local.properties
        run: echo "sdk.dir=$ANDROID_HOME" > local.properties

      - name: Download JVM unit coverage
        uses: actions/download-artifact@v4
        with:
          name: jvm-unit-coverage-report
          path: coverage/unit

      - name: Download API 29 instrumented coverage
        uses: actions/download-artifact@v4
        with:
          name: api29-instrumented-coverage-report
          path: coverage/android

      - name: Verify Sonar configuration
        shell: bash
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_PROJECT_KEY: ${{ vars.SONAR_PROJECT_KEY }}
          SONAR_ORGANIZATION: ${{ vars.SONAR_ORGANIZATION }}
          SONAR_HOST_URL: ${{ vars.SONAR_HOST_URL }}
        run: |
          required=(SONAR_TOKEN SONAR_PROJECT_KEY SONAR_ORGANIZATION SONAR_HOST_URL)
          for name in "${required[@]}"; do
            if [[ -z "${!name}" ]]; then
              echo "Required Sonar configuration is missing: ${name}"
              exit 1
            fi
          done
          test -s coverage/unit/report.xml
          test -s coverage/android/report.xml

      - name: Build and analyze
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_PROJECT_KEY: ${{ vars.SONAR_PROJECT_KEY }}
          SONAR_ORGANIZATION: ${{ vars.SONAR_ORGANIZATION }}
          SONAR_HOST_URL: ${{ vars.SONAR_HOST_URL }}
        run: >-
          ./gradlew :app:assembleDebug sonar
          -Dsonar.projectKey="$SONAR_PROJECT_KEY"
          -Dsonar.organization="$SONAR_ORGANIZATION"
          -Dsonar.coverage.jacoco.xmlReportPaths="$GITHUB_WORKSPACE/coverage/unit/report.xml,$GITHUB_WORKSPACE/coverage/android/report.xml"
```

`SONAR_TOKEN` and `SONAR_HOST_URL` stay in the step environment because SonarScanner reads them directly. Never repeat either secret-bearing value as a command-line `-D` argument. Do not add `continue-on-error` and do not add `-Dsonar.qualitygate.wait=true`. Scanner configuration/upload failures fail this job; a server-side quality-gate warning does not control the Gradle exit code.

- [ ] **Step 2: Validate fork safety and job dependencies by inspection**

Confirm:

```text
Internal pull request: Sonar job runs after both reports.
Push to main: Sonar job runs after both reports.
Fork pull request: Sonar job is skipped because secrets are unavailable.
Failed unit or API 29 suite: Sonar job does not run.
```

- [ ] **Step 3: Commit CI integration**

```bash
git add .github/workflows/android-ci.yml
git commit -m "feat10.6: analyze coverage and new code in SonarQube"
```

---

### Task 8: Calibrate Sonar policy, document ownership, review, and merge

**Files:**
- Modify: `docs/testing-strategy.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Produces: a warning-only Sonar policy and a reproducible local scan command.

- [ ] **Step 1: Configure the Sonar project policy**

In SonarQube Cloud:

1. Disable automatic analysis before enabling CI-based analysis.
2. Use the built-in `Sonar way` quality gate for the initial baseline.
3. Confirm that new-code coverage warns below 80%, new duplication warns above 3%, new issues are visible, and security hotspots require review.
4. Do not make the Sonar quality-gate GitHub check a required branch-protection check.
5. Keep existing GitHub hard checks required.

- [ ] **Step 2: Document Sonar as an informational aggregator**

Add to `docs/testing-strategy.md`:

```markdown
## SonarQube policy

- SonarQube Cloud OSS imports the JVM and managed API 29 JaCoCo XML reports after their producing test jobs pass.
- Sonar overall and new-code coverage answer whether source was executed by either imported suite; they do not replace layer-specific behavior gates.
- The built-in Sonar way gate is warning-only for this portfolio phase. Do not require its GitHub check and do not make the scanner wait for the server-side quality-gate result.
- Scanner or upload failure is a CI failure. A low Sonar percentage is visible but mergeable.
- Coverage on new code below 80% triggers review. Reviewers inspect branch/condition coverage and the applicable JVM, Room, Compose, navigation, accessibility, journey, and performance tests before deciding whether the gap is acceptable.
- Sonar secrets and account identifiers are repository settings. Fork pull requests never receive the token and skip Sonar upload.
```

- [ ] **Step 3: Synchronize the local scan command**

Add to both `AGENTS.md` and `CLAUDE.md`:

```bash
test -n "${SONAR_TOKEN:-}" && test -n "${SONAR_HOST_URL:-}" && test -n "${SONAR_PROJECT_KEY:-}" && test -n "${SONAR_ORGANIZATION:-}"
./gradlew :app:assembleDebug sonar -Dsonar.projectKey="$SONAR_PROJECT_KEY" -Dsonar.organization="$SONAR_ORGANIZATION" -Dsonar.coverage.jacoco.xmlReportPaths=coverage/unit/report.xml,coverage/android/report.xml
```

This command assumes the four values have already been exported into the caller's shell. The token and host URL remain environment-only and never appear in process arguments. The command is optional locally; required test gates remain independent of Sonar availability.

- [ ] **Step 4: Verify policy files and local build configuration**

Run:

```bash
diff -u <(tail -n +4 AGENTS.md) <(tail -n +4 CLAUDE.md)
./gradlew spotlessCheck :app:lintDebug :app:assembleDebug :app:assembleRelease
node --test .github/scripts/coverage-report.test.mjs
```

Expected: the shared policy bodies match after the intentional three-line preambles, formatting and builds pass, renderer tests pass, and the exact Sonar commands added to both guidance files are byte-identical.

- [ ] **Step 5: Request final Claude Code review and address verified findings**

Use the same named Feature 10 coverage review session. Ask for severity-ordered read-only findings covering secret safety, scanner version/JDK compatibility, Android/Kotlin bytecode discovery, report-path resolution, fork behavior, `:benchmark` analysis, duplicate analysis, automatic-analysis shutdown, quality-gate non-blocking semantics, and documentation accuracy. Codex verifies every finding before editing.

- [ ] **Step 6: Commit documentation**

```bash
git add docs/testing-strategy.md AGENTS.md CLAUDE.md
git commit -m "docs: define SonarQube warning policy"
```

- [ ] **Step 7: Raise the pull request**

Use `gh-raise-pr` with:

```text
Title: feat10.6: add SonarQube Cloud OSS analysis
Head: feat/feat10.6-sonarqube-cloud
Base: main
```

The PR description must include the Sonar project link, imported report paths, GitHub variables by name, exact local checks, current quality-gate status, explanation of why Sonar is non-blocking, `:benchmark` scanner disposition, and Claude review disposition.

- [ ] **Step 8: Inspect the first CI-based analysis and merge**

Expected Sonar evidence:

```text
Both JaCoCo XML files are imported.
Core JVM files retain coverage from unit tests.
Room, Compose, and navigation files exercised on API 29 are not shown as zero solely because their tests are instrumented.
Generated Hilt, Dagger, Room implementation, Android resource, manifest, and benchmark code is excluded from coverage.
The PR shows issues, hotspots, duplication, and coverage on new code.
The PR remains mergeable when only the Sonar quality gate is below its target.
```

The first PR may show `Not Computed` until Sonar has a main-branch baseline. That state is acceptable only if the scanner upload succeeded and the absence of a baseline is confirmed in Sonar. Merge only after all existing required checks and the Sonar scanner job pass. The resulting `push: main` workflow establishes the baseline; verify the main dashboard after merge and record that normal changed-code PR comparison will be checked on the next code pull request.

---

## Final Acceptance Matrix

| Requirement | Evidence |
|---|---|
| JVM-only UI and generated zeroes are no longer misleading | Fixture test proves they cannot appear in the JVM-core ranking; real PR comment confirms |
| Existing core gate is unchanged | `verifyCoreCoverage` remains 85% line / 70% branch and passes |
| Instrumented coverage is measured | API 29 XML/HTML artifact has non-zero counters and source highlighting |
| API 36 is not added to PR coverage | Execution data is rooted at `debug/pixel2Api29`; Gradle dry-run and CI log contain API 29 only |
| Tests remain the primary gates | API 29 task, unit tests, lint, and builds remain required |
| Combined “covered by any suite” view exists | Sonar imports both XML reports |
| Combined percentage is not authoritative | Sonar quality gate is not required; strategy documents explicit layer gates |
| New-code weakness is visible | Sonar reports coverage and branch/condition gaps on changed code, warning below 80% |
| External contributions are safe | Fork PRs skip token-dependent comments/uploads while hard tests still run |
| Agent instructions agree | `AGENTS.md` and `CLAUDE.md` share an identical body from Build Commands onward; only their three-line agent-specific preambles differ |

## Rollback Boundaries

- If the custom API 29 report task proves unstable, revert only `feat10.5`'s Android percentage/comment additions; keep the existing hard API 29 test and JVM core gate.
- If SonarQube Cloud becomes unavailable, remove the `sonar-analysis` job and scanner plugin; the separate local/CI reports and all hard gates remain intact.
- Never weaken a test, threshold, accessibility gate, journey, migration check, or build gate to make a coverage or Sonar dashboard green.
