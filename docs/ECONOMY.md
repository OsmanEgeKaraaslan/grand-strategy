# Economy

Population, employment, income, upkeep, construction costs, and the exact
arithmetic used for money and people.

Labels: **APPROVED** = specified by the product owner. **PROPOSED** = our default,
configurable, needs confirmation. **OPEN** = undecided; see
[OPEN_QUESTIONS.md](OPEN_QUESTIONS.md).

---

## 0. Implementation status (Phase 4, 2026-10-18)

Implemented in the `economy` module, exactly as specified in §1–§9:

| Rule | Implementation |
|---|---|
| Money | `long` milligold everywhere (`…Mg` in the API), `Math.*Exact` arithmetic, DB `bigint` with `treasury_mg >= 0` |
| Ruleset | `backend/src/main/resources/rulesets/v1/economy.json`, selected by `game.ruleset_version` (new games: `strategy.ruleset.current-version`). Values: agriculture 1 mg/worker; workshop capacity 4,000; T1 3 mg/worker, cost 40 gold, 1 round, buildable; T2 5 mg/worker, not buildable (Tier 2 "new equivalent" cost 100 gold kept for future repairs); max 6 workshops per city; start 50 gold + 1 active T1 workshop in each capital |
| Employment (§2.2) | `economy.rules.CityEconomy.production` — tiers filled by productivity, the rest farm; only ACTIVE workshops have capacity; population is an input, so Phase 5 recruitment only changes the city row |
| Occupation (Q-W2) | `CityEconomy.income`: owner ≠ controller → collectible income 0 for both; the API returns both `potentialIncome` and `collectibleIncomeMg` plus `incomeStatus` |
| Round end (§6) | `RoundPhase.Step.ECONOMY`: per nation in id order — collectible income → upkeep (all `UpkeepSource`s; battalions from Phase 5) → `Treasury.settle` (floor 0, deficit, arrears) → `economy_round_report`. `Step.CONSTRUCTION` afterwards: projects with `completes_after_round <= round` become ACTIVE |
| Deficit (§7) | `Treasury.settle`; `IN_ARREARS` blocks construction; cleared by the next round without deficit |
| Construction (§5) | `Construction.blockers` (shared by UI and command): turn, legal owner, not occupied, tier buildable, ≤ 6 workshops incl. projects, not in arrears, affordable. Cost deducted in the same transaction as the project row; completes at the end of round N+d−1 (Q-E8) |

Not in Phase 4: Tier 2 construction/upgrades, repairs, population growth, recruitment.

### Phase 5 additions (implemented 2026-10-18)

| Rule | Implementation |
|---|---|
| Upkeep (§4) | `economy.api.UpkeepSource` hook; the military module's source sums `military.rules.Upkeep.of` (proportional, floored) over **existing** battalions, so destroyed battalions cost nothing. Charged inside the same `ECONOMY` round phase: income → upkeep → settlement → arrears. The `round_resolution` guard makes a second charge for the same round impossible. Active fortresses (Phase 7) will be a second source |
| Recruitment (§8) | `military.rules.Recruitment.blockers` (shared by UI and command): your turn, legal owner, controls the city, not in arrears, `population − 1,000 ≥ 1,000`, affordable. Gold (`EconomyApi.spend`), exactly 1,000 civilians (`WorldApi.changePopulation`) and the battalion row are written in one command transaction; the economy recalculates from the new population with no other change |
| Ruleset | `rulesets/v1|v2/military.json`: recruit 10 / 25 / 40 gold, upkeep 0.5 / 1.5 / 2 gold per full battalion (infantry / cavalry / artillery). All PROVISIONAL balance (Q-E7) |
| Starting forces (Q-W3) | Ruleset **v2** (new games): 3 infantry + 1 cavalry per nation in its capital, raised **from the capital's 12,000 civilians** (→ 8,000), so no population is created. Ruleset **v1** has none; games created before Phase 5 keep v1 and are never rewritten |

Worked example (the Phase 5 acceptance case): a 12,000-person city with one Tier 1 workshop
earns 4,000 × 3 + 8,000 × 1 = 20 gold. Recruiting one infantry battalion costs 10 gold and
1,000 civilians: 11,000 people → 4,000 × 3 + 7,000 × 1 = **19 gold**, upkeep +0.5 gold/round.
In a v2 game the capital already holds 8,000 after the starting forces: 4,000 × 3 + 4,000 = 16
gold, and the nation pays 3 × 0.5 + 1.5 = 3 gold upkeep per round.

