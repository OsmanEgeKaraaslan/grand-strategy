# Game Design

The structured gameplay rulebook. Detail lives in the specialised documents:
[ECONOMY.md](ECONOMY.md), [COMBAT_AND_SIEGE.md](COMBAT_AND_SIEGE.md),
[TURN_SYSTEM.md](TURN_SYSTEM.md). Open decisions: [OPEN_QUESTIONS.md](OPEN_QUESTIONS.md).

## Labels

| Label | Meaning |
|---|---|
| **APPROVED** | Explicitly specified by the product owner. Changing it needs product approval. |
| **PROPOSED** | Our suggested rule or number. Configurable. Implemented as default only when the phase requires it, and listed in OPEN_QUESTIONS until confirmed. |
| **OPEN** | Undecided. Must not be implemented until decided. |

---

## 1. Vision

A multiplayer, browser-based, **classic sequential turn-based** grand strategy
game in a fully fictional world. Europa Universalis-style irregular province map
and nation management; turn structure closer to a board game. Strategic depth comes
from four tensions:

1. **People are both workforce and manpower.** Every soldier recruited is a worker
   lost.
2. **Army composition vs terrain.** Infantry holds, cavalry flanks in the open,
   artillery hits hard and breaks fortresses but must be protected.
3. **How to take a city.** Starve it (slow, kills people, keeps buildings) or
   bombard it (fast, saves people, breaks buildings).
4. **Gold vs upkeep.** Armies and fortresses cost money every round.

## 2. World

### 2.1 Fictional setting — APPROVED

World, geography, nations, cultures and history are fictional. The first map
(PROPOSED working name **"The Sundered Reach"**) is a small two-to-four-nation map
for the vertical slice. Names are placeholders until a world bible exists.

### 2.2 Provinces — APPROVED

- Irregular polygons (not hexes), rendered as SVG paths.
- Each province: id, name, owner, controller, terrain, geometry, neighbours,
  one city (MVP), military presence.
- Provinces form an undirected graph; armies move between adjacent provinces.
- Map data is data-driven (map templates), reusable across many games.

### 2.3 Terrain — APPROVED (types), PROPOSED (data model)

- MVP terrain: `PLAINS`, `MOUNTAINS`. More later (forest, marsh, hills, desert).
- Terrain is a property of the province template; its effects are ruleset
  configuration keyed by terrain type.

### 2.4 Rivers — APPROVED (as edges)

- A river is an attribute of an adjacency: `crossing = NONE | RIVER`.
- Attacking across a RIVER edge gives the attacker −20% offence.
- Mountains and rivers are independent modifiers that may combine
  (PROPOSED: multiplicatively); the first map avoids combining them.
- Later: `STRAIT`, `BRIDGE`, `PASS` crossing types fit the same column.

### 2.5 Ownership vs control — APPROVED (decision Q-W1, 2026-10-17)

- **Owner:** the nation the province legally belongs to.
- **Controller:** the nation whose forces currently hold the city.
- Both exist as separate fields from the first game-state migration onward.
- Military occupation (capture, siege success) changes **only the controller**.
  Example: A owns X; B occupies X → owner A, controller B.
- Legal ownership changes **only** through diplomatic mechanisms, primarily peace
  treaties (Phase 9). There is no automatic owner transfer on conquest — not even
  as a temporary MVP shortcut.
- Until diplomacy exists, occupation is therefore permanent-until-retaken and
  never becomes annexation.
- **Occupied cities produce no income for either side (APPROVED, Q-W2, 2026-10-17).**
  Income resumes for the owner on liberation, or for a new owner once a treaty transfers
  ownership. Occupation alone never damages buildings or reduces population; neither side
  can recruit there (recruiting requires owning **and** controlling the city).

### 2.6 Cities — APPROVED (one per province)

City state: population, buildings (workshops, fortress, later academy/university/
barracks), siege status. City name may differ from province name.

### 2.7 Neutral provinces — OPEN Q-W4

Unowned ("independent") provinces would support territorial expansion, but
with ownership changing only through diplomacy there is no counterparty to sign
a treaty with. How unowned land becomes legally owned (claim/colonize action,
annexation rule, or none) is OPEN Q-W4. PROPOSED for MVP: the first map has
**no neutral provinces**; every province starts owned by a playable nation.

## 3. Nations and players

- A **player** is an authenticated account. A **game player** is that account's
  seat in one game. Each game player controls exactly one **nation** in that game
  (APPROVED intent; PROPOSED one-to-one).
