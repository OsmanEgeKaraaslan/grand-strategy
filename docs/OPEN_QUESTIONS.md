# Open Questions

Every undecided or conflicting mechanic, with a proposed default and trade-offs.
IDs are referenced from the other documents.

## Priority scale

| Priority | Meaning |
|---|---|
| **P0** | Blocks the vertical slice (Phases 3–5). Decide before the phase listed. |
| **P1** | Blocks Phases 6–7 (advanced combat, sieges). |
| **P2** | Blocks Phases 8–10. |
| **P3** | Polish / can change after launch via ruleset. |

To resolve: answer *accept proposal* / *alternative* / *custom*. Record the
decision and date in the Decision log at the bottom, then relabel the rule
APPROVED in every document that references it.

---

## P0 — playable prototype (Phases 3–5)

### Decided on 2026-10-17 (see Decision log)

| ID | Question | Decision |
|---|---|---|
| **Q-W1** | Ownership vs control | **Separate from the start. Occupation changes only the controller; legal ownership changes only via diplomacy (peace treaties). No automatic owner transfer, not even as an MVP shortcut.** |
| **Q-T1** | Turn order | Fixed seat order (join order, host first) |
| **Q-T4** | Fog of war | None in the initial MVP |
| **Q-W3** | Start setup | Proposed defaults accepted as provisional data (4–5 provinces, capital 12,000 pop, fortress 1, one T1 workshop, 50 gold, `3I 1C`); **no neutral provinces** pending Q-W4 |
| **Q-M1** | Movement limit | **Per battalion**: one adjacent province per battalion per player turn. Eligibility is tracked on each battalion; armies are organisational groupings; regrouping never resets eligibility |
| **Q-M2** | Entering an undefended hostile city province (Phases 5–6) | Simple occupation at the next round end — **control only** |
| **Q-M3** | New recruits | Cannot initiate movement or attacks in the turn they are recruited; **do** defend their province immediately |
| **Q-M4** | Stacking/grouping | Armies are organisational; create/rename/regroup freely on own turn; all hostile battalions in a province defend together |
| **Q-M5** | "Friendly-controlled" | Controlled by the battalion's own nation (allies excluded until diplomacy) |
| **Q-E1** | Income vs upkeep order | Income before upkeep in global round processing |
| **Q-E1b** | Deficit | Proposed MVP default accepted (treasury floors at 0, deficit recorded, `IN_ARREARS` next round); harsher consequences remain a later option |
| **Q-E2** | Workshop cap | 6 per city (ruleset value) |
| **Q-E5** | Rounding | Floor via basis points, as documented |
| **Q-E7** | Costs | Proposed values accepted as **provisional balance**, ruleset-configurable |
| **Q-E8** | Construction timing | As proposed (active at end of round N+d−1, after income) |
| **Q-C2** | Retreat | Proposed default accepted |
| **Q-C3** | Destroyed when no retreat path | Proposed default accepted |
| **Q-W2** | Income from occupied cities | **APPROVED 2026-10-17: when a province's legal owner and military controller differ, its city produces zero collectible gold for both nations.** Income resumes for the owner on liberation, and for a new owner after a treaty transfers ownership. Occupation itself never changes ownership, damages buildings or reduces population. Enforced by the backend economy engine (Phase 4). Recruitment there is already impossible for both sides (it requires owning **and** controlling the city, Q-M5/ECONOMY §8) |

Cross-cutting principles confirmed: all numerical coefficients live in versioned
rulesets; combat is deterministic; frontline protection is a core planned
mechanic (Phase 6); battalion manpower is tracked numerically; reinforcement
consumes real population and resources.

### Still OPEN

