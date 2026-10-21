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

### 2.3 Coalitions

The data model and the rules support any number of nations on two sides. Rulesets v1–v5 still fight
one nation against one nation: a move into a province held by battalions of two or more enemy nations
is refused with `COALITION_BATTLE_UNSUPPORTED`. Ruleset v6 fights **coalition battles**
(COMBAT_AND_SIEGE §3a): every battalion keeps its own nation's technology and doctrine, losses are
booked on the battalions that took them, and each nation gets its own report line. Q-D7 is decided
for v6 (OPEN_QUESTIONS decision log, 2026-10-20).

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
attacker) are **not** implemented. **Ruleset v6 replaces this automatic response by calls to arms (§12).**

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

Phase 10 (sections 10–16 below, ruleset v6) added coalition battles, multi-nation terms, calls to
arms, peace conferences, separate peace and elimination. Still not implemented: offensive calls to
arms, term costs in war score, neutral land (Q-W4), other victory conditions, AI (Phase 11),
profile statistics (Phase 12), stalled-game handling (Q-T3).

---

# Phase 10 — coalitions (ruleset v6)

Every rule below applies only to games created with ruleset **v6** (switched on by the
`features` flags of `rulesets/v6/diplomacy.json`). Games of v1–v5 keep their rules unchanged:
v5 games still use the defensive response of §3.3 and the turn-bound peace proposals of §5.
All values are PROVISIONAL and versioned.

## 10. Gold gifts — PROVISIONAL (Phase 10A)

- On its turn a nation may send gold to any other nation that is not eliminated: an integer amount
  of milligold, `0 < amount ≤ gifts.maxMg` (v6: **500 gold**), not above its treasury.
- Gifts to a nation one is at war with are refused (`GIFT_TO_ENEMY`) unless
  `gifts.allowToEnemies` (v6: false).
- One command `SEND_GOLD`: atomic (both treasuries in one transaction) and idempotent (same key =
  same gift). Public history: `gold_gift` table and event `GOLD_GIFT`; no gold is created or lost.

## 11. Nations without a player (NPC placeholder) — PROVISIONAL (Phase 10A; ruleset v6 only)

> **Ruleset v7 (Phase 11)** replaces the placeholder: unchosen nations are played by the computer,
> which takes part in all diplomacy by the rules of this document — see [AI.md](AI.md) §7. The
> placeholder below remains for v6 games.


On the Grand Campaign map only four nations are playable; every other nation (and every playable
nation nobody chose) is **controlled by the game**. Until Phase 11 adds an AI, such a nation is a
passive placeholder — it is not presented as an AI:

- it takes no turns (turn order skips it), never recruits, moves, declares war or proposes anything;
- it defends with the battalions it has and can be attacked, occupied and besieged normally;
- alliance proposals to it are refused (`NPC_NOT_NEGOTIABLE`); it is never called to arms;
- asked to a peace conference it **always agrees to talk** (the conference opens at once);
- in a draft it **approves only if everything it gives up is provinces the other side already
  controls militarily**; it never pays gold or tribute and never gives up people
  (`PeaceTerms.npcApproves`). Receiving costs it nothing.

These rules exist only so wars against NPC nations can end deterministically; Phase 11 replaces
them by an AI that uses the same services.

## 12. Calls to arms — PROVISIONAL (replaces §3.3 in v6)

- Declaring war brings **nobody** in automatically. The attacked **war leader** may, on its turn,
  call any nation it has an active alliance with (normal or defensive) and which does not already
  fight in that war (`POST /diplomacy/wars/{warId}/calls`). One pending call per war and ally.
- The ally answers **on its own turn**:
  - **Honour**: it joins the defending side (`join_reason = CALL_TO_ARMS`) and fights every
    attacking nation. Re-checked now: it must not be allied with, in truce with, already at war
    with, or fighting on the same side as any attacking nation.
  - **Decline**: the alliance with the caller **ends** (`end_reason = CALL_DECLINED`); attachments
    it no longer allows end and battalions without access withdraw. There is no other penalty — no
    reputation or opinion exists yet.
- A call has no deadline and is never answered automatically; it is cancelled when the war ends or
  either nation is eliminated. Calls are public (`CALL_TO_ARMS`, `CALL_HONORED`, `CALL_DECLINED`).
