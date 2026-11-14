# AI Usage

## Tool used
Claude Code (Anthropic, model Claude Opus 5.5) was used as a coding assistant for Task 1.

## What the AI did
- Inspected the generated Spring Initializr project and fixed the `pom.xml` (it mixed Spring Boot 3.5
  with Boot 4-style starters and pulled in Spring AI Redis / Spring Boot Admin, which are out of scope).
- Wrote the Flyway migrations, entities, repositories, services, controllers and DTOs.
- Implemented the matching logic (normalization, homoglyph folding, OSA distance, scoring).
- Wrote the unit, integration and evaluation tests, and the `labelled_handles.csv` dataset.
- Ran `mvn clean test` and fixed failures (e.g. Greek capital `Ν` was folded to `v` because lower-casing
  happened before homoglyph folding; Java 21-only `List.reversed()` used while targeting Java 17).
- Smoke-tested the packaged app against a temporary local PostgreSQL 14 instance.
- Drafted this file and the README.

## What a human should review
- **VPA regex**: the assignment's exact regex was not available to the AI. The implementation uses
  `^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$` (in `ScreeningRequest.VPA_REGEX`). Replace it if the
  assignment specifies a different one.
- **Homoglyph/digit tables**: the "required" Cyrillic/Greek and digit substitutions were implemented from
  common confusable sets (see `TextNormalizer`). Compare against the assignment's list.
- **Dataset bias**: `labelled_handles.csv` was written by the AI, i.e. by the same author as the matcher.
  Labels were chosen by judgment ("is this impersonation?"), not by running the matcher, and include known
  hard cases, but the evaluation numbers are still optimistic compared to real traffic.

## Integrity
Evaluation metrics are computed at test time from real `MatchingService` decisions; nothing is hard-coded.