## 1. Units and arithmetic

### 1.1 Population — APPROVED (representation), PROPOSED (type)

- Population is a non-negative integer count of persons per city (APPROVED).
- No entity per civilian, no abstract "Pop" units (APPROVED).
- Storage: `INTEGER NOT NULL CHECK (population >= 0)`. Java value object
  `Population(int persons)` with checked arithmetic (`Math.addExact` etc.) (PROPOSED).

### 1.2 Gold — PROPOSED

Gold is stored as an integer number of **milligold** (1 gold = 1,000 mg).

Why milligold is sufficient and exact:

| Source | Rate per 1,000 people | Rate per person |
|---|---|---|
| Agriculture | 1 gold | **1 mg** |
| Tier 1 workshop | 3 gold | **3 mg** |
| Tier 2 workshop | 5 gold | **5 mg** |

Every approved income rate is an integer number of milligold *per person*, so any
population — 250, 1,350, 11,999 — produces an exact integer income with **no
rounding at all**. 250 farmers produce exactly 0.250 gold; 1,350 farmers produce
exactly 1.350 gold. Nothing disappears.

- DB: `BIGINT` (`treasury_mg`, `cost_mg`, …). Java: `record Gold(long mg)` with
  `plus`, `minus`, `times(long)`, `percentFloor(int basisPoints)`, using
  `Math.*Exact` to fail loudly on overflow.
- API: integer fields suffixed `Mg` (e.g. `treasuryMg: 36500`). Integers are
  safe in JavaScript up to 2^53. The UI formats `36500` as `36.5`.
- Rejected alternatives: `double` (non-deterministic rounding drift),
  `BigDecimal` everywhere (exact but heavier and easy to misuse with scales),
  whole-gold integers (would lose 0.25 gold from 250 people — violates the spec).

### 1.3 Rounding policy — PROPOSED

There is exactly one rounding function family, used everywhere:

| Operation | Rule | Code |
|---|---|---|
| Income from population | exact, no rounding | integer multiply |
| Percentage of gold (tribute, repair cost, refunds) | `floor(amount_mg × bps / 10 000)` | `Gold.percentFloor(bps)` |
| Percentage of population (siege attrition, growth, transfers) | `floor(persons × bps / 10 000)` | `Population.percentFloor(bps)` |
| Proportional manpower distribution across battalions | floor per battalion, remainder by largest remainder, ties by battalion id ascending | `Apportion.largestRemainder` |
| Combat intermediate values | `BigDecimal`, `MathContext.DECIMAL64` (16 significant digits, HALF_EVEN); final men lost floored | see COMBAT_AND_SIEGE |

Percentages are expressed in **basis points** (1% = 100 bps) in configuration so
no fractional percentage ever needs a floating type.

Floor (toward zero for non-negative values) is chosen because it is simple, never
creates people or gold from nothing, and is trivially reproducible in tests.
Consequence: very small cities lose nothing to 5% attrition (`floor(19 × 0.05) = 0`).
This is accepted as PROPOSED; see OPEN Q-E5.

---

## 2. Employment — APPROVED rules, PROPOSED algorithm

### 2.1 Rules (APPROVED)

- Every civilian works: in a workshop if a slot is available, otherwise in agriculture.
- Workshop capacity is 4,000 workers, for both tiers.
- Each workshop is a separate building instance.
- Workers fill the highest-productivity workplaces first: Tier 2 → Tier 1 → agriculture.
- When population falls (recruitment, attrition), agriculture loses workers first,
  then Tier 1, then Tier 2.
- Damaged workshops cannot produce; their former workers contribute only
  agricultural income until repaired.
- Employment assignments should not be persisted; compute them.

### 2.2 Algorithm (PROPOSED)

Employment is a pure function of the city, recomputed whenever needed:

```
cap2 = 4000 × count(workshops where tier = 2 and status = ACTIVE)
cap1 = 4000 × count(workshops where tier = 1 and status = ACTIVE)

employed2 = min(P, cap2)
employed1 = min(P − employed2, cap1)
farmers   = P − employed2 − employed1

income_mg = 5 × employed2 + 3 × employed1 + 1 × farmers
```

Observations that make this robust:

- The "fill highest first" rule and the "remove agriculture first" rule are the
  **same rule** seen from two directions. Because employment is recomputed from
  total population, recruitment automatically removes farmers first, then Tier 1,
  then Tier 2 — no extra code, no stored assignments, no drift.
- Damaged workshops simply contribute zero capacity; their former workers fall
  through to agriculture.
- Workshops `UNDER_CONSTRUCTION`, `UNDER_REPAIR`, `UPGRADING` (PROPOSED: upgrading
  workshops keep producing at their old tier until the upgrade completes; see
  OPEN Q-E3) are handled by the same status filter.
- Workshop capacity per tier is ruleset configuration (`workshop.capacity = 4000`).

### 2.3 Worked examples

**E1 — approved example.** P = 12,000, three Tier 1 workshops.
cap1 = 12,000 → employed1 = 12,000, farmers = 0.
Income = 3 × 12,000 = 36,000 mg = **36 gold**. ✔

**E2 — approved example.** Same city, all three upgraded to Tier 2.
Income = 5 × 12,000 = 60,000 mg = **60 gold**. ✔

**E3 — approved example.** P = 12,000, one Tier 2, one Tier 1.
employed2 = 4,000, employed1 = 4,000, farmers = 4,000.
Income = 20,000 + 12,000 + 4,000 = 36,000 mg = **36 gold**. ✔
Recruit 1,000 soldiers → P = 11,000 → farmers = 3,000 → **35 gold**. ✔

**E4 — fractional population.** P = 1,350, no workshops → 1,350 mg = **1.350 gold**.
P = 250 → **0.250 gold**.

**E5 — understaffed workshops.** P = 5,500, one Tier 2, one Tier 1.
employed2 = 4,000, employed1 = 1,500, farmers = 0.
Income = 20,000 + 4,500 = 24,500 mg = **24.5 gold**.

**E6 — damaged workshop.** P = 12,000, three Tier 1, one DAMAGED.
cap1 = 8,000 → employed1 = 8,000, farmers = 4,000.
Income = 24,000 + 4,000 = **28 gold** (loss of 8 gold until repaired).

**E7 — recruitment eats into workshops.** P = 8,000, two Tier 1. Recruit 1,000.
Before: employed1 = 8,000 → 24 gold. After: P = 7,000, employed1 = 7,000 → 21 gold.
(There are no farmers, so the Tier 1 workers are next — consistent with the rule.)

---

## 3. Income — APPROVED rates, PROPOSED aggregation

- Nation income per round = sum of `income_mg` of every city the nation **owns and
  controls**.
- **Occupied cities yield nothing (APPROVED, Q-W2, 2026-10-17):** if a province's legal
  owner and military controller are different nations, its city produces **zero collectible
  gold for both**. Examples: owner A / controller A → A collects; owner A / controller B →
  nobody collects; A liberates it (controller A again) → A collects again; a treaty makes B
  the owner while B controls it → B collects. The employment calculation is unchanged; the
  city's income is simply not credited to any treasury. Occupation by itself never changes
  ownership, damages buildings or reduces population. Enforced by the backend economy engine
  (Phase 4), not by the UI.
- Income is computed during global round processing, after siege attrition of that
  round (see §6), from the population at that moment.
- PROPOSED: a besieged city still produces income for its owner (simplest; no rule
  says otherwise). OPEN Q-S6 asks whether blockade should cut income.

---

## 4. Upkeep — APPROVED principle, PROPOSED values

APPROVED: military units and active fortresses cost gold every global round;
infantry is relatively cheap, cavalry and artillery are expensive; upkeep is
deducted once per global round.

PROPOSED values (all configurable, per full 1,000-man battalion):

| Item | Recruitment cost | Upkeep per round |
|---|---|---|
| Infantry battalion | 10 gold | 0.500 gold |
| Cavalry battalion | 25 gold | 1.500 gold |
| Artillery battalion | 40 gold | 2.000 gold |
| Fortress (ACTIVE) | see §5 | 1.000 gold × fortress level |
| Fortress (DAMAGED) | — | 0 (PROPOSED: no upkeep while not functional) |

PROPOSED: battalion upkeep scales with current manpower:
`upkeep_mg = floor(base_upkeep_mg × manpower / max_manpower)`.
A 650-man infantry battalion costs `floor(500 × 650 / 1000) = 325 mg`.
Trade-off: proportional upkeep is fair and avoids punishing damaged units; flat
upkeep would encourage reinforcing or merging. Either is easy to switch.

