# DeepSeek Pi Executor Handover

This document is the repository-specific operating contract for delegating a
bounded IronPath task to DeepSeek Pro through the official PI workspace route.
It is a handover guide, not a replacement for repository policy.

## Authority and role boundary

Codex is the orchestrator and decision-maker for an IronPath task. Codex (or a
Codex Sol reviewer) owns scope selection, risk classification, task admission,
acceptance, GitHub actions, and final integration. An independent Codex
Sol/high review is required before accepting a Pi result.

DeepSeek Pro Pi is the default **executor** only for a qualifying implementation
task: clear, narrowly bounded, reversible, non-sensitive, and supplied with
deterministic acceptance criteria and verification commands. Pi does not make
architecture, product, security, or release decisions, and it is not a reviewer
or a substitute for Codex verification.

## Non-delegable work

Do not send any of the following to Pi, even as a small portion of a larger task:

- security rules, authorization, authentication, credentials, or account identity;
- privacy, personal data, telemetry, or sensitive logging;
- payments, billing, quotas, or commercial configuration;
- database migrations, restore/reset/data-deletion behavior, or other data-loss risk;
- concurrency, synchronization, race-condition handling, or background-work
  coordination;
- deployment, release, CI/CD policy, secrets, infrastructure, or live-service setup;
- architecture, module/interface boundary decisions, or dependency selection;
- unresolved debugging, flaky-test diagnosis, or any task whose root cause is not
  already established.

For IronPath, the current V5 account, Google sign-in, Firestore backup/restore,
ownership, sentinel, deletion, and Firebase Rules work falls in these exclusions.
Codex/Sol retains it even when an individual edit appears mechanical.

## Context and data boundary

Pi has no Agent Shared access. Do not give it Agent Shared paths, history, user
profile information, credentials, API keys, tokens, `google-services.json`, live
Firebase identifiers, OAuth configuration, emulator secrets, logs containing user
data, or broad project/workspace access.

Codex provides the smallest task-local context needed to execute the capsule:
the approved files, relevant excerpts or paths, exact acceptance criteria, and
fixed commands. A task may expose only its dedicated worktree and the explicitly
listed writable files or roots. Pi must not discover or inspect parent workspaces,
other worktrees, home directories, Agent Shared, keychains, or unrelated project
files.

## Required TASK.md instructions

The PI harness disables automatic context-file loading. Therefore **every**
`TASK.md` must explicitly require Pi to read, before editing:

1. the task worktree's `AGENTS.md`;
2. this `docs/deepseek-executor-handover.md` handover;
3. the PRD relevant to the approved slice (currently
   `docs/ironpath-v5-menu-account-backup-prd.md` for V5 work, plus any inherited
   PRD named there when applicable); and
4. `docs/testing-strategy.md`.

The task file must say that Pi does **not** auto-load `AGENTS.md`; reading it is
an explicit prerequisite, not an assumption. If the required documents are outside
the approved task-local context, Codex must provide only the needed copies or
excerpts inside the dedicated worktree; Pi must stop rather than expand access.

## Mandatory task capsule

Codex writes a complete, single-purpose capsule before launch. It contains:

| Field | Requirement |
| --- | --- |
| Worktree | A dedicated worktree created from a named base commit and branch. |
| Deliverable | One concrete user-visible or maintenance outcome with acceptance criteria. |
| Writable scope | Exact files or roots. All other paths are read-only; unlisted paths are forbidden. |
| Context | Only the approved repository documents and task-local source/test files. |
| Commands | An ordered, fixed command vector with expected success criteria. Pi may not substitute, extend, or invent commands. |
| Network and tools | `network: prohibited`; no GitHub actions, Git commands or mutations, package installs, dependency updates, or external tools. |
| Execution limit | One launch, no retry. A timeout, stall, invalid output, or failing fixed command is a handback blocker, not a reason to relaunch. |
| Cost | A declared conservative maximum cost in the manifest; reject admission if the task cannot fit it. |
| Review | One independent, read-only Codex Sol/high review after Codex inspects the diff. |

The manifest must also set a bounded tool-call count, wall-clock limit, and
stall limit. The task remains outside Pi's scope if any requirement cannot be
made exact before launch.

## IronPath verification selection

Follow `AGENTS.md` and `docs/testing-strategy.md`; choose proof proportional to
the changed risk rather than running every suite indiscriminately. The capsule
must name the exact selected commands and why they apply.

- A documentation-only task normally uses Markdown/source sanity such as targeted
  `test -f` and `rg` repository-reference checks. It does not need Android builds
  merely because the repository contains an Android app.
- A pure domain or ViewModel change starts with focused JVM tests and adds happy,
  error, and boundary proof.
- A Room entity, DAO, transaction, or schema change requires real Room testing;
  a version change also requires the supported migration tests and exported schema
  evidence.
- A Compose component, route, or navigation change requires the relevant isolated
  Compose/NavHost tests, semantic/accessibility proof, and the applicable
  recreation or journey coverage.
- A performance-sensitive change requires the relevant profile or benchmark proof.

For an ordinary Android PR, the repository's CI evidence must include `Static &
Build`, `Unit Tests & Coverage`, and `API 29 Hilt Smoke`. The principal local
commands, selected only when their layer is relevant, are:

```bash
./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease assembleDebug assembleRelease
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.ironpath.accessibility.PlatformAccessibilityChecksTest
```

Use JDK 21 and the Gradle wrapper. Seeker is the preferred physical device for
instrumented smoke work; when it is unavailable, follow the managed API 29 path
in `AGENTS.md`. API 36 accessibility, Baseline Profile, macrobenchmark, Firebase
emulator, and live manual checks are selected only when the feature-risk matrix or
the approved task requires them. Pi must never add a network dependency, Google
account, live Firebase project, or paid service to make verification pass.

## Execution and handback

Pi may edit only the declared writable scope, run only the fixed command vector,
and return a structured result. It must stop and hand back instead of making a
judgment call when it encounters an unlisted file, missing tool, unclear
requirement, failing command, unexpected diff, conflict, credential request, or
potentially sensitive data.

Every handback to Codex must contain:

1. the exact diff and list of changed files;
2. every fixed command and its result, including commands not run and why;
3. any scope deviation, unexpected read, or attempted-but-blocked operation;
4. blockers, assumptions, and decisions deferred to Codex;
5. the Pi result record, route/model, timing, token/cost evidence, and whether the
   one-launch/no-retry limits were respected; and
6. PR evidence available at handback (base/head, commit, review state, or CI state).

Codex independently inspects the final diff, reruns the relevant verification,
obtains the Sol/high review, and alone decides whether to commit, open a PR, request
changes, or discard the result.

The exact diff is produced by the PI harness or by Codex from the dedicated worktree;
Pi does not use Git to collect it.

## Admission checklist for Codex

Before a Pi launch, confirm all answers are yes:

- Is the task clear, reversible, non-sensitive, and outside every exclusion above?
- Are exact writable files/roots and a dedicated worktree defined?
- Does `TASK.md` explicitly require the four prerequisite documents and explain the
  disabled automatic `AGENTS.md` loading?
- Are the context, fixed commands, no-network/no-install/no-git rules, one-launch
  policy, tool/wall/stall bounds, and cost cap present in the manifest?
- Can the result be accepted or rejected from the stated diff and verification
  evidence without asking Pi for an architecture or security judgment?
- Is an independent Codex Sol/high reviewer reserved for the completed diff?

If any answer is no, keep the work with Codex/Sol or narrow the task until it is
admissible.
