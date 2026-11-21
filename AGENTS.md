# AGENTS.md — Standing instructions for coding agents

These instructions apply to every AI coding session (Codex, Claude Code, etc.) and to
human contributors. Read this file and the relevant `docs/` files before changing
anything. When this file conflicts with a one-off request, ask before deviating.

## 1. Scope discipline

- Work only on the phase and task you were asked to do. Check
  [docs/ROADMAP.md](docs/ROADMAP.md) for the current phase and its **explicit non-goals**.
- Never start the next phase automatically.
- Never implement a mechanic labelled **OPEN** in the docs. If a task needs one,
  stop and ask, or implement behind the documented PROPOSED default **only** if the
  task explicitly says the proposal is accepted.
- Never silently invent game rules. If you need a rule that is not documented,
  add it to [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md) and ask.
- Do not add dependencies, infrastructure (Redis, Kafka, brokers, Kubernetes,
  microservices) or frameworks without a written justification approved by the user.

## 2. Architecture rules

- Modular monolith. Package root `com.reach.strategy`; one top-level package per
  module (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)). Cross-module access only
  through each module's public `api` package. Module boundary tests (ArchUnit or
  Spring Modulith `verify()`) must pass once the first feature module exists (Phase 2).
- Cross-cutting technical code (security, error handling, request correlation, health)
  lives in `platform`; it must never contain game rules.
- **Rules engine is pure Java**: `..rules..` packages depend on nothing from Spring,
  JPA, Jackson or the database. They take immutable records in and return
  immutable results out. No clocks, no randomness, no I/O.
- Controllers are thin: authenticate → map DTO → call one application service
  method → map result. No business logic in controllers.
- Every gameplay mutation goes through the **game command pipeline**
  (lock game row → authorize → idempotency check → validate → apply → record
  command/events → commit). No other code path may mutate per-game state.
- Global round processing runs only through the `RoundProcessor` with its explicit,
  ordered list of phases. Do not rely on Spring bean ordering for game logic.
- Every per-game table carries `game_id`; cross-table references inside a game use
  composite foreign keys `(game_id, id)` so rows of different games can never be mixed.
- Static map templates are never mutated by gameplay.
- The frontend never talks to game tables via Supabase. Supabase is used for
  authentication only.

## 3. Coding standards (Java)

- Java 21: records for value objects/DTOs, sealed interfaces for command and
  result hierarchies, `switch` pattern matching, no Lombok unless approved.
- Strong types for domain quantities: `Gold` (long milligold), `Population` (long
  persons) and typed IDs where it prevents mix-ups. Never use `double`/`float`
  for gold, population, or rule calculations. Use `long` or `BigDecimal` with an
  explicit scale and `RoundingMode` named in the docs.
- Every rounding operation must cite the rule in [docs/ECONOMY.md](docs/ECONOMY.md)
  or [docs/COMBAT_AND_SIEGE.md](docs/COMBAT_AND_SIEGE.md) in a short comment.
- All balance numbers live in the versioned ruleset configuration, never as magic
  numbers in code.
- Iterate collections in a deterministic order (sort by id) whenever order can
  affect results.
- Validation: Jakarta Bean Validation on request DTOs for shape; domain validation
  in the rules/application layer for game legality.
- Errors: throw typed domain exceptions mapped to RFC 9457 problem responses with a
  stable `code` (see [docs/API_DESIGN.md](docs/API_DESIGN.md)).
- Logging: structured (JSON in prod), include `requestId`, `userId`, `gameId`,
  `commandId` in MDC. Never log tokens, emails or personal data.
- Database: every schema change is a new Flyway migration (`V<n>__description.sql`).
  Never edit an applied migration. Add constraints and indexes deliberately.
- Comments explain *why*, not *what*. Keep them sparse.

## 4. Coding standards (TypeScript/React)

- Strict TypeScript. API types generated from the backend OpenAPI spec; do not
  hand-write duplicates.
- Server state via TanStack Query; no client-side "authoritative" game state.
- Components render server state and submit intents. Optimistic updates only for
  purely cosmetic UI, or for commands whose result the client can predict exactly from
  what it already knows (D-UX1: every command except end turn and moves that fight; the
  predictions live in `frontend/src/api/predict.ts`). A prediction is always rolled back on a
  refusal and replaced by the next server state; never predict hidden information. Consequences
  that depend on others (who joins a war, battalions that must withdraw) and battle outcomes
  are shown only when the server answers.
- The map is SVG rendered from template geometry; game state is overlaid.

## 5. Testing requirements

- Rules engine: unit tests for every rule, including the worked examples in the docs
  as golden tests. A doc example and a test must never disagree; if a rule changes,
  update both.
- Property-based tests (jqwik) for invariants: population conservation, gold
  never accidentally negative, determinism, employment priority.
- Application services: PostgreSQL integration tests with Testcontainers. No H2.
- Concurrency: tests that fire parallel requests (e.g. double end-turn) and assert
  exactly-once effects.
- Security: tests for unauthenticated, non-member, non-active-player and
  wrong-owner access on every new endpoint.
- Frontend: Vitest + React Testing Library for components with logic; Playwright for
  the vertical-slice happy path once it exists.
- A task is not done until `mvn verify` and frontend tests pass locally and in CI.

## 6. Documentation conventions

- Every rule is labelled **APPROVED** (given by the product owner), **PROPOSED**
  (our suggestion, configurable) or **OPEN** (needs a decision).
- Changing a rule's status requires updating [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md)
  (record the decision and date) and every document that references it.
- Numbers in docs use the same units as code: gold in whole gold with up to 3
  decimals (stored as milligold), population in persons.
- Keep docs and code consistent in the same change. Update the README status line
  when a phase completes.

## 7. Git and workflow

- Small, focused commits with imperative messages. One logical change per PR.
- Do not commit secrets. Configuration via environment variables; `.env.example`
  documents them.
- Do not push, deploy or run destructive database commands without explicit approval.
