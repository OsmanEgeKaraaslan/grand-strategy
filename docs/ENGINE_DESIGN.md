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

**S2 proper — implementation plan (2026-11-20).** The military tables (`battalion`, `army`,
`battalion_cohort`; `manpower_transfer` and `battle_report` are already JDBC) are only used inside the
military module: `MilitaryService`, `BattalionService`, `AttachmentService`, `WithdrawalService`,
`MilitaryHooks`, `PresenceService` and `domain.CohortLedger` (~90 touchpoints, all with explicit
saves). A table must leave JPA completely in one step, otherwise JDBC writes and JPA's
persistence-context cache disagree inside one transaction.

- **S2b — off JPA, behaviour unchanged.** `Battalion`, `Army`, `BattalionCohort` become plain classes;
  `BattalionRepository`, `ArmyRepository`, `BattalionCohortRepository` become JDBC repositories with
  the same method names (`save` = insert or update by id, `delete`, `flush` = no-op). Any mutation
  that relied on dirty checking without an explicit save shows up as a failing test and gets an
  explicit save. Done when every test passes and no `@Entity` remains in `military.domain`.
- **S2c — pure model.** `military.rules.Forces` (immutable maps of units, formations and cohorts by
  id) with transition methods; `military.domain.MilitaryStore` loads `Forces` in three queries and
  persists `(before, after)` by diff in JDBC batches. Commands move one by one onto
  `apply(Forces, …) → (Forces, events)`: plain move, recruit, reinforce, disband, regroup, battle,
  attach/detach, withdrawal. Each is switched completely and its entity-mutation code deleted.
- **S2d — remove the mutable classes** once no command uses them.

**S2 status (2026-11-20, done).** S2b shipped on its own (JDBC repositories, behaviour unchanged).
S2c and S2d then switched every military command at once, since all of them already wrote through
JDBC: the services load `Forces`, compute the new `Forces` with its pure transitions (`raise`,
`update`, `reinforce`, `suffer`, `remove`, `form`, `withoutEmptyFormations`, `leavingSplitArmies`;
`Unit.movedTo`, `relocatedTo`, `inArmy`, `attachedTo`, `detached`) and hand both to
`MilitaryStore.persist`, which writes only the changed rows. The cohort invariant (a battalion's cohorts
add up to its manpower) is checked on every transition instead of after each write. Recruitment and
the starting forces persist against `Forces.empty()`, so they insert without loading the game's
forces. `Battalion`, `Army`, `BattalionCohort`, their repositories and `CohortLedger` are deleted; no
JPA entity is left in the military module. Iteration order equals the database's uuid order
(`Forces.ID_ORDER`), so results match the previous `ORDER BY id` reads.

Measured on the Grand Campaign: recruitment 18 statements (unchanged), plain move 25 (24 before: the
full load reads three tables), one computer round 1,988 (1,989). The S2 target "human move ≤ 10" is
not reached by S2 alone: the military part of a move is now four statements (three loads, one batched
write), and the other 21 are the command pipeline, the world and relation reads and the siege check.
Those shrink when their modules become pure (S3–S5) and when the computer phase keeps the whole
`GameState` in memory (S6), which this stage makes possible for the military slice.

**S3 status (2026-11-21, done).** Economy, research and sieges follow the same pattern as the
military: an immutable value per module in its `rules` package with pure transitions, and a JDBC
store that loads it and writes only new and changed rows in batches.

| Module | Value | Store | Tables |
|---|---|---|---|
| economy | `Ledger` (treasuries `Purse`, workshops `Building`) | `EconomyStore` | `nation_economy`, `building` |
| research | `Studies` (`Progress`, `Institution`, `Doctrine`) | `ResearchStore` | `nation_research`, `institution`, `nation_doctrine` |
| siege | `Strongholds` (`Fortress`, `Siege`) | `SiegeStore` | `fortification`, `siege` |

Every command of these modules (workshop build, upgrade and repair; institutions, technology and
doctrines; fortress works) and their round phases (income, construction, research, institutions,
sieges, fortifications) now run through them. Five round phases used to rely on JPA dirty checking
(an entity changed without an explicit save); they now write explicitly. A value may hold only part
of a game (one treasury, say): it is always compared with the value it was derived from. The siege
engine writes its pending changes before every call into another module and before each siege
round row, so no module ever reads stale fortress or siege rows. JPA is left only in `game` and
`identity`.

Removing JPA also removed its first-level cache, which had answered repeated reads of the same row
inside one transaction for free. The resulting re-reads were fixed at their source rather than by a
new cache:
- `EconomyApi.spend` returns the treasury after payment.
- `EconomyApi.treasuries(gameId)` reads every treasury at once for round-end checks.
- Treasury reads share the read scope of the computer's observation.
- The growth phase reads the besieged cities and every nation's technology once per round instead of
  once per city.

Measured on the Grand Campaign: one computer round of 8 turns 1,988 → 1,871 statements; an
observation 42 → 43; recruitment 18 → 19 (the treasury is no longer cached between the check and the
payment); plain move 25.

**S4 status (2026-11-21, done).** Diplomacy was already JDBC with pure rules (`Diplomacy`,
`PeaceTerms`, `WarScore`, `Victory`) and an immutable read model (`DiplomacyState.Picture`); its
writes were conditional SQL updates scattered through seven services. Its state now changes as one
value:
- `diplomacy.domain.Relations` holds alliances, wars, participants, active fronts, truces, tributes,
  peace proposals, calls to arms and conferences as their row records, with one pure transition per
  former write. The guarded ones return empty exactly where the conditional update matched no row
  (`activateAlliance`, `closeProposal`, `revise`, …).
- `DiplomacyStore` loads it and writes the difference per table in foreign-key order, updates before
  inserts (a superseded proposal frees the one-pending-proposal slot before the new one takes it).
- `DiplomacyDraft` is the per-command working copy. Its method names mirror the old repository writes,
  so the services kept their shape. A draft writes itself before any other module is called and before
  a fresh `Picture` is read. Drafts register with the transaction: recording an event first writes
  every pending draft (an event may name a war created in the same command), and a before-commit hook
  writes anything left, so no change can be lost.
- Append-only records (events, gifts, treaties, tribute payments, war score, conference revisions and
  decisions) stay direct inserts. The repositories' old write methods are deleted.

`DiplomacyRollbackIT` now injects its failures at the new write points (the store write that ends the
war; the tribute payment insert); its assertions are unchanged.

**S5 status (2026-11-21, done).** After S2–S4 every round phase already computes on values loaded at
its start and writes through a store; the remaining per-row work was removed:
- GROWTH writes all cities in one batched update (`WorldApi.changePopulations`) and all growth reports
  in one batched insert, instead of two statements per city (72 cities on the Grand Campaign).
- ECONOMY asks every upkeep source once for the whole game (`TreasuryService.upkeepByNation`) instead
  of once per nation.
- CONSTRUCTION, RESEARCH, INSTITUTIONS and FORTIFICATIONS load their rows once and write the changed
  ones in one batch (S3); SIEGES writes before each call into another module (S3); TRIBUTE and
  DIPLOMACY write through a draft only when something is due (S4).

The phases still run inside `RoundProcessor` in `RoundPhase.Step` order, in the end-turn transaction,
behind the `round_resolution` guard. Measured: one computer round of 8 turns 1,965 → 1,751 statements.
Each phase still reads the world snapshot on its own; S6 shares those reads.

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