| ID | Question | Proposed default (not approved) | Trade-offs | Needed by |
|---|---|---|---|---|
| **Q-W4** | How does unowned (independent) land become legally owned, given ownership changes only by diplomacy? | No neutral provinces in MVP maps; later a "claim/colonize" action or treaty-like annexation of independents | Neutrals add expansion but need their own legal-transfer rule | Ph 9 / map design |
| **Q-T2** | Victory conditions and how a game ends | **Implemented PROVISIONALLY in Phase 9 (2026-10-19, ruleset v5):** game ends after round 30; score = 20 per legally owned province + 1 per full 1,000 people in owned cities; tie for first = draw; result stored once (DIPLOMACY.md §8). Other candidates (peace-based, ownership share) remain possible. **No automatic elimination.** | Each changes pacing heavily | Ph 9 |
| **Q-T3** | Stalled games without timeouts or concession | MVP: operator `ABANDONED` action only. Candidates later: unanimous vote to abandon; optional per-lobby turn timer chosen at creation | A per-lobby timer is not an automatic default rule, but it is still a new rule needing approval | Ph 3 (operator only) / later |
| **Q-M6** | Destination grouping after a move | Moved battalions form a new army at the destination unless the player names an existing own army there. **Provisional MVP choice (Phase 5, kept and recorded in Phase 6, 2026-10-19):** a whole army that moves stays one army; battalions that move without the rest of their army leave it and stand ungrouped; the player groups them with the regroup command. Location and eligibility are per battalion either way | Auto-merging into the largest army is more convenient but surprising | Ph 5 (provisional MVP choice, recorded 2026-10-19) |

## P1 — blocks advanced combat and sieges