Scale sanity check: a 12,000-person city with three Tier 1 workshops (36 gold)
supports ~72 infantry battalions or ~18 artillery battalions of upkeep — but only
has the manpower for a fraction of that, so manpower, not gold, is the binding
military constraint early. This is intentional but unbalanced in detail; real
balance comes from playtesting.

---

## 5. Construction, upgrades and repairs

| Project | Cost | Duration | Status |
|---|---|---|---|
| Build Tier 1 workshop | 40 gold | 1 round | PROPOSED |
| Upgrade Tier 1 → Tier 2 (requires civilian tech) | 60 gold | 1 round | PROPOSED |
| "New equivalent" cost of a Tier 2 workshop (for repair) | 100 gold (= 40 + 60) | — | PROPOSED |
| Repair damaged workshop | **30% of new equivalent cost** | **1 round** | **APPROVED** rule; amounts follow from proposed costs: T1 = 12 gold, T2 = 30 gold |
| Build / raise fortress to level n | 50 gold × n | 2 rounds | PROPOSED |
| Repair damaged fortress | PROPOSED 30% of the cost of building its current level (level 2 → 30 gold) | 1 round | **OPEN** (cost not finalized) |

PROPOSED timing rule ("duration d"): a project ordered in round N becomes ACTIVE
during the round-end processing of round N + d − 1, **after** income is computed.
It therefore first produces income in round N + d. A 1-round workshop ordered in
round 3 produces nothing in round 3 and full income at the end of round 4.
Rationale: ordering a workshop on your turn never pays off instantly; every player
in the round faces the same delay regardless of seat order.

PROPOSED: maximum 6 workshops per city (config). Beyond `ceil(P / 4000)` workshops
add no income, so the cap is mainly a UI/abuse guard. OPEN Q-E2.

Costs are deducted immediately when the order is accepted. PROPOSED: cancelling a
project refunds 0% (simplest; refunds are OPEN Q-E4).

**Phase 8 implementation (2026-10-19, ruleset v4):**

- **Tier 1 → Tier 2 upgrade** (`POST …/cities/{cityId}/workshops/{buildingId}/upgrade`, `economy.json`
  `workshopUpgrade`): 60 gold, 1 round, needs civilian technology level 2; only an ACTIVE Tier 1
  workshop without another project, by the active owner who controls an unbesieged city, not in
  arrears. The same building is upgraded (identity and slot kept, so the 6-workshop limit is
  unaffected); it keeps producing at Tier 1 until the upgrade completes at the end of the order
  round (Q-E3 default, Q-E8 timing), so Tier 2 income never applies retroactively. If an artillery
  capture damages a workshop that is being upgraded, the upgrade is lost (paid, not refunded) and
  the workshop is DAMAGED at Tier 1 (PROVISIONAL). Tier 2 workshops are not built directly; the
  existing 100 gold "new equivalent" still prices their repair (30 gold).
- **Tier 2 employment** uses the existing calculator: Tier 2 fills first, then Tier 1, then farming.
  12,000 people with one Tier 2 and one Tier 1 workshop: 4,000 + 4,000 + 4,000 workers → 20 + 12 + 4 =
  **36 gold**.
- **Institutions** are paid from the same treasury (60 gold each) and cost 1 gold upkeep per round
  through the existing `UpkeepSource` settlement.

**Phase 7 implementation (2026-10-19, ruleset v3):**

