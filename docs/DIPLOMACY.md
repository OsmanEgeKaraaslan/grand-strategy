# Diplomacy, war, peace and match completion (Phase 9)

Rules of the `diplomacy` module, ruleset **v5** (`rulesets/v5/diplomacy.json`). Status labels follow
AGENTS.md §6: **APPROVED** (product owner), **PROPOSED/PROVISIONAL** (our default, configurable,
implemented because Phase 9 required a working rule), **OPEN** (needs a decision). Every number below
is a ruleset value, never a constant in code.

Games created with rulesets **v1–v4 are unchanged**: they have no diplomacy, every pair of nations is
permanently at war, movement is unrestricted and no game ends (the pre-diplomacy rule, GAME_DESIGN
§11.1). The diplomacy screen says so; diplomacy commands answer `409 DIPLOMACY_NOT_AVAILABLE`.

## 1. Relationships (any number of nations)

Every pair of different nations is in exactly one standing:

| Standing | Meaning |
|---|---|
| **Peace** (neutral) | Default for every pair at the start of a v5 game. Territory is closed, battalions never fight. |
| **War** | The two nations fight each other in one active war. |
| **Alliance** — normal or defensive | Mutually accepted; see §3. |
| **Truce** | Peace after a treaty, during which neither may declare war on the other (§6.2). |

Contradictions are impossible by rule and backed by the database: no war with oneself, never two
active wars between the same pair (partial unique index on `war_front`), never an alliance between
nations at war, at most one open (proposed or active) alliance per pair (partial unique index), and
no alliance that would bind a nation to an enemy of one of its allies (`ALLIANCE_CONFLICT`).

## 2. War

### 2.1 Declaration — APPROVED structure, PROVISIONAL rules (Q-D1)

- Only the **active player**, on its own turn, through the command pipeline (idempotent; two
  simultaneous declarations produce exactly one war).
- Refused if the target is the declarer itself (`CANNOT_TARGET_SELF`), unknown, already at war with
  it (`ALREADY_AT_WAR`), allied with it (`ALLIED_NATION` — end the alliance first), under truce
  (`TRUCE_IN_EFFECT`) or fighting on the declarer's side of another active war (`FIGHTING_TOGETHER`).
- Persisted as a `war` with participants (attacker leader, defender leader, joined allies) and one
  `war_front` per pair of nations fighting each other; a public `WAR_DECLARED` event is recorded.
- **Declaring war spends and resets nothing**: battalions that moved stay spent, ready ones stay ready.

### 2.2 What war allows

| Action | Needs war? |
|---|---|
| Entering a province held by another nation | Yes, unless that nation grants military access (§3.2) |
| Battle (moving into a province with battalions of another nation) | Only battalions of nations **at war** with the mover fight; allied or neutral battalions there stay out |
| Simple occupation of an unfortified province (round end) | Exactly one nation **at war with the controller** stands there, unopposed |
| Starting a siege of a fortified province | Same as occupation |
| Continuing a siege | The besieger is still present **and still at war** with the controller; otherwise the siege is lifted |
| Retreat | A defeated defender may retreat only where no battalions **at war with it** stand |

Movement access (`military.api.Hostility.Relations.mayEnter`): own territory (owned or controlled),
territory of a nation one is at war with, or territory whose controller grants access. **Neutral
foreign territory is closed** (`422 NO_MILITARY_ACCESS`).

### 2.3 Coalition limit (Phase 10)

The data model and the rules support any number of nations and two-sided coalitions. The battle
engine still fights one nation against one nation: moving into a province held by battalions of
**two or more enemy nations** is refused with `COALITION_BATTLE_UNSUPPORTED` (OPEN Q-D7, Phase 10).
The only published map has two nations, so this cannot occur yet.

## 3. Alliances

### 3.1 Proposal and acceptance — PROVISIONAL

- A nation proposes a **normal alliance** or a **defensive pact** on its turn; the other answers on
  **its own** turn (accept or reject). The proposer may withdraw its proposal.
- Acceptance re-checks the standing at that moment. An unanswered proposal **expires** at the end of
  round `proposedRound + proposalExpiryRounds` (v5: 3).
- Alliance proposals are private to the two nations; formed and ended alliances are public.

### 3.2 Military access — PROVISIONAL

| Relation | Access to the partner's territory (v5) |
|---|---|
| Normal alliance | Yes (`militaryAccess.normalAlliance = true`) |
| Defensive pact | No (`defensiveAlliance = false`) |
| Fighting on the same side of an active war | Yes (`coBelligerents = true`) |

### 3.3 Defensive response — PROVISIONAL (Q-D6)

