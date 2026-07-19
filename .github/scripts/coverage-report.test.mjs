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

test("parser ignores nested real-world counters when selecting report totals", () => {
  const report = parseJacocoReport(fixture("realistic-coverage.xml"));
  assert.deepEqual(report.root.line, {
    missed: 4,
    covered: 6,
    total: 10,
    pct: 60,
  });
  assert.deepEqual(report.root.branch, {
    missed: 2,
    covered: 4,
    total: 6,
    pct: 67,
  });
  assert.equal(report.files[0].name, "com/example/ironpath/domain/planner/PlanGenerator.kt");
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