- Nations are instantiated per game from nation templates on the map (name,
  colour, starting provinces).
- Start setup (Q-W3, accepted as provisional defaults 2026-10-17; per nation, MVP
  map): 4–5 provinces, capital ~12,000 people with fortress level 1 and one
  Tier 1 workshop, other cities 4,000–8,000, treasury 50 gold, starting army
  `3I 1C` at the capital. All values are map/ruleset data.

## 4. Turn structure — APPROVED

Classic sequential turns (full design: [TURN_SYSTEM.md](TURN_SYSTEM.md)):

1. Players act one at a time in seat order. Only the active player may issue
   gameplay commands.
2. Valid actions take effect immediately and are visible to others.
3. The active player explicitly ends their turn; the next seat becomes active.
4. After the last seat ends, **global round processing** runs once: sieges,
   attrition, income, upkeep, research, construction completions, (later)
   growth, tribute.
5. Next round begins with the first seat.

No turn deadlines and no automatic turn advancement in the MVP (APPROVED).
Turn order: **fixed seat order** assigned at game start (APPROVED for MVP, Q-T1).

### Actions available during a turn (PROPOSED list per phase)

| Action | Phase introduced |
|---|---|
| End turn | 3 |
| Build workshop / fortress, upgrade, repair | 4 (fortress: 7) |
| Recruit battalion | 5 |
| Move a selection of eligible battalions (one step) | 5 |
| Organise armies (create, rename, regroup battalions) | 5 |
| Disband battalion | 5 |
| Reinforce battalion | 6 |
| (Sieges start automatically on move) | 7 |
| Build academy / university / barracks, choose research | 8 |
| Diplomacy: declare war, propose alliance, call allies, offer/answer peace | 9 |

There is **no concede or surrender action** in the MVP (APPROVED, 2026-10-17).

## 5. Population — APPROVED

- Real integer population per city. No individual civilian entities, no abstract
  Pop units.
- Uses: economic production and military manpower.
- Recruiting reduces civilian population immediately; disbanding returns the
  surviving soldiers.
- Deterministic integer rounding everywhere (PROPOSED policy: floor of basis
  points — [ECONOMY.md §1.3](ECONOMY.md)).
- Growth: eventually via civilian tech and other mechanics; PROPOSED 0 in MVP
  (OPEN Q-E6).

## 6. Economy — APPROVED rates

| Workplace | Capacity | Income per 1,000 workers |
|---|---|---|
| Agriculture (anyone not in a workshop) | unlimited | 1 gold |
| Tier 1 workshop | 4,000 | 3 gold |
| Tier 2 workshop (needs civilian tech) | 4,000 | 5 gold |

- Workers fill Tier 2 → Tier 1 → agriculture; population loss removes
  agriculture → Tier 1 → Tier 2. Computed, not stored.
- Damaged workshops produce nothing; their workers farm.
- Gold is exact: stored as integer milligold (PROPOSED).
- Upkeep: once per global round for battalions and active fortresses.
- Income is calculated **before** upkeep in global round processing (APPROVED,
  Q-E1). Deficit behaviour: accepted MVP default (Q-E1b). Treasury never goes negative.
- All costs and coefficients are provisional balance values held in versioned
  rulesets (APPROVED principle).

Details and examples: [ECONOMY.md](ECONOMY.md).

## 7. Military

### 7.1 Unit roles — APPROVED

| Role | Character | Upkeep |
|---|---|---|
| Infantry | Cheap, frontline, good defence, protects artillery, stable | Low |
| Cavalry | Strong in open terrain, flanking, expensive to recruit | High |
| Artillery | Expensive, siege specialist, high damage, fragile without infantry, slow | High |

Unit names and equipment may change with technology; roles do not.

### 7.2 Battalion — APPROVED attributes

id, unit type, current manpower, max manpower (1,000 initially), origin city,
recruitment cost, upkeep, owner, army membership, relevant military technology.