| ID | Question | Proposed default | Trade-offs | Needed by |
|---|---|---|---|---|
| **Q-C1** | Accept the 3-phase combat model and its coefficients (attack 1.0/1.4/2.5, defence 1.2/0.9/0.5, screen 1/0.5, k = 1, artEff 0.4–1.0, exposure 0.2–1.5, flank max 30%, loss 40/point, break 30%) | Accept as starting balance, tune via ruleset. **Implemented in Phase 5 as PROVISIONAL ruleset values (2026-10-18); still not approved** | Simpler single-exchange model is easier to explain but can't show artillery collapsing mid-battle | Ph 6 |
| **Q-C4** | Frontage limits | None until large armies are common; later 20 plains / 12 mountains | Frontage makes doom-stacks less dominant | Ph 6+ |
| **Q-C5** | Multi-army battles (several attacking armies, allies joining) | Defender side combines all hostile armies in province; attacker is one army | Coordinated attacks need a "join attack" step in sequential turns | Ph 6 |
| **Q-M7** | Fortified provinces under simple occupation (Phases 5–6, before sieges exist) | **Resolved in Phase 7 (2026-10-19):** fortified provinces (fortress ACTIVE, level ≥ 1) need a successful siege; unfortified and damaged-fortress provinces keep simple occupation. One control phase applies both rules (COMBAT_AND_SIEGE §5.0) | An interim "fortresses block occupation" rule would make fortresses matter earlier, but would invent a rule ahead of the siege design | Ph 7 |
| **Q-C6** | Defender bonus beyond terrain; does a fortress help field battles in its province? | None beyond terrain and unit defence | A fort bonus makes defenders sturdier but double-counts with siege difficulty | Ph 6 |
| **Q-R1** | Reinforcement location, rate, cost | **Accepted for implementation 2026-10-19 (option A):** own owned-and-controlled, unbesieged city province, ≤ 500 men/battalion/turn, recruitment cost pro rata (implemented rounding **up**, PROPOSED), from the local city, blocked in arrears; the city keeps 1,000 civilians (PROPOSED, as for recruitment); a reinforced battalion cannot move or attack that turn but defends (PROVISIONAL). Implemented in Phase 6 | B (origin only) is readable but slow; C (supply range) is richer but complex | Done (Ph 6) |
| **Q-S1** | Siege duration formula (original `ceil(3F − S)` conflicts with 6–8 rounds) | Progress points: required `(5 + F) × 100`, progress `100 + 50 × artillery/1000` per round. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Alternative B: original formula with `S = 0 → 5 + F` special case | Ph 7 |
| **Q-S2** | What counts as an "artillery siege" (damage outcome)? | Any participating artillery manpower at the fall round. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Majority-of-progress-from-artillery is fairer if guns arrive late; more bookkeeping | Ph 7 |
| **Q-S11** | Military actions inside a besieged province | **Implemented PROVISIONALLY in Phase 7 (2026-10-19, product-owner MVP policy):** no recruiting, reinforcing, building or repairing in a besieged city; no disbanding inside a besieged province; a cohort whose origin is besieged settles in the local city on disband. Earlier proposal: Recruitment and reinforcement blocked in a besieged city (approved for recruitment, ECONOMY §8; Phase 6 reinforcement has the hook but no sieges exist yet); open: may a besieged battalion disband into its besieged city, and do cohorts from a besieged origin city fall back to the local city? Proposed: disband allowed only if the province is not besieged; a besieged origin counts as not friendly-controlled | Allowing it lets a garrison "dissolve" into the starving city to avoid destruction | Ph 7 |
| **Q-S12** | Who pays a fortress's upkeep while its city is occupied? | Nobody (PROVISIONAL, implemented in Phase 7): only an owner that also controls the city pays; mirrors Q-W2 (an occupied city pays nobody) | Charging the owner keeps the cost of a lost fortress; charging the occupier makes holding expensive | Ph 9 |
| **Q-S13** | Fortress integrity beyond intact/damaged | Phase 7 keeps integrity binary in effect (full while ACTIVE, 0 when DAMAGED by an artillery capture); `fortressIntegrityLossBps` is configurable for partial damage later | Gradual bombardment would let artillery wear a fortress down round by round, but needs its own repair pricing | Post Ph 9 |
| **Q-S14** | Projects in a captured city | A running fortress upgrade is lost on capture (paid, no refund); workshop construction projects continue (PROVISIONAL; COMBAT_AND_SIEGE §5.6 proposed cancelling all projects, not applied to workshops so no building is destroyed) | Cancelling everything is harsher and simpler to explain | Ph 9 |
| **Q-S15** | Defender advantage at a fortress in field battles (relates to Q-C6) | None in Phase 7: the fortress only lengthens sieges; a relief army or besiegers fight an ordinary battle | A fortress bonus to defenders would make relief easier and double-count with siege duration | Post Ph 9 |
| **Q-C8** | Loss-ratio victory lets a 1.5× attacker take a mountain province with equal absolute losses (BALANCE_ANALYSIS.md, observation 2) | Keep for now; revisit in the post-Phase-9 balancing pass (options: absolute-loss tie-break, minimum damage ratio, defender bonus Q-C6) | Changing it affects every battle outcome | Post Ph 9 |
| **Q-S2b** | Do sieges start automatically or by explicit action? | Automatic on entering. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Explicit action allows "passing through" enemy land | Ph 7 |
| **Q-S3** | Which workshop is damaged on artillery capture? | Highest-tier ACTIVE, ties lowest id. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Lowest tier is gentler; random is excluded (determinism) | Ph 7 |
| **Q-S4** | Effect of a damaged fortress | Effective level 0, no upkeep until repaired. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Level − 1 is gentler and keeps some value | Ph 7 |
| **Q-S5** | Fortress repair cost | 30% of building the current level, 1 round (mirrors workshops). **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Higher cost makes artillery capture more punishing | Ph 7 |
| **Q-S6** | Does a besieged city still produce income for its owner? | Yes. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Blockade cutting income strengthens starvation strategy | Ph 7 |
| **Q-S7** | Attrition in the round the city falls; attrition when capturing unfortified cities (1-round siege) | Yes in both cases (uniform rule). **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Exempting unfortified cities makes expansion into neutrals cheaper in people | Ph 7 |
| **Q-S8** | Is siege progress kept when a siege is lifted and resumed? | Lost. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Keeping it (with decay) rewards persistent besiegers | Ph 7 |
| **Q-S9** | Garrisons: does a city defend itself? | No garrison in MVP. **Implemented as this PROVISIONAL default in Phase 7 (2026-10-19); not approved balance** | Garrisons protect undefended cities from tiny raiding armies | Ph 7 |
| **Q-S10** | Multiple nations besieging one city | One besieger nation per city; allies later join as participants, controller = siege leader | Needs alliance rules | Ph 9 |
| **Q-E3** | Does a workshop keep producing while upgrading? | Yes, at old tier. **Implemented PROVISIONALLY in Phase 8 (2026-10-19, ruleset v4); not approved balance** | Shutting it during upgrade adds a real cost to upgrading | Ph 8 (Tier 2) |
| **Q-E4** | Refunds for cancelled projects | No cancel in MVP; later 0% | 50% refund is friendlier | P3 |

## P2 — blocks progression, diplomacy, analytics