When a nation is attacked (war declared on it), **every ally of the defender — normal or defensive —
joins the war on the defender's side at once**, unless that ally is also allied with the attacker,
is in truce with it, is already at war with it, or fights on the attacker's side of another war. Joining creates a front between the ally and the
attacker; the ally's battalions may then fight the attacker under the normal rules. Deterministic:
allies in nation id order. This replaces the earlier PROPOSED call-to-arms vote (GAME_DESIGN §11.1),
which needs a turn-based answer flow not yet designed; offensive calls (normal allies of the
attacker) are **not** implemented (Phase 10).

### 3.4 Ending an alliance — PROVISIONAL

Either member may end an **active** alliance at any time, except while both fight on the **same
side of an active war** (`ALLIES_AT_WAR_TOGETHER`: make peace first). Access ends at once: battalions
that stand in the former partner's territory withdraw (§6.3).

## 4. War score — PROVISIONAL (Q-D2)

Per side, 0…`maxScore` (100), deterministic, built only from authoritative facts:

| Component | Rule (v5 values) | Cap |
|---|---|---|
| Battles | Only the **winner** of a battle between nations of the war scores: `floor(enemyLosses × 5 / 1000)`, at most 10 per battle; **no points below 200 enemy losses** (tiny battles cannot be farmed) | 40 |
| Sieges | 10 per successful siege of an enemy city | 30 |
| Occupation | `floor(occupied enemy population × 50 / total enemy population)` — people in cities legally owned by the other side and controlled by this side | 50 |

`total = min(100, battles + sieges + occupation)`; **net = attacker total − defender total**
(−100…+100, positive favours the attacker).

- Battle and siege points are fixed when the event happens and stored once per source
  (`war_score_event`, unique per war, kind and battle/siege id): retries and restarts cannot count
  anything twice. Occupation is read from the current world, so it always matches the map.
- **War score guides; it never forces.** Any terms both leaders accept are valid (Q-D3: no term
  costs, no enforced peace, no surrender).

**Worked example (golden test `DiplomacyRulesTest.Score.workedExample`).** Varn won battles worth 7
and 4 points and one siege, and occupies 12,000 of Ostra's 46,000 people; Ostra won one battle worth
3. Varn: battles 11, sieges 10, occupation `floor(12,000 × 50 / 46,000) = 13`, total 34. Ostra:
3 + 0 + 0 = 3. Net +31 (favours Varn).

## 5. Peace

### 5.1 Proposals — PROVISIONAL (Q-D3)

- Only the **two war leaders** negotiate. The proposer acts on its turn; the recipient answers on
  **its own** turn: **accept**, **reject** or **counter** (a counter-proposal supersedes the original).
- Statuses: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED` (withdrawn by the proposer), `EXPIRED`
  (unanswered at the end of round `createdRound + 3`), `SUPERSEDED` (a newer proposal from the same
  leader, a counter, or another proposal of the war being accepted).
- Stored with war, nations, canonical terms, creation round and turn, and the game's state version.
- Visible only to proposer and recipient (others get `404`). There is **no concede or surrender**
  command (APPROVED 2026-10-17).
- `POST …/peace-proposals/check` validates terms without changing anything.

### 5.2 Terms — APPROVED types (GAME_DESIGN §11.3), PROVISIONAL limits

Every term names a giver and a receiver, both war leaders (`NOT_A_PARTY` otherwise).

| Term | Rule |
|---|---|
| `PROVINCE` (cession) | The giver must **legally own** the province (at proposal and at acceptance). Starting **capitals can never be ceded** (`CAPITAL_NOT_CEDABLE`), so no nation can be dismantled by treaty; losing territory never eliminates a nation. Each province once per treaty. |
| `GOLD` (reparations) | Integer milligold, `0 < amount ≤ 1,000 gold` per term; all gold terms of a giver together must be covered by its treasury (at proposal and at acceptance). Paid once, at acceptance. |
| `TRIBUTE` | At most one per treaty. Rate and duration come from the ruleset (10% of gross income, 10 global rounds) and are stored with the tribute; a client cannot choose them. |
| `POPULATION` | Explicit source city (owned by the giver, not ceded by the same treaty), destination city (owned by the receiver, or ceded to it by the same treaty) and an integer count of 1,000–2,000 people per term (Q-D4); all terms from one city together at most `floor(20% × its population)`, and it keeps at least 1,000 people. Soldiers and cohorts are never touched. |

### 5.3 Settlement — atomic

On acceptance, in **one transaction** (any failure rolls back everything, including the proposal
status):

1. Re-validate every term against the state **now** (ownership, treasury, populations). If anything
   no longer holds: `409 PEACE_TERMS_NO_LONGER_VALID`, nothing changes, the proposal stays pending.
2. Proposal `ACCEPTED`; other pending proposals of the war `SUPERSEDED`; the treaty is stored (unique
   per war and per proposal).
3. Terms in fixed order: **reparations → cessions → population transfers → tribute**.
   - Reparations: exactly the amount leaves the giver's treasury and arrives in the receiver's.
   - Cession: owner **and** controller become the receiver (`WorldApi.transferOwnership`, the only
     way ownership ever changes). City, buildings (workshops, fortress, institutions) and population
     stay with the province; projects under way continue and complete for the new owner (their cost
     was already paid).
   - Population: `−n` in the source city, `+n` in the destination — conserved exactly.
   - Tribute: created `ACTIVE`, first charged at the end of the signing round.
4. The war ends (`ENDED`, reason `PEACE_TREATY`) with all its fronts; a **truce** is stored for every
   attacker-side × defender-side pair until round `signedRound + truceRounds` (v5: 10).
5. **Occupation ends**: every province controlled by a nation no longer at war with its legal owner
   returns to the owner's control (`CONTROL_RETURNED`).
6. **Withdrawal** (§6.3) and siege re-evaluation: sieges whose besieger is gone or no longer at war
   are lifted.

## 6. After the war

### 6.1 Tribute — APPROVED base, PROVISIONAL details (Q-D5)

Round step **TRIBUTE**, right after ECONOMY (TURN_SYSTEM §5): for each active tribute, in id order,
`due = floor(payer's gross income this round × 1,000 / 10,000)` (floor to the milligold, PeaceTerms
`tributeDue`). The payer pays `min(due, treasury after settlement)` — **shortfall policy
(PROVISIONAL): what cannot be paid is forgiven, never debt**, and the round still counts. Exactly
what leaves the payer arrives at the receiver; gold is never created. One charge per tribute and
round (primary key `tribute_payment (tribute_id, round_number)`); after the 10th charge the tribute
is `COMPLETED`. Its full history (gross income, due, paid per round) stays readable.