- Partial casualties persist (1,000 → 650). No automatic regeneration.
- PROPOSED: "relevant military technology" is stored as `equipment_level`
  (the nation's military tech level at recruitment); upgrade rules OPEN Q-C7.
- PROPOSED: recruitment cost and upkeep are derived from the ruleset by unit
  type and level; the battalion stores `recruitment_cost_mg` (what was paid) for
  history, not for logic.

### 7.3 Armies — APPROVED role (organisational grouping)

- An army is a named, **organisational** group of battalions of one nation in
  one province. It carries no movement state of its own.
- Invariant (PROPOSED): every battalion of an army is in the army's province.
- Multiple armies of one nation may share a province. Creating, renaming and
  regrouping (moving battalions between armies in the same province) is free
  during the owner's turn and **never resets or transfers movement eligibility**.
- Empty armies are deleted automatically.

### 7.4 Movement — APPROVED: battalion-level eligibility (Q-M1, 2026-10-17)

- Movement eligibility is tracked **per battalion**, never per army.
- MVP limit (APPROVED): each battalion may move **one adjacent province per
  player turn**.
- A move command selects one or more eligible battalions located in the same
  province (they may come from different armies) and a destination adjacent
  province. Moving one battalion does not affect the eligibility of any other
  battalion, including others in the same army.
- A battalion is eligible when: it belongs to the acting nation, it was not
  recruited during the current turn (§7.5), and it has not already moved during
  the current turn. Both facts are stored on the battalion (turn numbers), so
  regrouping cannot be used to move twice.
- On arrival, moved battalions form a new army at the destination or join an
  existing own army there chosen by the player (PROPOSED). If a whole army's
  battalions move together, the army itself moves. (Phase 5 interim, Q-M6 still
  OPEN: battalions that move without the rest of their army arrive ungrouped and
  can be grouped with the regroup command.)
- A selection moves at the pace of its slowest battalion (irrelevant while all
  types move one province; matters once movement points exist).
- Later (OPEN): movement points (infantry 2, cavalry 3, artillery 1; mountains
  cost 2, river crossing +1), stored per battalion in the same model.
- Entering a province with hostile battalions → immediate battle; the moving
  selection is the attacking force.
- Entering a hostile-controlled city province with no hostile battalions →
  siege starts (Phase 7). In Phases 5–6 (accepted default, Q-M2; implemented in
  Phase 5): **simple occupation** — if the battalions are still there at the next
  round end, **control** passes to their nation. Ownership never changes (§2.5);
  civilians and buildings are untouched; the city pays nobody while occupied (Q-W2).
  The same rule returns control to an owner whose battalions alone stand in the
  province (liberation). Since Phase 7 a fortified province (intact fortress) needs a successful
  siege instead (Q-M7 resolved; COMBAT_AND_SIEGE §5.0).
- Movement into a besieged/contested province is allowed; leaving a siege
  withdraws from it.

### 7.5 Recruitment — APPROVED core and readiness rule

Reduces city population immediately; costs gold. Constraints (own and controlled
city, not besieged, residual population ≥ 1,000): [ECONOMY.md §8](ECONOMY.md).

**Readiness (APPROVED, Q-M3, 2026-10-17):** a newly recruited battalion
**cannot initiate movement or an offensive attack** during the player turn in
which it was recruited. It **does defend** its province immediately: if another
player attacks that province later in the same round, the new battalion is part
of the defending force like any other battalion present. Readiness restricts
initiative only, never defensive participation.

### 7.6 Disbanding — APPROVED

Only in friendly-controlled territory. Survivors return to the origin city if it
is still friendly-controlled, else to the city of the province where disbanded.
Dead soldiers never return.

**Implemented in Phase 6:** only the owning active player disbands; "friendly-controlled" means
controlled by the battalion's **own** nation (allies' territory does not count, Q-M5). The
battalion is removed and its upkeep stops.

**Origin-aware manpower (Phase 6 design):** because reinforcements can come from another city,
a battalion keeps its soldiers as **cohorts** — one per origin city, sizes always summing to its
manpower (`battalion_cohort`; no per-soldier rows). Example: raised in City A, loses 400, refilled
by 400 from City B → cohorts A 600, B 400. Battle losses are taken from the cohorts in proportion
to their size (floors first, remaining deaths to the largest fractional remainders, ties by origin
city id), so the total is preserved exactly. On disband, **each cohort** goes back to its own
origin city if the nation still controls it, otherwise to the local city where the battalion
disbands. This prevents "population teleportation" (refilling from B and disbanding to send B's
people to A for free). `battalion.origin_city_id` stays as the battalion's original recruitment
identity. A simpler single-origin design was rejected because it either teleports population or
forbids reinforcement outside the origin city.

### 7.7 Reinforcement — APPROVED principle; rules accepted for implementation 2026-10-19 (Q-R1)