- **Workshop repair** (`POST …/cities/{cityId}/workshops/{buildingId}/repair`): only a DAMAGED
  workshop, by the active player whose nation owns **and** controls the city, not besieged, not in
  arrears. Cost `ceil(buildCost × 3000 / 10,000)` = 12 gold for Tier 1 (`economy.json`
  `workshopRepair`), paid at once; status `UNDER_REPAIR` (still no capacity) and ACTIVE at the end of
  the order round (duration 1, Q-E8: after that round's income, producing from the next round).
  Rulesets v1/v2 have no repair section; their games can never have damaged workshops.
- **Damage:** an artillery siege capture marks one ACTIVE workshop DAMAGED (COMBAT_AND_SIEGE §5.0).
  The building record stays; its workers go back to farming through the normal employment
  calculation; nobody dies from the damage itself.
- **Fortresses** (`POST …/cities/{cityId}/fortress {targetLevel}` and `…/fortress/repair`): 50 gold
  × n to reach level n, 2 rounds; repair 30% of the current level's cost, 1 round (`siege.json`).
  Same owner-and-controller, not-besieged, not-in-arrears, active-player rules; one project per
  fortress at a time; `targetLevel` must be the next level (a stale order is rejected).
  Fortress projects complete in round step `FORTIFICATIONS` (after income and after the round's
  sieges), so a new or repaired fortress resists from the next round end.
- **Upkeep:** an ACTIVE fortress costs its owner 1 gold × level per round end while the owner
  also controls the city (an occupied city's fortress costs nobody — PROVISIONAL); DAMAGED and
  under-construction fortresses cost nothing. Charged through the existing `UpkeepSource`
  settlement (income → upkeep → settlement → arrears), once per round.
- **Construction blocked while besieged:** Tier 1 workshop orders get the blocker `BESIEGED`.
- **Siege attrition** reduces population at round step `SIEGES`, before income: the round's income
  already uses the reduced population and the post-siege controller (an occupied city collects
  nothing, Q-W2). Settled income of earlier rounds is never changed.

---

## 6. Order of income and upkeep — APPROVED (Q-E1, 2026-10-17)

The original design said "upkeep at the beginning of a turn". The approved
sequential model has a shared round-end phase, so upkeep moves there. Proposed
round-end economic order (full list in [TURN_SYSTEM.md](TURN_SYSTEM.md) §5):

1. Siege progression and attrition (population changes first).
2. **Income** computed for every nation from current population.
3. **Upkeep** computed for every nation (armies + active fortresses).
4. **Settlement:** `treasury := treasury + income − upkeep`.
5. Deficit rule if the result would be negative (§7).
6. Construction/repair completions.

Income-before-upkeep means a nation can pay this round's upkeep from this round's
income. The alternative (upkeep first, from the existing treasury) is harsher on
new players and changes nothing else. **Decided: income before upkeep (Q-E1).**

---

## 7. Treasury floor and deficits — accepted MVP default (Q-E1b, 2026-10-17)

The spec forbids accidental negative resources. Two layers:

1. **Action-time validation (PROPOSED, firm):** any player action whose cost
   exceeds the current treasury is rejected (`INSUFFICIENT_GOLD`). Actions never
   make the treasury negative.
2. **Round-end deficit (PROPOSED MVP rule):** if `treasury + income − upkeep < 0`:
   - treasury is set to 0;
   - the unpaid amount is recorded in the round report (`deficitMg`);
   - the nation is `IN_ARREARS` for the following round: it cannot recruit,
     reinforce, construct, upgrade or repair (it can still move, fight, disband).
   - arrears clears automatically at the next round end that settles without deficit.

Stronger consequences (morale penalty, desertion/auto-disband, debt with interest)
are OPEN (Q-E1b). The MVP rule is deliberately mild, deterministic and testable,
and disbanding is always available to the player as a remedy.

---

## 8. Recruitment effects — APPROVED core, PROPOSED details

APPROVED:
- Recruiting removes soldiers from the city's civilian population immediately.
- Economic impact follows the employment algorithm (agriculture first).

PROPOSED:
- A full battalion requires 1,000 persons and the recruitment cost (§4).
- Recruitment allowed only in a city the nation **owns and controls**, that is not
  under siege, and whose population after recruitment is ≥ 1,000 (configurable
  `recruitment.minResidualPopulation`).
- The new battalion is placed into a chosen army in that province (or a new army)
  (Phase 5 implementation: it starts in no army; the player groups it with the regroup
  command — see Q-M6) and **cannot initiate movement or an attack during the turn it was recruited**,
  but **defends its province immediately** (APPROVED, Q-M3; prevents recruit-and-strike
  within one turn without weakening defence).

Example: P = 12,000 (E3 layout). Recruit 2 infantry → P = 10,000,
income 36 → 34 gold, treasury −20 gold, upkeep +1 gold/round.

---

## 8a. Reinforcement effects — implemented in Phase 6

- Replacements come from the city of the province where the battalion stands (owned and
  controlled by its nation) and leave the civilian population immediately; the city must keep
  1,000 civilians (PROPOSED, same as recruitment §8). Employment recalculates automatically.
- Cost: `ceil(recruitCostMg × soldiers / battalionManpower)` milligold (PROPOSED rounding up, so
  splitting never pays; exact with the current costs). 350 infantry replacements: 3,500 mg.
- Upkeep (§4) is proportional to manpower, so a refilled battalion costs more from the next round
  end: a 650-man infantry battalion costs 0.325 gold, after +350 it costs 0.5 gold.
- Blocked while `IN_ARREARS` (§7).

## 9. Disbanding effects — APPROVED

- Allowed only in friendly-controlled territory (PROPOSED: "friendly" = controlled
  by the battalion's own nation until alliances exist; accepted default Q-M5).
- Exactly the surviving manpower returns to civilian population. Dead soldiers
  never return.
- Destination: origin city if it is still controlled by the battalion's nation;
  otherwise the city of the province where the battalion is disbanded.
- Employment and income adjust automatically (recomputed).

Example: battalion recruited in Varn (1,000), 650 survive, disbanded in Ostra while
Varn is enemy-occupied → Ostra population +650.

Phase 6 implementation: the destination rule is applied **per origin cohort** (GAME_DESIGN
§7.6). A battalion of 750 with 500 from City A and 250 from City B returns 500 to A and 250 to B;
if B is no longer controlled by the nation, those 250 go to the local city. Its upkeep is no
longer charged from the next round end.

PROPOSED: no gold refund on disband.

---

## 10. Population growth — PROVISIONAL since Phase 8 (Q-E6)

APPROVED: growth should eventually exist via civilian technology and other mechanics.

PROPOSED for MVP: **no growth** (0 bps). Rationale: keeps early balancing
transparent; sieges and recruitment are the only population changes.

PROPOSED for later: `growth = floor(P × growth_bps / 10 000)` per round, base
growth from ruleset (e.g. 30 bps = 0.3%), increased by civilian technology,
suppressed in besieged cities. Applied after income (end of round), so growth
first pays off next round. OPEN Q-E6.

**Phase 8 implementation (2026-10-19, ruleset v4, PROVISIONAL):** at round step `GROWTH` (after
income and research), every city whose owner also controls it and which is not besieged gains
`floor(P × (50 + civilianBonus) / 10,000)` people — 0.5% per round, +0.25% at civilian level 3.
Examples: 12,000 → +60; 8,000 → +40; 7,650 → +38 (floored); under 200 people → +0. Besieged cities
lose 5% to attrition instead and never also grow; occupied cities do not grow. Arithmetic in `long`;
a result beyond the city's integer range fails the round instead of wrapping. Growth is civilian
only — battalions are never refilled by it — and the new people first work in the next round.
Each city's growth is recorded in `population_growth` (primary key per city and round). Games
before v4 have no growth.

## 10a. Reparations and tribute — PROVISIONAL since Phase 9 (Q-D5)

Rules in [DIPLOMACY.md](DIPLOMACY.md) §5–6; the economy only moves gold.

- **Reparations** are integer milligold, paid once when a treaty is accepted: exactly the amount
  leaves the payer's treasury and arrives in the receiver's (`EconomyApi.transfer`). The treaty
  is refused unless the payer's treasury covers every reparations term at that moment.
- **Tribute** is charged in round step TRIBUTE, after the settlement of §6–7:
  `due = floor(grossIncome × rateBps / 10 000)` (v5: 1 000 bps), floored to the milligold;
  `paid = min(due, treasury after settlement)`. The difference is forgiven, never debt, and does
  not cause arrears (arrears come only from §7). Gold is never created: `Δpayer = −paid = −Δreceiver`.
  Example: gross income 38 009 mg → due 3 800 mg; with 1 200 mg left after upkeep the payer pays
  1 200 mg and owes nothing more.
- Occupied cities still pay nobody (Q-W2); a ceded city pays its new owner from the next settlement.

---

## 11. Invariants (enforced by tests)

- `population ≥ 0`, `treasury_mg ≥ 0` at all times (DB CHECK + domain checks).
- Recruitment conserves people: Δcity population = −Δbattalion manpower.
- Disbanding conserves people: Δcity population = +battalion manpower.
- Income is a pure function of (population, workshop tiers, workshop statuses).
- Employment priority: `employed2 = min(P, cap2)` and farmers > 0 only if all
  active workshop capacity is full.
- Recomputing economy for the same state twice yields identical results.
- Treaty transfers conserve gold and people: reparations and tribute move exactly what they take;
  a population transfer removes and adds the same integer number of people.