Tribute does not enter the payer's upkeep or arrears: arrears come from the settlement before it.

**Renewed war (PROVISIONAL):** if payer and receiver are at war again (a new declaration, or a
defensive response), the tribute is `CANCELLED` (`WAR_RENEWED`). With a 10-round truce and 10-round
tribute this cannot happen in v5 between the treaty's own parties.

### 6.2 Truce — PROVISIONAL (Q-D1)

War may be declared again from round `truceUntilRound + 1`. A nation in truce with an attacker does
not join that attacker's war through the defensive response.

### 6.3 Withdrawal — PROVISIONAL

After a treaty, and after an alliance ends, every battalion standing where its nation may no longer
enter moves to the **nearest province its nation controls without enemy battalions** (fewest
borders, then the lowest province key; if none, the nearest province it may enter). Positions are
judged before anyone moves, so an army's battalions — which always stand together — arrive together
and stay an army. Nothing is deleted, split or reinforced; soldiers, cohorts and movement state are
kept. Public event `FORCES_WITHDRAWN`.

## 7. Turn rules

Every diplomatic command is a game command of the **active player** (lock → membership →
idempotency → in progress → active player → handler → record → state version). Proposals made on
one player's turn are answered on the other player's turn. Expiry is processed at round end in step
**DIPLOMACY** (the last step).

## 8. Match completion — PROVISIONAL (Q-T2)

- After the round end of **round 30** (`victory.maxRounds`) the game is `FINISHED`.
- Score per nation: **20 points per legally owned province + 1 point per full 1,000 people** in its
  legally owned cities. Occupation does not count — only ownership, which only treaties change.
- Ranking by score; equal scores share a rank; **a tie for the highest score is a draw**. No nation is
  eliminated; a nation without territory still has a standing.
- Stored exactly once in the round-end transaction: `game_result` (finishing round, reason
  `ROUND_LIMIT`, `WINNER`/`DRAW`, winner, ruleset, statistics) and one `game_result_standing` per
  nation naming its player (for Phase 12 statistics, not shown on profiles yet).
- After the finish every command answers `409 GAME_FINISHED`; state, map, economy, forces,
  diplomacy and `GET /games/{id}/result` stay readable.

**Worked example.** At the end of round 30 Varn owns 7 provinces with 46,000 people and Ostra 5 with
51,999: Varn `7 × 20 + 46 = 186`, Ostra `5 × 20 + 51 = 151` → Varn wins. An untouched game on the
symmetric map is a draw.

## 9. What is not in Phase 9

Coalition battles and multi-nation terms (Phase 10), offensive calls to arms, a call-to-arms vote,
term costs in war score, separate peace of a single coalition member, neutral nations without a
player, other victory conditions, AI (Phase 11), profile statistics (Phase 12), stalled-game handling
(Q-T3).