Consumes population and gold. Implemented in Phase 6: own owned-and-controlled city, at most
500 men per battalion per turn, recruitment cost pro rata, not in arrears; a reinforced
battalion cannot move or attack in that turn but defends (PROVISIONAL). Details in
[COMBAT_AND_SIEGE.md §4](COMBAT_AND_SIEGE.md).

### 7.8 Upkeep — APPROVED principle, PROPOSED values

Infantry low, cavalry high, artillery high, active fortresses cost maintenance.
Values: [ECONOMY.md §4](ECONOMY.md).

## 8. Combat — APPROVED principles, PROPOSED model

Deterministic; composition, terrain, rivers, tech, doctrine, frontline protection
and flanking matter; partial casualties. APPROVED penalties: river −20%, attacking
into mountains −35%, reducible by doctrine. The PROPOSED 3-phase model with
protection ratio, flanking multiplier, exposure-weighted casualties and a break
threshold, with five worked examples: [COMBAT_AND_SIEGE.md](COMBAT_AND_SIEGE.md).

## 9. Sieges — APPROVED principles, PROPOSED formula

Multi-round; artillery speeds them, fortress levels slow them; ~5% civilian
attrition per round; starvation preserves buildings, artillery damages the
fortress and one workshop. Workshop repair = 30% cost, 1 round (APPROVED).
The original duration formula conflicts with the 6–8-round intent; PROPOSED
progress-point formula and state machine: [COMBAT_AND_SIEGE.md §5](COMBAT_AND_SIEGE.md).

## 10. Technology and progression — APPROVED structure, OPEN content

| Institution (building) | Generates | Improves |
|---|---|---|
| Military Academy | Military tech points | Weapons, unit effectiveness, eras |
| University | Civilian tech points | Workshops (unlocks Tier 2), construction, growth, economy |
| Barracks / Headquarters | Doctrine points | Effectiveness, morale (break threshold), terrain penalty reduction |

- Technology is nation-level (APPROVED). Not required for the earliest MVP.
- PROPOSED foundation: three linear **tracks** of a few levels each defined in the
  ruleset (`tech_definition` data: id, track, level, cost, effects). Each round,
  generated points accumulate into the track's current research. Effects are
  typed modifiers (`TECH_MOD_ATTACK_INFANTRY +10%`, `UNLOCK_WORKSHOP_TIER_2`,
  `REDUCE_TERRAIN_PENALTY_RIVER 25%`), consumed by the rules engine as inputs.
- A branching tree is a later extension of the same data model.
- PROPOSED: institution buildings generate a flat number of points per round
  (e.g. 10), optionally scaled by city population later. OPEN Q-X1.

Because Tier 2 workshops require civilian tech, **Tier 2 is unavailable in the
MVP** unless a ruleset flag grants it (useful for tests and the economy screen).

### 10.1 Phase 8 implementation (2026-10-19, ruleset v4) — all values PROVISIONAL (Q-X1)

- **Institutions** (`research` module, `rulesets/v4/research.json`): Military Academy → military
  points, University → civilian points, Barracks → doctrine points (unrelated to recruiting). One of
  each type per city; 60 gold, 2 rounds (Q-E8), 10 points per round, 1 gold upkeep per round. Only
  the active owner who also controls an unbesieged city, not in arrears, may build. An institution
  produces for its city's owner while ACTIVE and the owner controls the city (occupied: nobody,
  like Q-W2; besieged cities keep producing, like their income). No free institutions at start.
- **Points** are national pools, added once per global round end (step RESEARCH) and never per
  player turn; an institution completing at a round end produces from the next one.
- **Technology tracks** (levels bought in order with that track's points, never twice — the command
  names the next level): Military 1 → 2 (40 points, +10% damage for every unit type) → 3 (80 points,
  +20% in total); Civilian 1 → 2 (40 points, unlocks the Tier 1 → 2 workshop upgrade) → 3 (80 points,
  +0.25% population growth per round).
- **Military technology** multiplies each unit type's damage in the battle engine
  (`D = base × (1 + flank) × terrain × techMod`); it never adds soldiers. It is **national and
  immediate**: every existing battalion of the nation uses the current level in its next battle.
  `battalion.equipment_level` stays unused and reserved for a future per-battalion equipment
  system, so there is a single source of military strength (Q-C7 decided provisionally this way).
