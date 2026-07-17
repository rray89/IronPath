# IronPath Testing Strategy

## Test pyramid
- Pure JVM tests: domain rules, reducers/validators, ViewModel state, error and concurrency branches.
- Room integration tests: every DAO query, constraint, cascade, migration, and cross-DAO transaction.
- Compose tests: every user-visible state, validation message, enabled/disabled action, and callback contract.
- Real-app journey tests: only critical navigation/persistence paths; keep them few and deterministic.
- Performance/accessibility: release startup, critical scrolling/input, semantics, font scale, and touch targets.

## Feature risk matrix
| Change | Required proof |
|---|---|
| Domain or ViewModel | failing JVM test, success/error/boundary cases |
| Entity, DAO, query, transaction | real Room test; migration test for schema changes |
| Screen or component | isolated Compose state/action test and accessibility semantics |
| Route/back stack | NavHost test |
| Critical user journey | real-app persistence test |
| Time/date behavior | fixed timezone and boundary-date tests through TimeProvider |
| Performance-sensitive flow | benchmark or explicit performance evidence |

## Definition of Done
1. Product contract and acceptance criteria are written before code.
2. Tests fail for the intended reason before production code changes.
3. Happy path, empty state, validation, failure, duplicate/concurrent action, and recreation behavior are covered where applicable.
4. Relevant JVM, Room, Compose, navigation, and journey tests pass locally.
5. Spotless, lint, debug build, release build, and coverage gates pass.
6. Schema changes include exported schema JSON plus every supported migration path test.
7. No disabled, ignored, order-dependent, sleeping, or retry-masked test is merged.

## Coverage policy
- Scope: domain, repositories, and non-dev ViewModels.
- Minimum: 85% line and 70% branch.
- Changed production logic must not reduce either metric.
- Until base-versus-head automation lands in the production-gates PR, reviewers compare the scoped percentages manually.
- Compose/generated code is assessed through behavior tests, not JaCoCo percentage.

## Migration policy

Every database version increment must export the new schema and add both a direct previous-to-current migration test and an oldest-supported-to-current all-migrations test. The test must assert representative data, constraints, and indexes; schema validation alone is insufficient.

## Flake policy
- Quarantine is not a pass: a flaky test blocks release until fixed or removed with an approved replacement.
- Use test schedulers/idling/observable state, never sleeps.
- Fix locale, timezone, clock, animation clock, and database state in test setup.
