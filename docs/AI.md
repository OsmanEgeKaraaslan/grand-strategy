# Computer opponents (Phase 11, ruleset v7)

Every nation of a v7 game that no human chose is played by the computer. Computers build, recruit,
research, move, fight, besiege, ally, call and answer calls to arms, negotiate peace and send gold —
**through the same application services, rules and command pipeline as a human**, with no
hidden bonus. All weights are PROVISIONAL and live in `rulesets/v7/ai.json`. Games of v1–v6 are
unchanged: their game-controlled nations stay the passive placeholders of DIPLOMACY §11.

The computer is conventional strategy-game AI: utility scoring, goal selection, graph search and
rule-based diplomacy over the authoritative state. No language model, no external service, no
machine learning, no randomness.

## 1. Architecture

```
game.api      ComputerSeats (seats, whose turn), TurnControl (end turn), ComputerOpponents (SPI),
              ComputerSeats.TurnPassed (event)
identity.api  ComputerPlayers (create a computer account, obtain its actor)
<module>.api  EconomyActions, MilitaryActions, SiegeActions, ResearchActions, DiplomacyActions —
              thin ports onto the existing services; the read models (…Views) moved to api
diplomacy.api ConferenceActivity (event)
ai            rules/        pure: AiState, AiAction, AiBrain, Strategy, Development, Military,
                            Statecraft, Strength, Paths, AiRules
              application/  AiTurnRunner, AiNegotiator, AiObserver, AiExecutor, AiTriggers, AiRulesets
              domain/       AiTurnRepository (ai_turn), AiDecisionLog (ai_decision)
```

`ai` is the top module: it may use every module's `api` (including diplomacy); no module uses `ai`.
The planners in `ai.rules` are pure Java (no Spring, no database, no clock, no `Random`): an
`AiState` in, an ordered list of `AiAction`s out. Equal state and rules always give equal plans.

## 2. How computer turns execute

- **Seats.** At the start of a v7 game every unchosen nation gets a computer seat after the human
  seats, in nation-key order (`game_player.computer`, nation `control = AI`). Computers therefore
  play **once per global round, after the humans**, in a fixed order; round processing (income,
  upkeep, sieges, research, growth) runs once after the last seat, for everybody alike. No extra
  round, no skipped economic cycle, no extra production.
- **Trigger.** Whenever a commit passes the turn to a computer seat (or ends a conference pause),
  `TurnPassed` is published; after the commit `AiTriggers` runs `AiTurnRunner` — on a worker thread
  (`strategy.ai.execution=async`, default) or in the triggering request, which waits for it
  (`inline`, used by the tests). A periodic sweep (`recovery-interval-ms`, 15 s) restarts games
  whose computer seat is on turn after a restart. `manual` plays nothing by itself (tests).
- **One turn** (bounded by `budget` in ai.json):
  1. observe — `AiObserver` reads the same views the nation's human would see (world snapshot,
     diplomacy, economy, military, sieges, research, the capital's institution and fortress options);
  2. plan — `AiBrain.planTurn`;
  3. execute each action through the owning module's port, in its own transaction, together with
     the turn's action counter; a battle (attack) or a war declaration is followed by a new
     observation;
  4. a refused action (the server said no) is recorded and never planned again in that turn;
  5. stop at `maxActionsPerTurn` (40), `maxObservationsPerTurn` (6) or `maxFailuresPerTurn` (10),
     then end the turn with the normal end-turn command.
- **Conferences out of turn.** `ConferenceActivity` after every conference command, and a pass
  before every computer turn, run `AiNegotiator`: each computer with conference business (answer,
  decide, revise) observes and acts, up to `maxConferencePasses`. Decisions are keyed by
  (conference, revision, nation), so a repeated pass never acts twice. A conference between two
  computers that still pauses the game when nothing more can happen is ended by its requester;
  a conference waiting for a human stays open until the human acts (no timeouts, per the rules).
  While a conference pauses the game, a computer's turn waits and resumes where it stopped.

## 3. Authorization

- A computer seat's player is a **computer account** (`player.kind = COMPUTER`) with **no Supabase
  user id** (check constraint). An authenticated request resolves players only by Supabase id, so
  no token can ever act as a computer; no web layer may obtain a computer actor (boundary test).
- `ComputerPlayers.actor(id)` hands out actors only for computer accounts. Each computer account is
  a member of exactly one game and holds one seat, so it can act only for its own AI nation, only in
  that game, and in turn only when its seat is active — every check is the existing pipeline's
  (membership, active seat, ownership of battalions, treasury, population, diplomacy, approvals).
- Human authentication and the REST layer are unchanged; there is no shortcut in any profile.

## 4. Reliability and reproducibility