- Only defensive calls exist. Offensive calls (the attacker's allies) are not implemented.

## 13. War leaders

The nation that declared the war leads the attackers; the attacked nation leads the defenders.
Only the two leaders negotiate the general peace. A leader cannot leave its coalition by separate
peace. If a leader is eliminated (§16), the remaining nation of its side with the most legally owned
provinces (ties: lowest nation id) becomes leader (`WAR_LEADER_CHANGED`); a side left empty ends the
war (`end_reason = ELIMINATION`).

## 14. Peace conferences — PROVISIONAL (replaces §5 in v6)

### 14.1 Request and answer

- **General peace:** a war leader asks the other leader.
- **Separate peace:** a nation that fights the war but does not lead its side asks the **opposing**
  war leader. Leaders get `LEADER_CANNOT_LEAVE`.
- The counterpart **accepts or refuses at any time**, also outside its turn. A refused request changes
  nothing. The requester may withdraw an unanswered request.
- At most **one conference is active** in a game (`CONFERENCE_IN_PROGRESS`); one open request per war,
  requester and kind.

### 14.2 The game pauses

While a conference is **ACTIVE** the whole game is paused (`game.paused_conference_id`):

- no turn can end (`409 GAME_PAUSED_FOR_CONFERENCE`) and no normal command is accepted, from anyone;
- the round, the turn number and the active seat are kept exactly; when the conference ends, play
  resumes with the same player;
- conference commands (answer, revise, approve, reject, cancel) are accepted from any member, in or
  out of turn — they go through the command pipeline with lock, membership, idempotency and
  authorization, but without the active-player check.

There are **no timeouts, no cooldowns and no automatic acceptance**. Either principal may end the
conference at any time (`CANCELLED`); play then resumes.

### 14.3 Revisions

- Opening the conference creates **revision 1**: a white peace (no terms), authored by the requester.
- Either principal publishes a new revision — the complete list of terms — naming the revision it
  builds on (`expectedRevision`); if the draft changed meanwhile the request fails with
  `STALE_REVISION`. Revisions are numbered, immutable and kept (`conference_revision`).
- Terms are the four approved types (§5.2) with the v6 limits. Who may give or receive:
  - general peace: **every nation still fighting the war**, giving only to nations of the other side;
  - separate peace: the leaving nation and the nations of the opposing side.
  A term between two nations of the same side, or with an outsider, is `NOT_A_PARTY`.
- In v6 capitals are **cedable** (`features.capitalsCedable`); v5 keeps them uncedable.

### 14.4 Required approvals

Every revision stores the nations whose approval it needs:

- **both principals** (the two leaders, or the leaving nation and the opposing leader), and
- **every nation that gives something up** in it — a province, gold, tribute or people.

A nation that only **receives** needs no approval. Restoring control of occupied provinces after
peace (§6.3) is not a term and needs no approval from the occupier.

An approval or rejection names its revision and counts only for it (`STALE_REVISION` for an earlier
one). Publishing a new revision therefore voids every earlier approval. A nation decides once per
revision (`ALREADY_DECIDED`). A rejection is recorded and shown; the principals may revise or end
the conference.

### 14.5 Settlement

When the last required approval of the current revision arrives, the treaty is settled **in the same
transaction**:

1. The terms are validated again against the current state; if they no longer hold, nothing at all
   is recorded — not even the approval — (`PEACE_TERMS_NO_LONGER_VALID`) and the principals revise.
2. The conference is `CONCLUDED` and the treaty is stored (`peace_treaty` with `conference_id`,
   `revision`, `kind`, `exiting_nation_id`).
3. Terms are applied in the order of §5.3 (reparations, cessions, population, tribute).
4. **General:** the war ends for everyone; truces between every attacker and every defender
   (`truceRounds`, v6: 10); pending calls and other requests for this war are cancelled.
   **Separate:** the leaving nation leaves the war (`left_reason = SEPARATE_PEACE`), with truces with
   every nation of the other side; the war goes on for the others.
5. Landless nations are eliminated (§16); control of occupied provinces returns where war ended;
   every attachment of the leaving nation (lent or commanded) ends, and attachments the new relations
   no longer allow end; battalions without access withdraw and sieges are re-judged.
6. If one nation is left the game ends by domination (§16); otherwise play resumes.

Concurrent final approvals settle exactly once (game row lock and conditional status updates).

## 15. Separate peace

A separate peace is a conference of kind `SEPARATE` (§14). The leaving nation keeps its alliances; it
stops fighting this war only. Its battalions in territory it may no longer enter withdraw; any of its
battalions lent to coalition partners, and partner battalions it commanded, return to their owners.

## 16. Elimination and domination — PROVISIONAL

- Under v6 a nation that **legally owns no province** after a settlement is **eliminated**
  (`features.elimination`). Losing battles or having every province occupied never eliminates.
- Deterministic policy, in nation id order: the nation is marked eliminated (the row stays for
  history); its battalions dissolve and their soldiers return as civilians to the cities they were
  raised in, whoever owns them now, so no person is created or lost; its armies are deleted;
  battalions attached to it return to their owners; its alliances end, its proposals, calls and
  conference requests are cancelled, tributes it pays or receives are cancelled; it leaves every war
  (§13 for leaders); a human player takes no more turns and gives no more orders
  (`NATION_ELIMINATED`). Its treasury stays and can no longer be used.
- **Domination** (`victory.domination`, v6: on): when only one nation is not eliminated, the game
  ends at once with reason `DOMINATION` and that nation as winner. If no human player is left the game
  ends with reason `NO_HUMAN_PLAYERS` and the score standings.
- **Score at the round cap** (`victory.maxRounds`, v6: 30): v6 adds
  `floor(treasury / 100 gold) × pointsPerHundredGold` (v6: 1) to the §8 score. Eliminated nations
  rank after every surviving nation and cannot win; a tie with an eliminated nation is no draw.
- The result is stored exactly once (`game_result`); there is no concede button.

**Worked example.** At the round cap Castavar owns 9 provinces with 52,400 people and 340.5 gold:
`9 × 20 + 52 + 3 = 235`.