- **Doctrines** (doctrine points; PROVISIONAL one choice in tier 1, permanent; the data model allows
  more tiers): Mountain Warfare (the mountain attack penalty −35% is halved to −17.5%), River
  Engineering (−20% halved to −10%), Siege Engineering (+50% on the artillery part of siege progress),
  Military Logistics (−20% battalion upkeep). Each changes the real calculation in the battle
  engine, the siege engine or the upkeep settlement through `military.api.MilitaryModifiers`; battle
  reports record both sides' modifiers at the time and are never recalculated.
- **Effects timing:** a technology or doctrine bought during a turn applies to every later battle,
  and to the next round end's siege progress and upkeep; earlier battles and settled rounds are
  never changed.

## 11. Diplomacy — APPROVED structure, PROVISIONAL rules (Phase 9, ruleset v5; full rules in [DIPLOMACY.md](DIPLOMACY.md))

### 11.1 Relations

- **War** between two coalitions (attacker leader + allies vs defender leader +
  allies).
- **Defensive pact:** members may be called when a member is *attacked*; does not
  support offensive wars.
- **Normal alliance:** members may be called into offensive or defensive wars.
- Calls to arms: PROPOSED earlier as a vote of the callee (declining breaks the alliance).
  **Phase 9 (PROVISIONAL Q-D6):** the defender's allies join a defensive war automatically;
  offensive calls are not implemented.
- Rulesets v1–v4 (pre-diplomacy): every pair of nations is permanently at war. From v5 every pair
  starts **at peace**; war needs a declaration, and only war allows entering neutral territory,
  battles, occupation and sieges (DIPLOMACY.md §2).

### 11.2 War score — PROVISIONAL Q-D2 (Phase 9)

0–100 per side from battles won (weighted by enemy casualties, no points for tiny battles),
sieges won and the share of enemy population occupied; battle and siege points are recorded once
per event, occupation is read from the current world (DIPLOMACY.md §4). The capital is not
weighted separately. War score guides negotiation; it never forces a peace.

### 11.3 Peace terms — APPROVED types

| Term | Rule | Status |
|---|---|---|
| Territorial annexation | Transfer ownership/control of selected eligible border provinces | APPROVED type; eligibility OPEN |
| Immediate reparations | Transfer a specified amount of gold at once | APPROVED; capped by treasury (PROPOSED) |
| Tribute | Defeated pays 10% of income per round for 10 global rounds | APPROVED; "income" = gross income, floor to mg (PROPOSED) |
| Population transfer | Move civilians from a defeated city to victor cities | APPROVED type; **1,000–2,000 people** is a PROPOSED conversion of "1–2 Pop", needs approval (Q-D4) |

Phase 9 (PROVISIONAL): eligibility = any province the giver legally owns except starting capitals
(Q-D8); reparations are integer milligold covered by the treasury; tribute is paid once per round
from the treasury after settlement, shortfalls forgiven (Q-D5); population transfers 1,000–2,000
people per term (Q-D4). Term costs in war score remain OPEN; every peace is voluntary (Q-D3).

## 12. Ending wars and games — APPROVED constraints, OPEN details

APPROVED (2026-10-17):
- **No concede or surrender feature in the MVP.**
- Wars are normally concluded through **peace agreements** (Phase 9).
- Losing a battle, or having every province occupied, is **not** a peace treaty
  and does not transfer ownership.
- Military occupation and legal annexation remain distinct (§2.5).
- No automatic elimination mechanic replaces surrender. A nation that controls
  no cities still exists, still owns its legal territory and still takes turns.
- No automatic timeout (do not invent one).

Consequence (documented honestly in the roadmap): the early playable prototype
(end of Phase 5) has **no war-ending or game-ending system**. Players can build,
recruit, fight and occupy indefinitely; a game is closed only by an operator
marking it `ABANDONED` (an administrative action, not a gameplay surrender).

PROVISIONAL (Phase 9, ruleset v5):
- Q-T2 — the game ends after round 30; standings by legally owned provinces and their people;
  a tie for first is a draw (DIPLOMACY.md §8). Older rulesets never end.

OPEN:
- Q-T3 — handling of stalled games without timeouts or concession.
- Q-D3 — war-score costs of terms and enforced peace (Phase 9 implements voluntary acceptance only).

## 13. Visibility — APPROVED for MVP (Q-T4, 2026-10-17)

No fog of war in the initial MVP: all players see all battalions and cities. Fog of
war requires per-viewer filtering of every state read; the API is designed so it
can be added (all state reads go through a viewer-aware query service).

## 14. Identified contradictions and risks in the original specification

