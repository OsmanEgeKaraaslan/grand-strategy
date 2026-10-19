# Combat Balance Analysis (Phase 6, 2026-10-19)

A deterministic review of the field-combat model of [COMBAT_AND_SIEGE.md §2](COMBAT_AND_SIEGE.md)
with the **PROVISIONAL** coefficients of rulesets v1/v2 (Q-C1 is still OPEN). Every row below
is a golden test in `backend/src/test/java/com/reach/strategy/military/rules/BalanceScenariosTest.java`;
if a coefficient changes, this document and that table are regenerated together.

This is a check for **defects and pathologies**, not the balancing pass. Real balancing is
scheduled after Phase 9, when the complete game loop (economy → war → peace) exists.

Notation: `6I 2C 2A` = 6 infantry, 2 cavalry, 2 artillery full battalions (1,000 men);
`8I@500` = eight 500-man battalions. Losses are men over the whole battle (≤ 3 phases);
the winner is the side that did not break, else the lower loss ratio (ties: defender).

## 1. Results

| # | Scenario | Attacker | Defender | Terrain | A lost | D lost | Winner |
|---|---|---|---|---|---|---|---|
| 1 | Infantry mirror | 5I | 5I | plains | 482 (9.6%) | 482 (9.6%) | Defender (tie) |
| 2 | Infantry mirror | 5I | 5I | river | 485 | 385 | Defender |
| 3 | Infantry mirror | 5I | 5I | mountains | 488 | 313 | Defender |
| 4 | Cavalry attacks infantry | 4C | 4I | plains | 500 | 694 | Attacker |
| 5 | Infantry attacks cavalry | 4I | 4C | plains | 694 | 500 | Defender |
| 6 | Cavalry attacks infantry | 4C | 4I | mountains | 522 | 207 | Defender |
| 7 | Infantry attacks guns | 4I | 4A | plains | 367 | 928 | Attacker |
| 8 | Guns attack infantry | 4A | 4I | plains | 928 | 367 | Defender |
| 9 | Balanced vs infantry | 4I 4A | 8I | plains | 957 | 1,333 | Attacker |
| 10 | Artillery-heavy vs infantry | 2I 6A | 8I | plains | 1,561 | 1,035 | Defender |
| 11 | Balanced vs artillery-heavy | 6I 2C 2A | 2I 1C 7A | plains | 1,482 | 2,776 | Attacker |
| 12 | Cavalry-heavy, open ground | 2I 6C | 8I | plains | 940 | 1,294 | Attacker |
| 13 | Cavalry-heavy, mountains | 2I 6C | 8I | mountains | 977 | 438 | Defender |
| 14 | 1.5× vs mountain defenders | 6I | 4I | mountains | 386 | 381 | Attacker |
| 15 | 2× vs mountain defenders | 8I | 4I | mountains | 381 | 510 | Attacker |
| 16 | 3× vs mountain defenders | 12I | 4I | mountains | 373 | 771 | Attacker |
| 17 | 1.5× across a river | 6I | 4I | river | 383 | 469 | Attacker |
| 18 | Full vs half-strength battalions | 4I | 8I@500 | plains | 385 | 385 | Defender (tie) |
| 19 | Full vs badly damaged | 4I | 4I@300 | plains | 106 | 395 (broke) | Attacker |
| 20 | Damaged attacker vs fresh | 4I@300 | 1I | plains | 95 | 115 | Attacker |
| 21 | Frontline collapse | 3I 3A | 8I | plains | 980 | 983 | Defender |
| 22 | Frontline collapse | 1I 4A | 5I | plains | 1,021 | 609 | Defender |
| 23 | Equal cost (120 gold) | 12I | 3A | plains | 100 | 960 (broke) | Attacker |
| 24 | Equal cost (120 gold) | 2I 4C | 12I | plains | 1,426 | 827 | Defender |

Additional properties checked by tests: flank +30% for 8C vs 8I on plains, +15% for 2C vs 8I,
0 in mountains (`cavalryFlankIsCappedAndDisappearsInMountains`); river × mountains = 0.52 for
the attacker only; artillery protection falls phase by phase in row 21 (1.00 → 0.96 → 0.93);
winners always lose men; losses never exceed manpower over an exhaustive grid of small
compositions; identical results regardless of input order.

## 2. Findings

**No mathematical defect in the engine.** In particular:

- **Manpower, not battalion count, decides** (row 18 = row 1 scaled): splitting an army into
  small battalions gains nothing, and damaged battalions fight exactly in proportion to their
  remaining soldiers (rows 19–20).
- **Terrain is consistent.** River −20% and mountains −35% apply to the attacker's damage only;
  the defender's damage is never penalised; cavalry fights at 60% in mountains on both sides and
  cannot flank there (rows 3, 6, 13).
- **Frontline protection works as intended.** Protected guns are the most damaging unit (row 9),
  unprotected guns lose to cheaper infantry (rows 8, 10, 23), and an artillery-heavy *defender*
  loses too (row 11 — the guns are the front line and take 2,776 losses).
- **Casualty allocation is stable**: largest-remainder by manpower within a type, ties by
  battalion id; totals always equal the manpower removed.

**One report defect, fixed (no numerical change):** when both armies reached the break threshold
in the same phase, the report named only the loser as broken. `Battle.Result` and the stored
report now carry `attackerBroke` and `defenderBroke`; outcomes and losses are unchanged, so no
new ruleset version was needed. (With the current coefficients both sides breaking at once only
happens in extreme mismatches; the rule is tested with a deliberately lethal test ruleset.)

**Observations, deliberately not changed (provisional; for the post-Phase-9 balancing pass):**

1. **Equal armies on open ground trade evenly and the defender wins the tie** (row 1). Defenders
   get no positional bonus beyond terrain — that is Q-C6, still OPEN.
2. **Victory is by loss *ratio*, so a larger attacker can "win" while losing about as many men**
   (row 14: 386 vs 381). The defender then retreats. This is the documented rule (§2.7) and
   rewards concentration of force, but it makes 1.5× enough to take a mountain province with
   equal casualties. Candidate tweak later: absolute-loss tie-break or a minimum damage ratio.
3. **Pure cavalry can flank with no infantry to pin the enemy** (row 4: 4C beats 4I). It costs
   2.5× as much, and at equal cost infantry wins clearly (row 24), so this is not a dominant
   strategy; a later rule could require a frontline for flanking.
4. **Frontline collapse is gradual over three phases** (row 21: protection 1.00 → 0.93). Combined
   arms with a modest screen stay effective; collapse is decisive only when the screen is thin
   from the start (row 22). Longer battles or frontage (Q-C4) would sharpen it.
5. **Cost efficiency**: per gold, infantry is the strongest unit in most open-field fights
   (rows 23–24); artillery pays off only behind a screen. Combined with manpower being the
   binding constraint early (ECONOMY §4), this looks acceptable for now.
6. **Tiny forces deal almost nothing** (a 10-man battalion causes 0 losses: floors per type and
   phase). This is correct, not exploitable: strengths are summed per type before flooring, so
   many tiny battalions together fight like one large one.

**Not found:** dominant compositions, invulnerable units, negative or created manpower,
order-dependent results, or terrain applied inconsistently.

## 3. Things that remain provisional

All unit coefficients (attack 1.0 / 1.4 / 2.5, defence 1.2 / 0.9 / 0.5, screen 1 / 0.5 / 0),
loss 40 per damage point, break at 30%, three phases, protection and exposure constants, flank
cap and the cavalry mountain factor (Q-C1). Defender bonus (Q-C6), frontage (Q-C4), pursuit and
multi-province attacks (Q-C5) are not modelled.