| ID | Question | Proposed default | Needed by |
|---|---|---|---|
| **Q-E6** | Population growth formula | 0 in MVP; later `floor(P × growth_bps / 10 000)` per round with tech bonuses, none while besieged. **Implemented PROVISIONALLY in Phase 8 (2026-10-19, ruleset v4); not approved balance** | Ph 8 |
| **Q-C7** | Battalion "relevant military technology": snapshot at recruitment and how upgrades happen | **Phase 8 provisional choice (2026-10-19):** technology is national and applies to every existing battalion at once; `equipment_level` stays unused, reserved for a later per-battalion equipment system, so there is one source of military strength. Earlier proposal: `equipment_level` at recruitment; upgrade by paying a fee in own territory, or auto-upgrade on reinforcement | Ph 8 |
| **Q-X1** | Technology costs, track lengths, effects, institution point output | 3 linear tracks × 5 levels, flat 10 points/round per institution, effects as typed modifiers. **Implemented PROVISIONALLY in Phase 8 (2026-10-19, ruleset v4); not approved balance** | Ph 8 |
| **Q-X2** | Can a doctrine be changed later, and how many doctrines may a nation hold? | PROVISIONAL in Phase 8: one doctrine of tier 1, permanent (`doctrinesPerTier` in the ruleset; higher tiers fit the same model) | Respecing adds flexibility but makes doctrine a cheap reaction to each war | Post Ph 9 |
| **Q-X3** | Should institutions in besieged cities keep producing research? | PROVISIONAL: yes (like income, Q-S6); occupied cities' institutions produce for nobody | Cutting research under siege strengthens sieges further | Post Ph 9 |
| **Q-X4** | Research balance: 60 gold institutions, 10 points/round, 40/80-point levels, 30-point doctrines, +10%/+20% damage, 0.5% growth | Accept as starting balance for the post-Phase-9 playtest | All interact with income (a level-2 university costs 60 gold + 1 gold/round for 4 rounds to reach Tier 2) | Post Ph 9 |
| **Q-D1** | War declaration rules (cooldowns, truces after peace) | Declare on own turn; 10-round truce after peace. **Implemented PROVISIONALLY in Phase 9 (ruleset v5)**; no cooldown after ending an alliance | Ph 9 |
| **Q-D2** | War score calculation | Battles (casualty-weighted), occupied population share, sieges, capital occupation; 0–100. **Implemented PROVISIONALLY in Phase 9:** winner-only battle points `floor(losses × 5 / 1000)` (min 200 losses, ≤ 10 per battle, ≤ 40), 10 per siege (≤ 30), occupation share × 50; capital occupation not weighted separately (DIPLOMACY.md §4) | Ph 9 |
| **Q-D3** | Peace acceptance and war-score cost of each term | Terms cost war score; offers within score can be enforced, beyond require voluntary acceptance. **Phase 9 (PROVISIONAL):** acceptance is always voluntary (accept, reject, counter on the recipient's turn); war score is shown as guidance only; term costs and enforced peace stay OPEN | Ph 9 |
| **Q-D4** | Population transfer amount ("1–2 Pop") | **1,000–2,000 people** (proposed conversion, needs approval); capped at 20% of source city. **Implemented PROVISIONALLY in Phase 9**, plus a 1,000-person minimum residual in the source city | Ph 9 |
| **Q-D5** | Tribute base: gross or net income? Rounding? | 10% of gross income, floor to mg, 10 rounds. **Implemented PROVISIONALLY in Phase 9**: paid after settlement, at most the treasury, shortfall forgiven (no debt); cancelled on renewed war |
| **Q-D6** | Defensive alliance response | **Ruleset v6 (Phase 10, 2026-10-20): defensive calls to arms** — the attacked leader calls allies; honour joins the war, decline ends the alliance; no deadline, no other penalty (DIPLOMACY §12). v5 keeps the automatic response. **PROVISIONAL Phase 9:** the defender's allies (normal or defensive) join the war automatically unless allied with, in truce with or already at war with the attacker; no call-to-arms vote, no offensive calls Trade-off: A vote gives allies choice but needs an answer flow and a penalty for declining. | Ph 10 |
| **Q-D7** | Coalition battles and multi-nation peace terms | **Decided for ruleset v6 (Phase 10, 2026-10-20):** coalition battles with per-nation modifiers and losses, attachments, peace conferences in which any nation of either side may give or receive, with the approval of every giver (DIPLOMACY §14, COMBAT_AND_SIEGE §3a). v1–v5 keep: **Not implemented:** a move into a province held by two enemy nations is refused (`COALITION_BATTLE_UNSUPPORTED`); only the two war leaders negotiate and give or receive terms Trade-off: Needed once maps have 3+ nations. | Ph 10 |
| **Q-D8** | Which provinces can be ceded | **PROVISIONAL Phase 9:** any province the giver legally owns except starting capitals (so no nation is dismantled by treaty) Trade-off: "Border provinces only" is closer to the original wording but needs a border definition for occupied land. | Post Ph 9 playtest |
| **Q-D9** | Military access and alliance termination | **PROVISIONAL Phase 9:** normal alliances and co-belligerents grant access, defensive pacts do not; an alliance cannot end while both fight on the same side; battalions without access withdraw to the nearest own province | Access for defensive pacts would let defenders reinforce each other | Post Ph 9 playtest | Ph 9 |
| **Q-D10** | Behaviour of nations without a player until AI exists | **PROVISIONAL Phase 10:** passive placeholder — no turns, no initiative, refuses alliances, always agrees to a peace conference, approves only drafts in which it gives up provinces the other side already controls (DIPLOMACY §11) | A fixed rule lets wars against them end without pretending to be an AI | Ph 11 |
| **Q-D11** | Peace conference procedure | **PROVISIONAL Phase 10:** one active conference pauses the whole game; immutable revisions; approvals bound to one revision; both principals plus every giver approve; atomic settlement with fresh validation; no timeouts, cooldowns or automatic acceptance (DIPLOMACY §14) | A pause can be held open by a principal, but either may end it at any time | Post Ph 10 playtest |
| **Q-D12** | Separate peace, elimination and domination | **PROVISIONAL Phase 10:** non-leaders leave by separate peace with the opposing leader; a nation owning no province after a settlement is eliminated (soldiers go home as civilians); the last nation standing wins at once; capitals cedable in v6; score adds 1 point per 100 gold | Cedable capitals make elimination possible at all | Post Ph 10 playtest |
| **Q-W5** | Grand Campaign balance (tiers, starting gold and forces, fortress placement) | **PROVISIONAL Phase 10:** four symmetric powers (7 provinces, 46,000 people, 50 gold, 3I 1C) and six NPC factions by tier (ECONOMY §10b) | Asymmetric NPC strength shapes early expansion | Post Ph 10 playtest |
| **Q-AI1** | Computer opponent balance and pacing (weights in ai.json, one difficulty) | **PROVISIONAL Phase 11:** values of rulesets/v7/ai.json; Scenario A: the computer major power tends to win, minors are overrun, passive humans get attacked by several neighbours | Needs a human playtest; difficulty levels later | Post Ph 11 playtest |
| **Q-AI2** | Computer execution point | **PROVISIONAL Phase 11:** computer seats after the human seats, once per round, before round processing (same economic cycle as humans) | Humans wait a few seconds after the last human turn | Post Ph 11 playtest |
| **Q-AI3** | Ending a deadlocked conference between computers | **PROVISIONAL Phase 11:** each computer drafts at most `maxRevisionsPerConference` revisions and its requester ends a conference that can make no more progress; conferences with a human principal stay open (no timeouts) | Prevents an all-computer pause; human conferences untouched | Post Ph 11 playtest |
| **Q-SO1** | Who may invite players into a private lobby? | **PROVISIONAL Phase 13A:** only the host, only friends, only while the lobby waits for players; invitations reserve no seat | Letting every member invite is friendlier but lets guests fill the host's lobby | Beta feedback |
| **Q-SO2** | Blocking scope | **PROVISIONAL Phase 12B:** a block in either direction stops friend requests, lobby invitations and Quick Match pairing; it ends the friendship and withdraws pending requests and invitations; it is not revealed. It does **not** remove anyone from a game already shared, hide in-game diplomacy, or hide public profiles | Full mute/report tools need moderation (later) | Phase 15 |
| **Q-SO3** | Quick Match faction assignment | **PROVISIONAL Phase 13B:** two playable nations of the largest tier (Grand Campaign: POWERs), capitals ≥ 4 borders apart preferred, random pair and side; the longer-waiting player moves first | Drafting/picks later; first-move advantage unmeasured | Beta feedback |
| **Q-SO4** | Search expiry and game limits | **PROVISIONAL Phase 13B:** a search without a heartbeat for 90 s expires (searches only; games have no timer, Q-T3 unchanged); no new search with ≥ 5 unfinished games | Too strict for very slow players? | Beta feedback |
| **Q-SO5** | Leaving a running game | **OPEN:** nobody can leave or concede a started game (APPROVED no concede, Q-T3); unfinished games count toward the Quick Match limit | Players who abandon matches lock themselves out of new Quick Matches until those games end | Post-beta |
| **Q-SO6** | Account deletion and public lobby discovery | Account deletion **implemented in Phase 15A** (anonymize, social data removed, refused during unfinished games); public lobby discovery still **deferred** | — | Production readiness |
| **Q-AC1** | Account deletion while seated in an unfinished game (lobby or running match) | **OPEN.** Phase 15A refuses deletion (409 `ACCOUNT_DELETION_BLOCKED`) rather than inventing a forfeit/leave rule. Options: (a) keep refusing, operator handles exceptions; (b) leave lobbies automatically, forfeit running games via a future "leave game" rule (Q-SO5) | — | Public beta |
| **Q-MOD1** | Moderation: suspending accounts, resetting names, closing stuck games | **APPROVED 2026-11-03** (product owner): administrators (Supabase user ids in `ADMIN_SUPABASE_USER_IDS`) may suspend and reinstate accounts, reset names and abandon games; a suspended player's nations in running games are **taken over by the computer** for the rest of the game. **PROPOSED** details: their lobbies are abandoned (there is no "leave lobby" yet); on rulesets without computer opponents (v1–v6) the running game is abandoned instead; reinstating does not return seats; a game left without humans ends at once (`NO_HUMAN_PLAYERS`, as for elimination) | Seat takeover is final; no notification to the other players yet | Production readiness |
| **Q-AC2** | Retention: how long Supabase Auth users of deleted accounts, `terms_acceptance` rows, logs and backups are kept | **OPEN — operator/legal decision** (DATA_PRIVACY_INVENTORY.md §5) | — | Public beta |
| **Q-A1** | Estimated play time method | Sessionize commands with 10-min gap + 2-min allowance | Ph 10 |
| **Q-A2** | Email open/click tracking | Off | Ph 10 |

---

## Contradictions found in the original specification

1. **Siege duration** formula vs 6–8 round intent → Q-S1.
2. **Upkeep timing** ("beginning of turn") vs round-end processing → Q-E1.
3. **Occupation vs ownership** with no peace system before Phase 9 → decided Q-W1 (control only; ownership via diplomacy); economic effect decided Q-W2 (occupied cities yield no income to anyone).
4. **Tier 2 workshops** in economic examples, but civilian tech comes after MVP → gated by ruleset flag.
5. **"1–2 Pop"** transfer under numeric population → Q-D4.
6. **"Approximately 5%"** attrition needs exact rounding → Q-E5 / Q-S7.

Not contradictions (verified consistent): employment priority vs recruitment
removal order (same rule); "fast sieges preserve people" (follows from shorter
duration with constant 5% attrition); workshop repair rule (30%, one round).

## Architectural risks

| Risk | Mitigation |
|---|---|
| Spring Boot 4 ecosystem maturity (Jackson 3, Hibernate 7, library compatibility) | Phase 1 spike verifies Testcontainers, Flyway, springdoc, ArchUnit/Modulith versions before committing |
| Supabase transaction pooler incompatible with session features | Direct or session-mode connection; documented in ARCHITECTURE §4.3 |
| Supabase JWT signing mode (legacy HS256 vs asymmetric JWKS) | Decided in Phase 2: asymmetric JWKS only (ES256/RS256). Legacy-secret projects must enable Supabase JWT Signing Keys |
| Long-held game lock if round processing grows | Measured metric; split into committed processing state + worker only if needed |
| Balance changes breaking running games | Ruleset snapshot per game |
| Map authoring errors (asymmetric adjacency, orphan provinces) | Import validator + tests |
| Scope creep (diplomacy/tech before the slice is fun) | Roadmap gates; AGENTS.md scope discipline |
| Stalled games (no deadlines) | Operator abandon (MVP), visibility of waiting player; player-facing option OPEN (Q-T3) |

---

## Decision log

| Date | ID | Decision | By |
|---|---|---|---|
| 2026-10-17 | Q-W1 | Ownership and control are separate from the start; occupation changes control only; ownership changes only through diplomacy (primarily peace treaties); no automatic owner transfer, including in the MVP | Product owner |
| 2026-10-17 | Q-M1 | Movement eligibility per battalion, not per army; 1 adjacent province per battalion per player turn; armies are organisational; regrouping never resets eligibility | Product owner |
| 2026-10-17 | Q-M3 | New recruits cannot move or attack in the turn they are recruited, but defend immediately | Product owner |
| 2026-10-17 | — | No concede/surrender in the MVP; wars end through peace agreements (Phase 9); losing battles or all provinces occupied is not peace; no automatic elimination; prototype may lack a war-ending system (Q-T2, Q-T3, Q-D3 stay OPEN) | Product owner |
| 2026-10-17 | Q-T1, Q-T4 | Fixed turn order; no fog of war; no deadlines; no scheduled turn advancement | Product owner |
| 2026-10-17 | Q-E1 | Income before upkeep in global round processing | Product owner |
| 2026-10-17 | Q-W3, Q-M2, Q-M4, Q-M5, Q-E1b, Q-E2, Q-E5, Q-E7, Q-E8, Q-C2, Q-C3 | Proposed P0 defaults accepted where not in conflict with the decisions above; costs are provisional balance; all coefficients configurable through versioned rulesets | Product owner |
| 2026-10-17 | — | Confirmed principles: deterministic combat; frontline protection is a core planned mechanic; numeric battalion manpower; reinforcement consumes real population and resources. P1 and P2 proposals are **not** approved | Product owner |
| 2026-10-17 | Phase 1 scope | Phase 1 is backend-only (frontend scaffold moves to Phase 2) | Product owner |
| 2026-10-17 | Q-W2 | Occupied city (owner ≠ controller) yields zero collectible gold for both nations; owner resumes on liberation, new owner collects after a treaty transfer; occupation alone does not change ownership, damage buildings or reduce population; enforced by the backend economy engine | Product owner |
| 2026-10-18 | Phase 5 scope | Phase 5 includes basic frontline protection and a provisional cavalry flank (moved forward from Phase 6), with all combat coefficients in the versioned ruleset and labelled PROVISIONAL; Q-C1 stays open. Unit costs 10/25/40 gold and upkeep 0.5/1.5/2 gold are provisional (Q-E7). No disband or reinforcement commands in Phase 5 | Product owner (Phase 5 request) |
| 2026-10-19 | Q-R1 | Reinforcement rules accepted for implementation (option A): max 1,000 manpower, ≤ 500 replacements per battalion per player turn, real civilians from an owned and controlled, unbesieged city, gold pro rata to the recruitment cost, active player only, blocked in arrears; reinforced battalions still defend. Consuming the battalion's movement/attack for that turn is a PROVISIONAL tactical rule | Product owner (Phase 6 request) |
| 2026-10-19 | Disbanding, origin tracking | Disband behaviour as approved (owner, own-controlled territory only, survivors return to origin city else local city, dead never return), applied per origin cohort to prevent population teleportation | Product owner (Phase 6 request) |
| 2026-10-19 | Q-M6 | Phase 5 grouping behaviour recorded as the provisional MVP choice (partial moves leave the army; a whole army moving stays grouped; regrouping never resets movement) | Product owner (Phase 6 request) |
| 2026-10-19 | Roadmap after Phase 9 | 10 World Expansion, 11 Enemy AI, 12 Accounts & Player Profiles, 13 Public/Private Games & Friends, 14 UI/UX Overhaul, 15 Security Audit & Hardening, 16 Cloud Deployment & Public Beta; no separate gameplay-testing phase (manual playtest after Phase 9); security fundamentals continue in every phase | Product owner |
| 2026-10-19 | Phase 7 sieges | Siege model implemented as the PROPOSED defaults of Q-S1–Q-S9 (progress points, floor 5% attrition, artillery damage to fortress and one workshop, repairs 30% / 1 round, progress lost when lifted, no garrison), all values in ruleset v3 and PROVISIONAL; Q-M7 resolved (fortified provinces need a siege); besieged-city policy Q-S11 as requested by the product owner; new games use ruleset v3 with starting fortresses, running games keep v1/v2 | Product owner (Phase 7 request) |
| 2026-10-19 | Phase 8 research | Institutions, national research pools, two technology tracks, four tier-1 doctrines (one may be chosen), Tier 1 → 2 workshop upgrades and population growth implemented with PROVISIONAL values in ruleset v4 (Q-X1, Q-E3, Q-E6, Q-C7, Q-X2–Q-X4); new games use v4, running v1–v3 games keep their rules | Product owner (Phase 8 request) |
| 2026-10-19 | Phase 9 diplomacy | Wars, normal alliances and defensive pacts, war score, peace proposals with atomic settlement of the four approved term types, tribute, truce, withdrawal and a round-30 victory implemented with PROVISIONAL rules in ruleset v5 (Q-D1–Q-D5, Q-D6, Q-D8, Q-D9, Q-T2); no concede or surrender; coalition battles deferred (Q-D7); new games use v5, running v1–v4 games keep permanent war and never finish | Product owner (Phase 9 request) |
| 2026-10-20 | Q-D6, Q-D7, Q-D10–Q-D12, Q-W5 | Phase 10 (ruleset v6): Grand Campaign map with 72 provinces and 10 factions, 2–4 human players with passive NPC placeholders, starting values per tier, gold gifts, defensive calls to arms, coalition battles with per-nation modifiers, attached battalions with per-round movement, peace conferences that pause the game with revisions and required approvals, separate peace, elimination and domination — all PROVISIONAL and versioned; v1–v5 unchanged | Product owner (Phase 10 request) |
| 2026-10-20 | Q-AI1–Q-AI3 | Phase 11 (ruleset v7): computer seats play every unchosen nation through the normal command pipeline with computer accounts (no login), bounded and restart-safe turns, rule-based planners for economy, research, military and diplomacy, PROVISIONAL weights in ai.json; v1–v6 unchanged; battle defect (zero-manpower flush) fixed | Product owner (Phase 11 request) |
| 2026-10-22 | Q-SO1–Q-SO6 | Phases 12–13: profiles with unique handles, emblems and statistics from stored results; friends, blocks and notifications; host-only friend invitations into lobbies; unranked Quick Match (two humans, eight computer nations, current ruleset) with a database queue; provisional choices recorded as Q-SO1–Q-SO4, leaving a game stays OPEN (Q-SO5), account deletion and public lobbies deferred (Q-SO6); no turn timers, ranked play, chat or uploads | Product owner (Phases 12–13 request) |
| 2026-10-22 | Q-SO6, Q-AC1, Q-AC2 | Phase 15: account deletion implemented as anonymization with social-data removal, refused during unfinished games (Q-AC1 stays OPEN); legal documents are versioned drafts accepted at sign-up or on next visit; retention and legal content are operator decisions (Q-AC2); per-account rate limits, unfinished-game cap (10) and AI queue bounds are technical limits, not game rules | Product owner (Phase 15 request) |
| 2026-10-29 | Ruleset v8 | Playtest feedback: (1) a truce restricts only initiating a war — it no longer blocks joining through a valid call to arms, defensive or offensive; (2) a declarer may invite chosen normal (full) allies to the attack, nobody is forced in, declining an invitation keeps the alliance, defensive-pact partners are not offensive allies; (3) computer nations value held territory in peace (PROVISIONAL values in `ai.json` → `peace`). New ruleset v8; v1–v7 games keep their rules (DIPLOMACY.md §6.2, §12a; AI.md §7) | Product owner (playtest feedback request) |
| 2026-11-03 | Q-MOD1, Q-T3 | Moderation tools: suspend/reinstate accounts (the API refuses a suspended account with the reason), reset offensive names, abandon games (`ABANDONED`, the operator action of Q-T3); suspended players' nations in running games are taken over by the computer. Admin page at `/admin`; every action is recorded in `moderation_action` (V24) | Product owner |
| 2026-11-02 | Naming, legal texts | The game is named **Clash of Empires**; faction display names set by the product owner (empires for the four POWER nations of the Grand Campaign; keys unchanged; V22). Legal texts version 1.0 replace the drafts: written from the application, minimum age 16, no placeholders; the contact address is configuration (`VITE_CONTACT_EMAIL`) | Product owner |