- **Exactly once.** Action *n* of turn *t* uses the idempotency key derived from (game, *t*, *n*)
  and commits together with `ai_turn.actions = n + 1` (conditional on the old value). A crash
  loses at most an uncommitted action; a restarted runner continues from the committed counter;
  two runners cannot both issue action *n*. Ending the turn is the ordinary end-turn command with
  its expected turn number. Within one JVM a game is played by one thread at a time.
- **Never stuck.** Every turn ends within its budget; unexpected errors are recorded
  (`ai_turn.last_error`) and the turn still ends; inline execution runs on a separate thread so a
  failure never poisons the triggering request's persistence context.
- **Deterministic.** Planners use no randomness; ties are broken by id or key. The battle engine
  is unchanged and deterministic.
- **Explainable.** `ai_decision` keeps every major decision with its reason (e.g. "declare war on
  Frisian Free Cities: strength 337% of theirs, 26 border prize", "approve revision 2: an
  acceptable price while losing"); `ai_turn` keeps goal, actions, failures, observations and
  duration per turn; structured logs (`AiTurnRunner`, `AiNegotiator`) repeat both. Public history
  shows the ordinary events (wars, treaties, battles) — internal scores are not published.

## 5. Strategy and economy

- **Goal per turn** (`Strategy`): RECOVER (in arrears or losing money with a small treasury),
  DEFEND (at war with land occupied, the capital threatened or a stronger enemy nearby), EXPAND
  (at war otherwise), BUILDUP (a much stronger neighbour, or an aggressive personality from round
  `firstWarRound − 1`), DEVELOP otherwise. Goals change as the board changes.
- **Personalities** (ai.json): EXPANSIONIST, ECONOMIC, DEFENSIVE, DIPLOMATIC — basis-point weights
  that shift thresholds (war odds, attack odds, upkeep share, reserve, patience in war, alliance
  willingness) and the institution order. Every personality still fights, builds, researches and
  negotiates.
- **Spending**: a reserve (`reserveMg`, personality-adjusted) is never spent. Workshops, upgrades and
  repairs are ranked by payback (rounds until the extra income repays the price, from the server's
  own prices and the city's farmers) and bought while the payback fits the rounds left; at most
  `maxConstructionsPerTurn`. At war or preparing for one, the army is paid first.
- **Research**: institutions in the personality's order once income allows; points are spent on
  technologies as soon as the server allows; one doctrine chosen by the frontier (river crossings,
  mountains, enemy fortresses, army upkeep).
- **Fortresses**: the capital is fortified when threatened and the treasury is comfortable.

## 6. Military

- **Strength** is estimated as infantry-equivalent men (`unitStrength`), scaled by the nation's own
  river and mountain attack multipliers. Estimates rank options; the battle engine decides.
- **Recruitment** keeps army upkeep under the goal's share of gross income
  (`upkeepShareBps`), keeps at least `minimumBattalions`, and fills the composition the situation
  needs (artillery when enemy fortresses are in reach, some cavalry, infantry otherwise) in the city
  nearest the front. Worn battalions that stay put are reinforced.
- **Pathfinding** (`Paths`): BFS for distances, Dijkstra for routes (a river crossing or mountains
  make a step costlier, never longer). Only provinces the nation may enter are passable — own land,
  land of nations at war with it, land whose controller grants access. One move per battalion per
  round; attached battalions move with their commander.
- **Each ready stack**, in order: keep a siege going; keep a capital garrison while anyone could
  threaten it; attack an adjacent enemy force at estimated odds ≥ `attackRatioBps` (personality-
  adjusted; `riskyAttackRatioBps` for valuable objectives); else occupy an undefended enemy-held
  province (a fortified one is besieged); else fall back from a much stronger enemy; else march
  towards the best objective (enemy-held land valued by population, capitals and own occupied land;
  own threatened provinces when defending; the border of a planned target while staging). Battles
  among allies never happen: only nations at war with the commander are attacked.

## 7. Diplomacy

- **War**: a target is a neighbour at peace with no war blocker, against which the nation's strength
  ratio — counting the target's allies at `allyHelpExpectationBps` — reaches `warRatioBps`, with a
  border prize. No wars before `firstWarRound`, in the last `noNewWarsInLastRounds`, while
  recovering or defending, or beyond `maxWars`. The declaration comes only once 40% of the army
  stands at the target's border (staging), never by surprise from afar. Truces and alliances are the
  server's war blockers — a computer cannot bypass them.
- **Alliances**: a proposal is accepted against a shared threat, a common enemy, or (diplomatic
  personalities) from a neighbour — never from the planned target and never beyond `maxAlliances`.
  A threatened nation proposes a defensive pact to the strongest nation that borders the same
  threat; a refusal is not retried for `allianceRetryRounds`. An expansionist may end an alliance
  with a much weaker neighbour it means to conquer.
- **Calls to arms**: answered on the computer's turn — honoured when the coalition (the computer and
  the side it would join) reaches `honourRatioBps` of the side it would fight and it is not itself
  fighting for its life, declined otherwise (a declined defensive call ends the alliance, as the rules
  say; a declined invitation does not). As attacked leader it calls every ally. In v8 a declaring
  computer invites every normal ally that is not allied with its target, and a truce no longer makes
  it decline a call (DIPLOMACY.md §12a).
- **Peace**: a computer asks for peace when losing (score, lost provinces, or strength), when the war
  has lasted its patience, when it holds enemy land and is winning, or before the game ends while it
  holds enemy land. A non-leader that is losing asks for a separate peace. A clearly stronger side
  refuses to talk while the war is young.
- **Terms**: a winning computer demands provinces of the other side that its own side occupies,
  ceded to the occupier, more with a higher score (≤ `maxDemandedProvinces`; a capital only when that
  nation has nothing else), and a tribute with a large lead. It never offers anything belonging to
  an ally. A losing computer offers its own occupied provinces. After a rejection it concedes one
  demand per draft and ends the conference after `maxRevisionsPerConference` drafts.
- **Approvals**: every decision names the revision. Value = gains − losses in gold-equivalent
  (provinces with their people, gold, tribute, people). A losing computer accepts a price up to what
  it has already lost militarily (more when desperate), never its own elimination while it still
  holds land; a winning or clearly stronger one wants a gain unless the war has dragged on or the
  game ends soon; otherwise a fair settlement is accepted.
- **Peace in ruleset v8** (playtest feedback, 2026-10-29: computers accepted white peace too easily).
  Values in `ai.json` → `peace`; v7 games keep the rules above. The deciding nation looks at its
  **held gains** (the value of the enemy provinces it controls and would demand — exactly the terms it
  asks for), whether they are **at risk** (the enemy side stronger by `gainsAtRiskRatioBps`), whether
  it **can take more** (clearly stronger and an enemy province borders its land), and whether a
  **siege** of its is within `siegeNearlyDoneRounds` of success. Then:
  - *Losing*: unchanged — it seeks peace and accepts reasonable concessions.
  - *Holding land*: it wants at least `keepGainsShareBps` (70%) of the held value; the share drops by
    `concessionPerRoundBps` (10 points) for every round the war lasts beyond `peaceAfterRounds`, and is
    halved when the gains are at risk or the game ends soon. A white peace is therefore refused
    early and accepted once the share reaches 0 (round `peaceAfterRounds + 7` of the war at the
    latest) — no deadlock. It asks for a conference after half its patience, or at once when its
    gains are at risk, and counters the opening white peace with its demands; its own drafts are
    approved as submitted.
  - *Holding nothing, but able to advance* (stronger, land within reach or a siege about to
    succeed): it neither asks for nor accepts an empty peace until the war has lasted
    `stalemateRounds` (14) or the game nears its end; any positive offer is accepted.
  - *Otherwise* (a stalemate): a fair settlement or white peace is accepted as before.
  Deterministic tests: `PeaceEvaluationV8Test` (decisive victory, defeat, stalemate, occupation, risk,
  sieges, requests, invitations and truces).
- **Gifts**: rare — only to a much weaker partner fighting a common enemy, from a treasury above
  `giftMinTreasuryMg`, a share of the spendable gold, at most once per `giftEveryRounds` rounds per
  partner; all gift rules of the server apply.
- **Not implemented**: opinion or reputation, vassals, loans, offering own troops to allies
  (computers command battalions humans attach to them, but do not attach their own).

## 8. Performance (measured)

Scenario A (2 humans, 8 computers, Grand Campaign, all 30 rounds, development machine, PostgreSQL in
Testcontainers, inline execution): 240 computer turns, **average 531 ms, maximum 1.5 s** per turn,
612 commands, 2 refusals; a full round (8 computers plus round processing) took **4–9 s**. Each
observation is eight reads; map topology is cached; there is no per-candidate database access.

## 9. Known limitations of the first version

- One difficulty. Decisions are greedy for one turn with simple staging; there is no multi-turn
  campaign plan, no feints, no coordination between computer allies beyond fighting the same enemy.
- Computers pile on the weakest neighbour; a passive human gets attacked by several of them.
- No opinion or memory beyond what the board shows (a declined call is not remembered; an ex-ally
  may attack later).
- Terms never include gold reparations or population transfers (treasuries of others are not
  visible); tribute only with a large lead.
- Computers do not attach their own battalions to allies.
- Balance and pacing are unvalidated by a human playtest (OPEN_QUESTIONS Q-AI1).

## 10. Manual test (2 humans, 8 computers)

See README "Manual test with computer opponents".
