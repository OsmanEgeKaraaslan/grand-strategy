# In-memory game engine (Phase 8) — design note

Status: **PROPOSED** (2026-11-20). Approved to start by the product owner on 2026-11-20 ("design
first"); the open decisions in §8 need answers before the stages that depend on them.

## 1. Problem

Measured on a Grand Campaign game (72 provinces, 10 nations, 8 computer seats):

| Work | SQL statements | Why |
|---|---|---|
| One round of computer turns (~30 actions) | ~2,850 in ~130 transactions | every action is a full command; the computer re-reads five module views (~84 statements) before planning |
| One human move | ~33 | load, check and write through three modules |
| Refetch after a command (state + economy + military) | ~48 | views are rebuilt from the database |

Against a hosted database every statement is a round trip (~20 ms from a home connection, 1–2 ms
from a VPS in the same region), so a computer round costs between a few seconds and about a minute.
The gameplay itself is cheap: planning a computer turn takes microseconds. The time is spent on
loading and writing state one module and one action at a time.

## 2. Goal

1. Load a game's state **once**, play any number of commands on it **in memory**, write the result
   **once**.
2. Keep exactly one implementation of every rule: the services, the computer opponents and later the
   browser run the same pure functions.
3. Keep every guarantee of today's design (AGENTS.md §2): the command pipeline order, idempotency,
   the ordered round phases, per-game keys, module boundaries.

Targets: a computer round under 200 ms of database time on a same-region database (one load plus one
write per computer turn); a human move ≤ 10 statements; all existing tests green at every stage.

## 3. What stays and what changes

Stays:

- **Rules** in `..rules..` packages (pure, deterministic): `Movement`, `Battle`, `Retreat`,
  `Recruitment`, `Construction`, `Research`, siege and diplomacy rules. They already take records in
  and return results out.
- **The command pipeline** (`GameCommands`): lock game row → membership → idempotency → in progress →
  active player → apply → record command → bump state version → commit. Only "apply" changes inside.
- **The schema**: no table is replaced. Writes move from JPA entity mutation to explicit writes.
- **Module boundaries**: each module keeps owning its tables and its part of the state.

Changes:

- Services stop interleaving "load, check, mutate entity, save, call another module's hook". A
  command becomes `apply(GameState, Command) → Result(GameState, List<Event>)`, a pure function.
- Cross-module effects (a move starts a siege; recruitment takes population from the world and gold
  from the economy) become calls between pure functions over the same in-memory state, not hooks
  that write to the database mid-command.

## 4. The model

```
GameState (immutable)                       loaded by GameStateStore in a fixed number of queries
├── header: round, turn, active seat, ruleset, paused conference, state version
├── world:     provinces (owner, controller, population), nations
├── economy:   treasuries, buildings (workshops, projects), last reports
├── military:  battalions, armies, cohorts
├── siege:     fortresses, active sieges
├── research:  nation research, doctrines, institutions
└── diplomacy: relations, wars, alliances, calls, proposals, treaties, conferences, gifts
```

- Each module defines its own immutable slice (`MilitaryState`, `EconomyState`, …) and a store that
  loads and persists only its tables. `GameState` composes the slices; nothing outside a module
  reads its slice except through that module's `api` functions (ArchUnit/Modulith rules unchanged).
- Collections are maps keyed by id with deterministic iteration (sorted by id), so results never
  depend on load order (AGENTS.md §3).
- Static map topology stays cached per map template, as today.

## 5. Applying and persisting

```
apply(state, command) → Result(newState, events)      pure; throws a typed domain refusal
persist(old, new)     → writes per slice              diff of changed rows by id, one transaction
events                → battle reports, history, decision log, notifications (as today)
```

- **Diff, not hand-written updates:** each slice compares old and new maps by id (inserted, updated,
  deleted rows). Immutable maps make unchanged rows identical objects, so the diff is cheap and a
  forgotten write cannot happen.
- **Writes** use batched JDBC per table (insert/update/delete), with the same constraints and
  composite keys; the state version bump and the command record stay in the pipeline.
- **Refusals** are the same typed exceptions with the same codes, thrown before anything is written.

## 6. Computer phase

```
load GameState once
repeat for each consecutive computer seat:
    observe  = projection of the in-memory state (no queries)
    plan     = AiBrain.planTurn(observe)            unchanged pure planning
    for each action: state = apply(state, action)   refused actions are skipped and logged as today
    state = apply(state, EndTurn)                   incl. round processing once rounds are pure (S5)
stop at a human seat, a paused conference, or the end of the game
persist(diff) + command rows + AI turn rows          one transaction per computer turn (decision D2)
```

Idempotency keys stay derived from (game, turn, sequence), so a crash or a second runner never plays
an action twice: the transaction of a turn either commits completely or not at all.

## 7. Stages

Each stage is shippable, keeps all tests green and deletes the code it replaces (no permanent dual
paths). Measured with `QueryBudgetIT` and a new computer-round budget test.

| Stage | Content | Expected effect |
|---|---|---|
| **S1** | `GameState` loaders per slice (read-only) and in-memory projections for the computer observation | computer observation ~84 → ~15 statements, once per turn |
| **S2** | Military commands pure + persisted by diff: recruit, move, battle, reinforce, disband, regroup, attach/detach | human move ~33 → ≤ 10; most computer actions in memory |
| **S3** | Economy (build, upgrade, repair), research (institutions, technology, doctrines), sieges (fortress build/repair) | remaining computer actions in memory |
| **S4** | Diplomacy commands (war, alliances, gold, calls, peace, conferences) | every command pure |
| **S5** | Round phases as pure functions in `RoundProcessor` order (sieges, economy, tribute, research, institutions, growth, …) | computer phases cross round ends in memory |
| **S6** | Computer phase in memory (§6) | computer round: one load and one write per computer turn |
| **S7** | Same pure functions in the browser (TeaVM) to predict battles and economic actions (D-UX1 extended) | instant feedback for every command; needs dependency approval |

S1 alone gives a large part of the computer gain without touching any write path, so it comes first.

**S1 status (2026-11-20, first step done):** the computer observation runs in a read scope
(`platform.read.ReadScope`): inside one read-only transaction the world snapshot, membership,
research effects, active sieges and the diplomacy picture are read once and shared by the five module
views. Results are identical by construction (one REPEATABLE READ snapshot; the scope refuses to open
in a writing transaction). Measured: one observation 84 → 42 statements, one round of 8 computer turns
~2,850 → 2,176. `ComputerQueryBudgetIT` guards both. Still to do in S1: the slice loaders and pure
projections that replace the module views for the computer (target ~15 statements per observation).

**S2a status (2026-11-20, lean military loading, no write path changed):** the military `State`
reads every nation's modifiers and the diplomatic relations only when a command first needs them
(recruitment and reinforcement never do), and a plain move hands the snapshot and relations it read
to the siege module instead of letting it read them again (after a battle the siege module still
reads fresh state, since a battle can end a war). Measured: recruitment 24 → 18 statements, plain
move 33 → 24, one computer round 2,176 → 1,989. Budgets tightened in `QueryBudgetIT` and
`ComputerQueryBudgetIT`. The pure apply functions and diff writes of S2 proper are still to do.

## 8. Open decisions

| ID | Question | Proposed default |
|---|---|---|
| **D1** | Keep a per-game `GameState` cache between requests (keyed by state version), or load per command? | Load per command first (simple, correct with several instances); add a cache later only if measurements ask for it |
| **D2** | Persist a computer phase per computer turn or once for the whole phase? | Per computer turn: a crash loses at most one turn, and humans see progress |
| **D3** | TeaVM (or another Java→JS/Wasm compiler) as a new build dependency for S7 | Decide when S6 is done; written justification then (AGENTS.md §1) |
| **D4** | Diff writes with JDBC batches, or keep JPA entities for writes? | JDBC batches for migrated tables (no persistence-context surprises, fewer statements) |

## 9. Risks and how they are contained

- **Behaviour drift while migrating:** a command is migrated completely and its old code deleted in
  the same stage; the existing ~700 tests (golden balance scenarios, campaign scenarios, concurrency
  and authorization matrices) run unchanged against the new path.
- **Hidden write paths:** hooks that write mid-command (`presenceChanged`, population changes,
  treasury spending) are listed per stage and turned into pure calls before that stage ships.
- **Memory:** a Grand Campaign state is small (hundreds of rows); loading it is cheaper than the
  per-module reads it replaces.
- **Concurrency:** unchanged — the game row lock serialises commands; the computer phase holds the
  lock per computer turn, as each command does today.
