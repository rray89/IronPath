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
  const rows = ["| Metric | Covered | Total | % |", "|---|---:|---:|---:|"];
  for (const [type, label] of Object.entries(labels)) {
    const value = report.root[type];
    if (value) {
      rows.push(
        `| ${label} | ${value.covered} | ${value.total} | **${value.pct}%** |`,
      );
    }
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