| # | Issue | Resolution path |
|---|---|---|
| 1 | Siege formula `ceil(3F − S)` gives 3 rounds (level 1) and 9 rounds (level 3) without artillery, contradicting the 6–8 round intent | PROPOSED progress-point formula; decide Q-S1 |
| 2 | Upkeep "at the beginning of a turn" vs approved round-end processing | Moved to round end; order income→upkeep PROPOSED; decide Q-E1 |
| 3 | "Workers occupy highest first" and "recruitment removes agriculture first" look like two rules | They are one rule; computing employment from totals satisfies both |
| 4 | Ownership vs control with no peace mechanism before Phase 9 | Decided Q-W1: occupation changes control only, always; Q-W2: occupied cities yield no income to anyone |
| 5 | Tier 2 workshops require civilian tech, which comes after MVP | Tier 2 gated; test flag |
| 6 | "1–2 Pop" population transfer under numeric population | 1,000–2,000 PROPOSED; decide Q-D4 |
| 7 | "Friendly-controlled" undefined (allies?) | Own nation only until alliances (Q-M5) |
| 8 | Battalion "relevant military technology" vs nation-level tech | `equipment_level` snapshot PROPOSED (Q-C7) |
| 9 | No turn deadlines and no concede → games can stall forever | Operator `ABANDONED` action only; player-facing solution OPEN (Q-T3) |
| 10 | Siege "artillery vs no artillery" classification with artillery arriving/leaving mid-siege | Classify at fall (Q-S2) |
| 11 | Besieged city defenders (garrison) unspecified | None in MVP (Q-S9) |
| 12 | 5% attrition "approximately" | floor(5%) PROPOSED (Q-E5) |
| 13 | Damaged fortress effect and repair cost unspecified | Effective level 0, 30% PROPOSED (Q-S4, Q-S5) |
| 14 | Sequential turns favour later seats for reaction and earlier seats for initiative | Fixed vs rotating order (Q-T1) |
| 15 | Spring Boot 4 is new (Jackson 3, Hibernate 7) — some libraries may lag | Pin versions in Phase 1; verify Testcontainers/Flyway/springdoc compatibility first |
| 16 | Supabase connection pooler (transaction mode) breaks session features and prepared statements | Use direct or session-mode connection for the backend |

## 15. Phase 10 — world expansion and coalitions (ruleset v6) — PROVISIONAL

- **Grand Campaign map** (`grand-campaign`, version 1): 72 irregular provinces on a fictional,
  Europe-inspired continent, 10 fictional factions, rivers and mountains; generated by
  `tools/mapgen/grand-campaign.mjs` from original data (no external map sources). Provinces are
  polygons; the city is a separate point inside. Two provinces are neighbours exactly when they share
  at least `min_border_length` (16 map units) of land border — a corner touch is not adjacency, and a
  province may have any number of neighbours (here 1–7). The server validates adjacency against the
  geometry at startup.
- **Factions and tiers** (starting values per tier in the ruleset, ECONOMY §10b):

  | Faction | Tier | Provinces | People | Playable |
  |---|---|---|---|---|
  | Castavar, Nordmark, Vostrava, Dravonia | POWER | 7 each | 46,000 each | yes |
  | Aurelian Empire | MAJOR | 12 | 80,000 | no |
  | Serenna, Karpathia | REGIONAL | 9 each | 49,000 each | no |
  | Frisia, Helvaria, Aestia | MINOR | 4–5 | 21,000–24,000 | no |

  The four powers never border each other. The Sundered Reach (two nations) stays available.
- **Lobbies** for 2–4 human players (up to the map's maximum); every nation not chosen by a player is
  controlled by the game as a passive placeholder (DIPLOMACY §11) — not an AI.
- **Turns** stay sequential in seat order; nations without a player and eliminated players are
  skipped.
- **Coalitions**: gold gifts, calls to arms, coalition battles, attached battalions, peace conferences
  with revisions and approvals, separate peace, elimination and domination — rules in
  DIPLOMACY §10–16 and COMBAT_AND_SIEGE §3a.

## 16. Phase 11 — computer opponents (ruleset v7) — PROVISIONAL

Every nation no human chooses is played by the computer, by exactly the rules and information a
human has (no fog of war exists; no bonus of any kind). Computers develop, arm, research, ally,
fight, besiege, negotiate and can win by score or domination. One difficulty; personalities
(expansionist, economic, defensive, diplomatic) only shift weights. Details: [AI.md](AI.md).
