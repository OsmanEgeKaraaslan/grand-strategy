# API Design

REST over HTTPS, JSON, versioned under `/api/v1`. Documented with OpenAPI
(springdoc or equivalent compatible with Spring Boot 4), from which the frontend
generates its TypeScript types.

## 1. Conventions

| Topic | Convention |
|---|---|
| Base path | `/api/v1` |
| Auth | `Authorization: Bearer <Supabase access token>` on every endpoint except `GET /api/v1/health` and public unsubscribe |
| Content | `application/json`; errors `application/problem+json` (RFC 9457) |
| Naming | camelCase JSON; plural resource nouns; ids are UUID strings |
| Money | integer milligold, field suffix `Mg` (`treasuryMg: 36500` = 36.5 gold) |
| Population | integer persons |
| Commands | `POST` to an action/sub-resource; **`Idempotency-Key: <uuid>` required** |
| Turn token | `expectedTurnNumber` in body: required for end turn, optional elsewhere |
| Concurrency/caching | `ETag: "<stateVersion>"` on game reads; `If-None-Match` → `304` |
| Pagination | `?limit=&cursor=` (opaque cursor) for lists that grow (games, events, reports) |
| Unknown JSON fields | Rejected on command bodies (strict), ignored nowhere silently |
| Time | ISO-8601 UTC strings |

## 2. Endpoints

### 2.1 Identity (Phase 2 — implemented)

| Method | Path | Purpose | Status |
|---|---|---|---|
| GET | `/me` | Current player's profile. **First call provisions the player** (race-free `INSERT … ON CONFLICT DO NOTHING` keyed by the token `sub`); the sign-up `user_metadata.display_name` is used when valid and free, otherwise a generated `Commander-XXXXXX` | Implemented |
| PATCH | `/me` | Any of `{displayName, username, emblem, matchHistoryPublic}` (at least one) — change your own account. 409 `DISPLAY_NAME_TAKEN` / `USERNAME_TAKEN`, 422 `INVALID_DISPLAY_NAME` / `INVALID_USERNAME` / `USERNAME_RESERVED` / `INVALID_EMBLEM` | Implemented (Phase 12A) |
| GET | `/profiles/{username}` | Public profile with statistics (see §2.8) | Implemented (Phase 12A) |
| GET / PUT | `/me/communication-preferences` | Email opt-ins | Phase 12 (unscheduled) |
| POST | `/me/terms-acceptance` | `{termsVersion, privacyVersion}` — accept the current legal documents (must equal the server's versions, else 409 `TERMS_VERSION_OUTDATED`); idempotent; history in `terms_acceptance` | Implemented (Phase 15A) |
| POST | `/me/deletion` | `{confirmation: "DELETE"}` — delete (anonymize) the account. Needs a password sign-in within 10 min (`amr` claim, else 403 `REAUTHENTICATION_REQUIRED`); 409 `ACCOUNT_DELETION_BLOCKED` while seated in an unfinished game; returns `{deletedAt}`. Afterwards every request of the account gets 403 `ACCOUNT_DELETED` | Implemented (Phase 15A) |

`GET /me` also returns `terms: {currentTermsVersion, currentPrivacyVersion, accepted, acceptedTermsVersion,
acceptedPrivacyVersion, acceptedAt}`. With `strategy.legal.require-acceptance` (default on) every
endpoint except `/me` and its sub-paths answers 403 `TERMS_NOT_ACCEPTED` until the current versions are
accepted (Phase 15A). Passwords, email confirmation and recovery are Supabase Auth calls made by the
frontend; the API has no credential endpoints.

There is no endpoint that addresses a player by id: a player can only read or change their own
profile. Every authenticated endpoint resolves the acting player from the verified token only.

```json
GET /api/v1/me  →  200
{ "id": "6f1c…", "displayName": "Queen of Varn", "createdAt": "2026-10-17T19:40:15Z", "updatedAt": "2026-10-17T19:40:15Z" }
```

### 2.2 Maps (Phase 3 — implemented)

| Method | Path | Purpose |
|---|---|---|
| GET | `/maps/{mapId}` | Static template of one map version: provinces (key, name, terrain, city name, `svgPath`, label anchor, neighbours with crossing type), rivers (`svgPath`), nations (key, name, colour, capital, starting provinces). `Cache-Control: private, max-age=86400` — a map id names an immutable version. 404 `MAP_NOT_FOUND` |

Any authenticated player may read map templates; they contain no per-game state. A
`GET /maps` catalogue is not needed while a single map exists.

### 2.3 Lobby (Phase 2 — implemented)

Phase 2 lobbies are private and two-player; the invitation code is the only way in.

| Method | Path | Body | Result | Authorization |
|---|---|---|---|---|
| POST | `/games` | `{name}` (3–40 chars) | `201` + `Location`, lobby (creator in seat 0) | any player |
| GET | `/games` | — | games **you belong to**, newest first; no invite codes | any player |
| GET | `/games/{gameId}` | — | lobby: status, slots, participants, **invite code** | **members only**; everyone else gets `404 GAME_NOT_FOUND` (indistinguishable from a nonexistent game) |
| POST | `/games/join` | `{inviteCode}` (case/dashes/spaces ignored) | `200` lobby | any player; `404 INVITE_CODE_NOT_FOUND`, `409 GAME_FULL`; joining a game you are already in returns it unchanged (no duplicate) |

Lobby response (`GET /games/{id}`, create, join):

```json
{
  "id": "0d3c…", "name": "Twin Shores", "status": "WAITING_FOR_PLAYERS",
  "maxPlayers": 2, "playerCount": 1, "availableSlots": 1,
  "inviteCode": "K7QM-2XRP-H9TD", "yourSeat": 0, "youAreHost": true,
  "participants": [ { "seat": 0, "displayName": "Queen of Varn", "host": true, "you": true, "joinedAt": "…" } ],
  "createdAt": "…"
}
```

Status values: `WAITING_FOR_PLAYERS` (a slot is free) → `READY` (all slots taken). Joins lock
the game row (`SELECT … FOR UPDATE`), so two players racing for the last slot cannot both
succeed; database constraints (`player_count <= max_players`, unique `(game_id, player_id)` and
`(game_id, seat_order)`) back this up. Clients poll `GET /games/{id}` (the frontend every 3 s
while waiting); no WebSockets.

Invitation codes: 12 characters from a 32-symbol alphabet without look-alikes (60 bits,
`SecureRandom`), displayed `XXXX-XXXX-XXXX`, shown only to members.


#### Nation choice and start (Phase 3 — implemented)

| Method | Path | Body | Result | Authorization / errors |
|---|---|---|---|---|
| PUT | `/games/{gameId}/players/me/nation` | `{nationId}` | `200` lobby | member; before the start only (409 `GAME_ALREADY_STARTED`); 409 `NATION_TAKEN`; 422 `NATION_NOT_FOUND` (not on this map). Re-choosing your own nation is a no-op; changing it is allowed |
| POST | `/games/{gameId}/start` | — | `200` lobby (`IN_PROGRESS`) | members only; host only (403 `NOT_HOST`); 409 `GAME_NOT_READY` (slots free), 409 `NATIONS_NOT_SELECTED`. Repeating a successful start returns the started game unchanged |

The lobby response now also carries `mapId`, `mapName`, `nations[]` (`id`, `key`, `name`,
`color`, `provinceCount`, `chosenBySeat`, `chosenByYou`), `participants[].nationId`,
`canStart` (true only for the host when everyone has a distinct nation) and `stateVersion`.
Clients keep polling the lobby until `status` becomes `IN_PROGRESS`.

Start rule for the MVP: every player has a distinct nation **and** every nation of the map
is played (there is no neutral land, Q-W4). Starting runs in one transaction under the game
row lock: world instantiation (nations, provinces with owner = controller, cities) and the
switch to `IN_PROGRESS` with the host (seat 0) active in round 1.

### 2.4 Game state (Phase 3+)

| Method | Path | Purpose |
|---|---|---|
| GET | `/games/{gameId}/state` | **Implemented (Phase 3).** Members only (404 otherwise), started games only (409 `GAME_NOT_IN_PROGRESS`). Turn state (`round`, `activeSeat`, `turnNumber`, `stateVersion`, `yourSeat`, `yourTurn`), players (seat, display name, host, you, nationId), nations (id, name, colour, seat), provinces (`key`, `ownerNationId`, `controllerNationId`, `city {name, population}`). `ETag: "<stateVersion>"` with `Cache-Control: private, no-cache`; `If-None-Match` → `304`. Later phases add treasury, buildings, armies, sieges |
| GET | `/games/{gameId}/summary` | Not needed so far: the ETag makes unchanged `/state` polls an empty 304 |
| GET | `/games/{gameId}/cities/{cityId}/economy` | Employment breakdown, income, projected income (Phase 4) |
| GET | `/games/{gameId}/nations/me/budget` | Income/upkeep projection for this round (Phase 4/5) |
| GET | `/games/{gameId}/events?afterId=&limit=` | History feed |
| GET | `/games/{gameId}/rounds/{round}` | Round report |
| GET | `/games/{gameId}/battles?round=` / `/battles/{battleId}` | Battle reports |

All reads go through a viewer-aware query service so fog of war can be added
without changing endpoints (Q-T4).

### 2.4a Economy (Phase 4 — implemented)

All amounts are integer milligold (`…Mg`, 1 gold = 1000 mg), computed by the server from the
game's ruleset. Members of a started game only (404 for others, 409 `GAME_NOT_IN_PROGRESS`).

| Method | Path | Result |
|---|---|---|
| GET | `/games/{gameId}/economy` | `round`, `yourTurn`, `rules` (agriculture rate, workshop capacity, max workshops per city, tiers with rate/cost/duration/constructible), `nation` (**caller's only**: `treasuryMg`, `inArrears`, `projectedGrossIncomeMg`, `projectedUpkeepMg`, `projectedNetMg`, `lastRound`), `cities[]` (every city — no fog of war) |
| GET | `/games/{gameId}/cities/{cityId}/economy` | One city: population, owner, controller, `employment` (farmers, Tier 1, Tier 2 workers), `potentialIncome` (per source + total), `collectibleIncomeMg`, `incomeStatus` (`COLLECTIBLE`/`OCCUPIED`), `workshops[]` (id, tier, status, `completesAfterRound`, `operationalFromRound`), `workshopCount`, and `construction` for the caller (`costMg`, `durationRounds`, `completesAfterRound`, `operationalFromRound`, `allowed`, `blockers[]`) |
| POST | `/games/{gameId}/cities/{cityId}/workshops` | Body `{tier}` (only 1 is buildable). `Idempotency-Key` required. `201` → `{workshop, treasuryMg, city}`. Runs through the command pipeline (game lock, active player); price and timing come from the ruleset, never from the client. A retry with the same key returns the same workshop without paying again |

Construction errors: 409 `NOT_ACTIVE_PLAYER`, 403 `NOT_OWNER` (not the legal owner, e.g. only
occupying), 409 `PROVINCE_OCCUPIED`, 422 `TIER_LOCKED`, 422 `WORKSHOP_LIMIT_REACHED`, 422
`IN_ARREARS`, 422 `INSUFFICIENT_GOLD`, 404 `CITY_NOT_FOUND` (also for another game's city),
422 `IDEMPOTENCY_KEY_REUSED` (key used for a different command).

The game state's `provinces[].city` now also carries the city `id`. Clients key the economy
query by the game's `stateVersion`, so it refreshes whenever the polled state changes.

### 2.4e Research, institutions, Tier 2 and growth (Phase 8 — implemented)

| Method | Path | Result |
|---|---|---|
| GET | `/games/{gameId}/research` | **Caller's nation only** (other nations' research is private): `available` (false before v4), `pools[]` (points, `perRound` from institutions producing now, `lastRound`), `military` and `civilian` tracks (level, max, every level with cost and effects, `next` quote with `allowed`/`blockers`), `doctrines[]` (tier, cost, modifiers in bps, `chosen`, `allowed`, `blockers`), the caller's `institutions[]`, `effects` (the modifiers the engines apply now) |
| GET | `/games/{gameId}/cities/{cityId}/institutions` | Per institution type: cost, duration, completion and first producing round, points per round, upkeep, the caller's `allowed`/`blockers`, and the existing institution of that type |
| POST | `/games/{gameId}/cities/{cityId}/institutions` | Body `{type}`. `201` → `{institution, costMg, treasuryMg}` |
| POST | `/games/{gameId}/research/technologies` | Body `{track: MILITARY\|CIVILIAN, targetLevel}` (must be the next level). `200` → `{kind, subject, level, costPoints, research}` |
| POST | `/games/{gameId}/research/doctrines` | Body `{doctrine}`. `200` → same shape |
| POST | `/games/{gameId}/cities/{cityId}/workshops/{buildingId}/upgrade` | No body. `200` → `{workshop, costMg, treasuryMg}` |

City economy views add `workshops[].upgrade` (quote incl. `potentialIncomeAfterMg`),
`upgradeTargetTier`, `upgradeCompletesAfterRound`, and `growth` (`eligible`, `rateBps`,
`nextRoundGrowth`, `blockers`). The military overview adds `modifiers` (military level, doctrines,
damage bonus per unit type, base and effective river/mountain attack multipliers, upkeep reduction,
siege artillery bonus); siege summaries add `artilleryBonusBps`; battle reports add
`details.attackerModifiers`/`defenderModifiers`.

Errors: 409 `NOT_ACTIVE_PLAYER`, 403 `NOT_OWNER`, 409 `PROVINCE_OCCUPIED`, 409 `CITY_BESIEGED`, 409
`INSTITUTION_EXISTS`, 422 `IN_ARREARS`, 422 `INSUFFICIENT_GOLD`, 422 `INSUFFICIENT_RESEARCH_POINTS`,
409 `STALE_TECHNOLOGY_LEVEL`, 422 `MAX_LEVEL_REACHED`, 409 `DOCTRINE_ALREADY_CHOSEN`, 409
`DOCTRINE_TIER_FULL`, 404 `DOCTRINE_NOT_FOUND`, 422 `TECHNOLOGY_REQUIRED`, 422
`WORKSHOP_NOT_UPGRADABLE`, 422 `NOT_AVAILABLE_IN_RULESET`, 404 `CITY_NOT_FOUND` (also another game's),
404 `BUILDING_NOT_FOUND`, 400 `VALIDATION_FAILED`. All commands need `Idempotency-Key`.

### 2.4f Diplomacy, peace and results (Phase 9 — implemented)

Rules: [DIPLOMACY.md](DIPLOMACY.md). Base path `/games/{gameId}/diplomacy`. Members of a started
(or finished) game only: 401 without a token, 404 `GAME_NOT_FOUND` for non-members. Games of
rulesets before v5: the overview says `available: false`, commands answer 409
`DIPLOMACY_NOT_AVAILABLE`. Bodies carry intents only — war score, treasuries, ownership,
populations, settlement and tribute state are always computed by the server. Every command needs
`Idempotency-Key` and runs through the command pipeline (active player only).

| Method | Path | Result |
|---|---|---|
| GET | `/diplomacy` | `available`, `round`, `yourTurn`, `yourNationId`, `rules` (truce, expiry, term limits, tribute rate and rounds, population limits, reparations maximum, victory values, access flags), `nations[]` (the caller's standing with each: `status` SELF/WAR/ALLIED/PEACE, `allianceKind`, `allianceId`, `warId`, `truceUntilRound`, `weMayEnter`, `theyMayEnter`, `warBlockers[]`, `allianceBlockers[]`), `wars[]` (public: participants with side and join reason, rounds, `durationRounds`, `score {attacker, defender: {battles, sieges, occupation, total, battlesWon, siegesWon, occupiedEnemyPopulation, enemyPopulation}, net, maxScore}`, `yourSide`, `youLead`), `alliances[]` (active and ended ones are public; proposals only for their two nations; `canAccept`, `canEnd`, `endBlockers`), `proposals[]` (**only the caller's**, sent and received, with current `violations[]`), `treaties[]`, `tributes[]` (with every payment), `events[]` (public history, newest first) |
| GET | `/diplomacy/wars/{warId}` | `{war, scoreEvents[] (kind, side, source id, round, enemy losses, points), history[]}` |
| POST | `/diplomacy/wars` | `{targetNationId}` → `201 {war, joinedDefenders[]}`. 422 `CANNOT_TARGET_SELF`, 404 `NATION_NOT_FOUND`, 409 `ALREADY_AT_WAR`, 409 `ALLIED_NATION`, 409 `TRUCE_IN_EFFECT` |
| POST | `/diplomacy/alliances` | `{nationId, kind: NORMAL\|DEFENSIVE}` → `201 {alliance, withdrawals[]}`. 409 `AT_WAR`, `ALREADY_ALLIED`, `ALLIANCE_ALREADY_PROPOSED`, `ALLIANCE_CONFLICT` |
| POST | `/diplomacy/alliances/{id}/accept` \| `reject` | Recipient only (403 `NOT_ALLIANCE_RECIPIENT`); 409 `ALLIANCE_NOT_PENDING` |
| POST | `/diplomacy/alliances/{id}/terminate` | Ends an active alliance (409 `ALLIES_AT_WAR_TOGETHER`, `ALLIANCE_NOT_ACTIVE`) or withdraws one's own proposal (403 `NOT_ALLIANCE_PROPOSER`); returns the withdrawals it caused |
| GET | `/diplomacy/peace-proposals` \| `/{id}` | The caller's proposals only; a third nation's request is a 404 `PEACE_PROPOSAL_NOT_FOUND` |
| POST | `/diplomacy/peace-proposals` | `{warId, terms[]}`; a term is `{type: PROVINCE\|GOLD\|TRIBUTE\|POPULATION, fromNationId, toNationId, provinceKey?, amountMg?, sourceCityId?, destinationCityId?, people?}` (only the fields of its type are used; tribute rate and duration cannot be chosen) → `201 {proposal}`. 403 `NOT_WAR_LEADER`, 409 `WAR_NOT_ACTIVE`, 422 `PEACE_TERMS_INVALID` (detail lists every violation), 400 `INVALID_TERM`, 400 `VALIDATION_FAILED` (unknown type) |
| POST | `/diplomacy/peace-proposals/check` | Same body; validation only, nothing changes → `{valid, violations[{term, code}]}` |
| POST | `/diplomacy/peace-proposals/{id}/accept` | Recipient only (403 `NOT_PROPOSAL_RECIPIENT`). Settles atomically → `{treaty, war, withdrawals[], controlReturned[]}`. 409 `PEACE_TERMS_NO_LONGER_VALID` (nothing changed), 409 `PROPOSAL_NOT_PENDING`. A retry with the same key replays the treaty without paying again |
| POST | `/diplomacy/peace-proposals/{id}/reject` \| `cancel` | Recipient rejects; proposer cancels (403 `NOT_PROPOSAL_PROPOSER`) |
| POST | `/diplomacy/peace-proposals/{id}/counter` | `{terms[]}` → `201 {proposal}`; the original becomes SUPERSEDED |
| GET | `/diplomacy/treaties`, `/diplomacy/tributes`, `/diplomacy/events` | Public lists |
| GET | `/games/{gameId}/result` | Finished games: `{finishedRound, reason, outcome WINNER\|DRAW, winnerNationId, winnerName, rulesetVersion, finishedAt, standings[] (rank, nation, player, you, score, provinces, population, treasury), statistics}`; 409 `GAME_NOT_FINISHED` before the end |

Movement (`POST /moves`) adds 422 `NO_MILITARY_ACCESS` (neutral territory) and 422
`COALITION_BATTLE_UNSUPPORTED`; province move targets add `accessible`, and `hostile` now means
"battalions of a nation at war with you". End turn adds `finished` to its response. After a game
has finished every command answers 409 `GAME_FINISHED`; game state shows `status: FINISHED`.

### 2.4g Maps, gifts, coalitions and peace conferences (Phase 10 — implemented)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/maps` | Published maps: key, name, max players, nations, playable nations |
| POST | `/api/v1/games` | Body gains `maxPlayers` (2 – map maximum) and `mapKey` (default `grand-campaign`) |
| POST | `/games/{id}/diplomacy/gifts` | `{recipientNationId, amountMg}` → 201 `{gift, treasuryMg}`; on your turn |
| POST | `/games/{id}/attachments` | `{battalionIds, commanderNationId}`; on your turn, owner only |
| POST | `/games/{id}/attachments/release` | `{battalionIds}`; on your turn, owner only |
| POST | `/games/{id}/diplomacy/wars/{warId}/calls` | `{allyNationId}` → 201 `{call}`; attacked leader, on its turn |
| POST | `/games/{id}/diplomacy/calls/{callId}/answer` | `{accept}` (true = honour); the called ally, on its turn |
| GET | `/games/{id}/diplomacy/conferences/{conferenceId}` | Conference view (members) |
| POST | `/games/{id}/diplomacy/wars/{warId}/conferences` | `{kind: GENERAL\|SEPARATE}` → 201 `{conference, treaty, withdrawals}`; out of turn |
| POST | `/games/{id}/diplomacy/conferences/{conferenceId}/answer` | `{accept}`; the counterpart, out of turn |
| POST | `/games/{id}/diplomacy/conferences/{conferenceId}/cancel` | Either principal, out of turn |
| POST | `/games/{id}/diplomacy/conferences/{conferenceId}/drafts` | `{expectedRevision, terms}` → 201; a principal, out of turn |
| POST | `/games/{id}/diplomacy/conferences/{conferenceId}/revisions/{n}/approve` | A required nation; the last approval settles |
| POST | `/games/{id}/diplomacy/conferences/{conferenceId}/revisions/{n}/reject` | A required nation |

All commands need `Idempotency-Key`. The diplomacy overview gains `gifts`, `calls` and
`conferences`; war participants gain `leftRound`/`leftReason`; the rules view gains the gift
limits, `features`, `victoryPointsPerHundredGold` and `domination`. The game state gains
`pausedConferenceId`, `youAreEliminated` and per nation `control`, `eliminatedRound` and a nullable
`seat`. Battalions gain `commanderNationId`; battle details gain `nations` (one line per nation) and
participants a `nationId`. Ruleset v6 answers the Phase 9 proposal endpoints with
`409 USE_PEACE_CONFERENCE`; older games answer the new endpoints with `409 CONFERENCES_NOT_AVAILABLE`,
`CALLS_TO_ARMS_NOT_AVAILABLE` or `ATTACHMENT_NOT_AVAILABLE`.

New stable error codes: `GAME_PAUSED_FOR_CONFERENCE`, `NATION_ELIMINATED`, `NATION_NOT_PLAYABLE`,
`INVALID_PLAYER_COUNT`, `MAP_NOT_FOUND`, `NPC_NOT_NEGOTIABLE`, `INVALID_GIFT_AMOUNT`, `GIFT_TO_ENEMY`,
`GIFTS_NOT_AVAILABLE`, `NOT_DEFENDING_LEADER`, `NOT_ALLIED`, `ALREADY_IN_WAR`, `CALL_ALREADY_PENDING`,
`NOT_CALLED_NATION`, `CALL_NOT_PENDING`, `ALLIED_WITH_ENEMY`, `CONFERENCE_IN_PROGRESS`,
`CONFERENCE_ALREADY_REQUESTED`, `CONFERENCE_NOT_OPEN`, `NOT_CONFERENCE_COUNTERPART`,
`NOT_CONFERENCE_PRINCIPAL`, `LEADER_CANNOT_LEAVE`, `STALE_REVISION`, `APPROVAL_NOT_REQUIRED`,
`ALREADY_DECIDED`, `ATTACHMENT_NOT_ALLOWED`, `BATTALION_ATTACHED`, `BATTALION_NOT_ATTACHED`,
`ATTACHED_NOT_AT_WAR`.

### 2.4h Computer opponents (Phase 11)

No new endpoints. The game state's `players[]` gain `computer` (computer seats, display name
"Computer") and `nations[].control` may be `AI`; lobby participants never include computer seats.
Computers act through the same services as the endpoints (ports in each module's `api`); see
[AI.md](AI.md).

### 2.4d Fortifications and sieges (Phase 7 — implemented)

Members of a started game only. No fog of war: every member sees all fortresses and sieges.

| Method | Path | Result |
|---|---|---|
| GET | `/games/{gameId}/sieges` | `fortificationsAvailable` (false for pre-v3 games), `fortifications[]` (province, city, `level`, `effectiveLevel`, `status`, `integrity`/`maxIntegrity`, `project`), `activeSieges[]` and `recentSieges[]` (20) as summaries: besieger, defender, status, rounds, `requiredProgress`, `progress`, and for active sieges the server's `ratePerRound`, `artilleryManpower`, `estimatedRoundsLeft`, `nextAttrition`; `populationLost`, `artillerySiege`, `damagedBuildingId` |
| GET | `/games/{gameId}/sieges/{siegeId}` | Summary + `rounds[]` (artillery, progress added/after, population before/lost per round) |
| GET | `/games/{gameId}/provinces/{provinceKey}/fortification` | City, owner, controller, fortress, active siege, and the caller's `upgrade` and `repair` quotes (`targetLevel`, `costMg`, `durationRounds`, `completesAfterRound`, `operationalFromRound`, `allowed`, `blockers[]`) |
| POST | `/games/{gameId}/cities/{cityId}/fortress` | Body `{targetLevel}` (1–10, must be the next level). `201` → `{fortification, costMg, treasuryMg}` |
| POST | `/games/{gameId}/cities/{cityId}/fortress/repair` | No body. `201` → same shape |
| POST | `/games/{gameId}/cities/{cityId}/workshops/{buildingId}/repair` | No body. `200` → `{workshop, costMg, treasuryMg}`; city views carry `workshops[].repair` quotes and `repairCompletesAfterRound` |

Commands need `Idempotency-Key`. Errors: 409 `NOT_ACTIVE_PLAYER`, 403 `NOT_OWNER`, 409
`PROVINCE_OCCUPIED`, 409 `CITY_BESIEGED` (also for recruit, reinforce and workshop construction in a
besieged city), 409 `FORTRESS_PROJECT_IN_PROGRESS`, 409 `STALE_FORTRESS_LEVEL`, 422
`FORTRESS_DAMAGED`, 422 `FORTRESS_NOT_DAMAGED`, 422 `BUILDING_NOT_DAMAGED`, 422 `MAX_LEVEL_REACHED`,
422 `NOT_AVAILABLE_IN_RULESET`, 422 `IN_ARREARS`, 422 `INSUFFICIENT_GOLD`, 404 `CITY_NOT_FOUND`
(also another game's), 404 `FORTRESS_NOT_FOUND`, 404 `BUILDING_NOT_FOUND`, 404 `SIEGE_NOT_FOUND`,
404 `PROVINCE_NOT_FOUND`, 409 `PROVINCE_BESIEGED` (disband inside a siege), 400 `VALIDATION_FAILED`.
Sieges are never started or advanced by an endpoint: moves start and lift them, round ends advance
them.

### 2.4c Reinforcement and disbanding (Phase 6 — implemented)

| Method | Path | Result |
|---|---|---|
| GET | `/games/{gameId}/battalions/{battalionId}` | Caller's own battalion only (403 `NOT_YOUR_BATTALION` otherwise): `battalion`, `cohorts[]` (origin city, province, soldiers), `reinforcement` (local city and its population, `missing`, `turnAllowance`, `maxSoldiers`, `recruitCostMg`, `battalionManpower`, `costOfMaxMg`, `upkeepAfterMaxMg`, `allowed`, `blockers[]`), `disband` (`allowed`, `blockers[]`, `soldiers`, `returns[]` per city with `fallback`, `upkeepSavedMg`) |
| POST | `/games/{gameId}/battalions/{battalionId}/reinforcements` | Body `{soldiers}` (1–1000). `201` → `{battalion, soldiersAdded, costMg, treasuryMg, cityId, cityPopulation}` |
| POST | `/games/{gameId}/battalions/{battalionId}/disband` | Body `{}`. `200` → `{battalionId, unitType, soldiersReturned, returns[], upkeepSavedMg}` |

Both commands need `Idempotency-Key`; a retry returns the recorded result (stored in
`manpower_transfer`). Errors: 409 `NOT_ACTIVE_PLAYER`, 403 `NOT_YOUR_BATTALION`, 404
`BATTALION_NOT_FOUND` (also another game's or a destroyed one), 409 `BATTALION_DISBANDED`, 422
`BATTALION_AT_FULL_STRENGTH`, 422 `REINFORCEMENT_EXCEEDS_MAXIMUM`, 422
`REINFORCEMENT_LIMIT_REACHED` (500 per turn), 422 `INVALID_REINFORCEMENT_LOCATION` (city not owned
and controlled), 422 `IN_ARREARS`, 422 `INSUFFICIENT_POPULATION`, 422 `INSUFFICIENT_GOLD`, 422
`NOT_FRIENDLY_TERRITORY` (disband), 422 `IDEMPOTENCY_KEY_REUSED`, 400 `VALIDATION_FAILED`. Moves
also return 422 `BATTALION_REINFORCED_THIS_TURN`. Battle reports gain `details.attackerBroke` and
`details.defenderBroke` (null in reports stored before Phase 6).

### 2.4b Military (Phase 5 — implemented)

Members of a started game only (404 for others, 401 without a token). Request bodies carry
intents only; strength, costs, eligibility and battle results are decided by the server.

| Method | Path | Result |
|---|---|---|
| GET | `/games/{gameId}/military` | `round`, `turnNumber`, `yourTurn`, `nationId`, `rules` (unit costs and upkeep, battalion size, minimum residual population), `battalions[]` (**all** — no fog of war: id, nation, type, manpower/max, `provinceKey`, `armyId`, origin city, `eligibility` READY / RECRUITED_THIS_TURN / ALREADY_MOVED for the caller's own, null for others, `upkeepMg`), `armies[]`, `upkeep` (caller's total, per type, battalions, soldiers), `recruitment[]` (caller's own cities: per unit type cost, upkeep, civilians taken, population after, `allowed`, `blockers[]`), `recentBattles[]` (10) |
| GET | `/games/{gameId}/provinces/{provinceKey}/military` | Battalions in the province, owner/controller/`occupied`, and `moveTargets[]` (adjacent provinces with terrain, river, controller, hostile battalion count) |
| GET | `/games/{gameId}/battles` | The 50 most recent battle summaries |
| GET | `/games/{gameId}/battles/{battleId}` | Full report: conditions, participants (before / losses / after / fate), every phase (protection, artillery effectiveness, flank, terrain multiplier, damage, losses by type), outcome, retreat |
| POST | `/games/{gameId}/cities/{cityId}/recruitments` | Body `{unitType}`. `201` → `{battalion, treasuryMg, cityPopulation}` |
| POST | `/games/{gameId}/moves` | Body `{battalionIds[1..100], toProvinceKey}`. `200` → `{outcome: MOVED / ATTACK_WON / ATTACK_REPELLED, toProvinceKey, battalions[], battle}` |
| POST | `/games/{gameId}/armies` | Body `{name, battalionIds[]}` — forms a new army from co-located own battalions (split and merge in one command); emptied armies are deleted. `201` → `{army}` |

Commands require `Idempotency-Key` (400 `IDEMPOTENCY_KEY_MISSING`); a retry with the same key
returns the original result without acting again. Errors: 409 `NOT_ACTIVE_PLAYER`, 403
`NOT_OWNER`, 409 `PROVINCE_OCCUPIED`, 422 `IN_ARREARS`, 422 `INSUFFICIENT_POPULATION`, 422
`INSUFFICIENT_GOLD`, 404 `CITY_NOT_FOUND`, 404 `BATTALION_NOT_FOUND` (also another game's), 403
`NOT_YOUR_BATTALION`, 422 `BATTALIONS_NOT_COLOCATED`, 422 `BATTALION_RECRUITED_THIS_TURN`, 422
`BATTALION_ALREADY_MOVED` (one ineligible battalion rejects the whole selection), 422
`NOT_ADJACENT`, 404 `PROVINCE_NOT_FOUND`, 404 `BATTLE_NOT_FOUND`, 400 `VALIDATION_FAILED`.

Differences from the §2.5 sketch, deliberately smaller for Phase 5: one battalion per
recruitment (no `count`/`armyId`), provinces addressed by key, no `expectedTurnNumber` on moves
(eligibility already makes a stale move harmless), no rename/disband/reinforce endpoints.

### 2.5 Gameplay commands

All require: member, `IN_PROGRESS`, active player, `Idempotency-Key`.

| Method | Path | Body | Phase |
|---|---|---|---|
| POST | `/games/{gameId}/turn/end` | `{expectedTurnNumber}` (required) + `Idempotency-Key` header (required, UUID; 400 `IDEMPOTENCY_KEY_MISSING`). **Implemented.** Response `{round, activeSeat, turnNumber, stateVersion, roundCompleted, replayed, finished}`; a retry with the same key returns `replayed: true` without acting again. 409 `NOT_ACTIVE_PLAYER`, 409 `TURN_CHANGED`, 409 `GAME_NOT_IN_PROGRESS` | 3 |
| POST | `/games/{gameId}/cities/{cityId}/workshops` | `{tier}` — **implemented**, see §2.4a. Fortresses and institutions get their own endpoints in later phases | 4 |
| POST | `/games/{gameId}/buildings/{buildingId}/upgrade` | `{}` | 4 (gated by tech) |
| POST | `/games/{gameId}/buildings/{buildingId}/repair` | `{}` | 7 |
| POST | `/games/{gameId}/cities/{cityId}/recruitments` | `{unitType, count, armyId?}` | 5 |
| POST | `/games/{gameId}/moves` | `{battalionIds[], toProvinceId, targetArmyId?, newArmyName?, expectedTurnNumber?}` — all battalions must be the actor's, in one province, and individually eligible | 5 |
| POST | `/games/{gameId}/armies` | `{provinceId, name, battalionIds[]}` — create an army from battalions in one province | 5 |
| PATCH | `/games/{gameId}/armies/{armyId}` | `{name}` | 5 |
| POST | `/games/{gameId}/armies/{armyId}/battalions` | `{battalionIds[]}` — regroup battalions (same province) into this army; never changes movement eligibility | 5 |
| POST | `/games/{gameId}/battalions/{battalionId}/disband` | `{}` | 5 |
| POST | `/games/{gameId}/battalions/{battalionId}/reinforce` | `{men}` | 6 |
| PUT | `/games/{gameId}/research` | `{track, …}` | 8 |
| POST | `/games/{gameId}/diplomacy/…` | War, alliance and peace commands — **implemented**, see §2.4f | 9 |

Resource-oriented URLs are mapped by thin controllers onto sealed `GameCommand`
records handled by the single command pipeline (see TURN_SYSTEM §4).

**Command response** (`200 OK`):

```json
{
  "commandId": "0192f7c4-…",
  "stateVersion": 418,
  "turnNumber": 37,
  "result": { "type": "MOVE_RESULT", "battleId": "…", "outcome": "ATTACKER_WON" },
  "changed": { "armies": ["…"], "provinces": ["…"], "nations": ["…"] }
}
```

The client invalidates its cached state on `stateVersion` change (or applies the
included changed resources directly in later optimisation).

**Example — move:**

```http
POST /api/v1/games/7a1…/moves
Authorization: Bearer eyJ…
Idempotency-Key: 4f8e2a8a-6c1c-4f0e-9f1e-3e2d5b7c9a10
Content-Type: application/json

{ "battalionIds": ["b01…", "b02…"], "toProvinceId": "e55…", "expectedTurnNumber": 37 }
```

### 2.6 Communication (unscheduled; still deferred after Phases 12–13: in-app notifications only, see §2.8)

| Method | Path | Notes |
|---|---|---|
| GET/POST | `/public/unsubscribe?token=` | No auth; signed token; supports RFC 8058 one-click `POST` |
| POST | `/webhooks/email/{provider}` | Provider delivery events; verified by provider signature |

### 2.8 Profiles, friends, invitations, notifications, Quick Match (Phases 12–13 — implemented)

Every endpoint acts for the authenticated caller only; other players are addressed by **username**,
never by an id taken from the request. Players' internal ids, emails and Supabase ids are never
returned. These are plain idempotent state transitions (no `Idempotency-Key`): repeating one after a
lost response returns the same outcome. Inputs are bounded (usernames ≤ 32 characters in requests,
search ≤ 32, lists ≤ 100 ids).

| Method | Path | Purpose |
|---|---|---|
| GET | `/profiles/{username}` | `{username, displayName, emblem, memberSince, you, relation, matchHistoryPublic, statistics{completed, wins, losses, draws, winRateBps, favouriteFaction, factions[]}, recentMatches[] or null}`; `recentMatches[].gameId` only on your own profile. 404 `PLAYER_NOT_FOUND` (also computer accounts) |
| GET | `/players/search?q=` | Exact handle first, then handles starting with `q` (≥ 3 characters), at most 10: `[{username, displayName, emblem, relation}]` |
| GET | `/friends` | `{friends[], incoming[], outgoing[], blocked[]}` |
| POST | `/friends/requests` | `{username}` → the request (`PENDING`, or `ACCEPTED` if they had asked you). 404 `PLAYER_NOT_FOUND`, 409 `ALREADY_FRIENDS` / `PLAYER_UNAVAILABLE` / `TOO_MANY_PENDING_REQUESTS` / `TOO_MANY_FRIENDS`, 422 `CANNOT_FRIEND_SELF` |
| POST | `/friends/requests/{id}/accept`, `/decline` (recipient), `/cancel` (sender) | 404 `FRIEND_REQUEST_NOT_FOUND` for anyone else, 409 `REQUEST_NOT_PENDING` |
| DELETE | `/friends/{username}` | End a friendship (204, idempotent) |
| POST / DELETE | `/blocks`, `/blocks/{username}` | Block (`{username}`) / unblock |
| GET | `/notifications` | `{unread, items[{id, kind, actor, gameId, gameName, referenceId, createdAt, read}]}` (newest 30) |
| GET | `/notifications/unread-count` | `{unread}` — the cheap poll |
| POST | `/notifications/read` | `{ids?}` — mark yours read (all when empty) |
| GET | `/invitations` | `{incoming[]}`: your pending invitations with lobby name, map, capacity, inviter, `joinable` |
| GET | `/games/{gameId}/invitations` | Invitations you sent for a lobby you are in (404 otherwise) |
| POST | `/invitations` | `{gameId, username}` — host invites a friend. 403 `NOT_HOST`, 404 `GAME_NOT_FOUND` (not a member), 409 `NOT_FRIENDS` / `PLAYER_UNAVAILABLE` / `ALREADY_IN_GAME` / `LOBBY_NOT_JOINABLE` / `TOO_MANY_INVITATIONS`; a repeat returns the pending invitation |
| POST | `/invitations/{id}/accept` (invitee) | Joins the lobby → invitation `ACCEPTED`. 409 `GAME_FULL` / `GAME_ALREADY_STARTED` / `INVITATION_INVALID` (the invitation is INVALIDATED), `INVITATION_NOT_PENDING`; 404 `INVITATION_NOT_FOUND` for anyone else |
| POST | `/invitations/{id}/decline` (invitee), `/cancel` (inviter) | |
| GET | `/matchmaking/quick` | `{state: NONE \| SEARCHING \| MATCHED \| CANCELLED \| EXPIRED, ticketId, searchingSince, match{gameId, gameName, mapName, yourFaction, opponent{username, displayName, emblem, faction}, opened}}` — read-only, never starts a search |
| POST | `/matchmaking/quick/join` | Start searching (or return the running search / unopened match); looks for an opponent at once. 409 `TOO_MANY_ACTIVE_GAMES` |
| POST | `/matchmaking/quick/heartbeat` | Keep the search alive and look for an opponent (clients call it every 10 s while searching) |
| POST | `/matchmaking/quick/cancel` | Stop searching; returns `MATCHED` if a match was already committed |
| POST | `/matchmaking/quick/acknowledge` | The matched game was opened; the next join searches again |

`GET /games` (my games) additionally returns `mapName`, `yourNation{key, name, color}`,
`currentRound`, `yourTurn`, `paused`, `youAreEliminated`, `humanPlayers`, `computerPlayers` and
`lastActivityAt` (Phase 13A).

### 2.7 Operator (Phase 16+; none implemented)

No operator endpoints exist (Phase 15B audit). Operator tasks such as removing the Supabase user of
a deleted account are manual (PRODUCTION_READINESS.md §5.4). A future `/admin/**` would require an
operator role claim (or allowlisted player ids):
mark game abandoned, view incidents, create/schedule announcement campaigns.
Not exposed in the public UI.

## 3. Errors

RFC 9457 problem details with a stable machine `code`:

```json
{
  "type": "https://<domain>/problems/insufficient-gold",
  "title": "Insufficient gold",
  "status": 422,
  "code": "INSUFFICIENT_GOLD",
  "detail": "Recruitment costs 25.000 gold; treasury is 12.500 gold.",
  "instance": "/api/v1/games/7a1…/cities/9b2…/recruitments",
  "requestId": "f3c9…",
  "context": { "requiredMg": 25000, "availableMg": 12500 }
}
```

| HTTP | When | Example codes |
|---|---|---|
| 400 | Malformed request, DTO validation | `VALIDATION_FAILED` (with `errors[]` per field), `IDEMPOTENCY_KEY_MISSING` |
| 401 | Missing/invalid/expired token | `UNAUTHENTICATED` |
| 403 | Authenticated but not allowed | `NOT_HOST`, `NOT_OWNER`, `TERMS_NOT_ACCEPTED`, `ACCOUNT_DELETED`, `REAUTHENTICATION_REQUIRED` |
| 404 | Not found **or not visible to you** | `GAME_NOT_FOUND`, `INVITE_CODE_NOT_FOUND`, `MAP_NOT_FOUND`, `CITY_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `PLAYER_NOT_FOUND`, `FRIEND_REQUEST_NOT_FOUND`, `INVITATION_NOT_FOUND` |
| 409 | State conflict — retry after refresh may help | `NOT_ACTIVE_PLAYER`, `TURN_CHANGED`, `GAME_NOT_IN_PROGRESS`, `GAME_FINISHED`, `ALREADY_AT_WAR`, `TRUCE_IN_EFFECT`, `PEACE_TERMS_NO_LONGER_VALID`, `GAME_FULL`, `DISPLAY_NAME_TAKEN`, `NATION_TAKEN`, `GAME_NOT_READY`, `NATIONS_NOT_SELECTED`, `GAME_ALREADY_STARTED`, `PROVINCE_OCCUPIED`, `USERNAME_TAKEN`, `ALREADY_FRIENDS`, `PLAYER_UNAVAILABLE`, `REQUEST_NOT_PENDING`, `TOO_MANY_PENDING_REQUESTS`, `TOO_MANY_FRIENDS`, `NOT_FRIENDS`, `ALREADY_IN_GAME`, `LOBBY_NOT_JOINABLE`, `TOO_MANY_INVITATIONS`, `INVITATION_NOT_PENDING`, `INVITATION_INVALID`, `TOO_MANY_ACTIVE_GAMES`, `TOO_MANY_UNFINISHED_GAMES`, `TERMS_VERSION_OUTDATED`, `ACCOUNT_DELETION_BLOCKED` |
| 413 | Body larger than 64 KB | `PAYLOAD_TOO_LARGE` |
| 422 | Well-formed but illegal game action | `INVALID_DISPLAY_NAME`, `INVALID_USERNAME`, `USERNAME_RESERVED`, `INVALID_EMBLEM`, `CANNOT_FRIEND_SELF`, `CANNOT_BLOCK_SELF`, `NATION_NOT_FOUND`, `TIER_LOCKED`, `WORKSHOP_LIMIT_REACHED`, `INSUFFICIENT_GOLD`, `INSUFFICIENT_POPULATION`, `NOT_ADJACENT`, `BATTALION_ALREADY_MOVED`, `BATTALION_NOT_READY` (recruited this turn), `BATTALIONS_NOT_COLOCATED`, `CITY_BESIEGED`, `NOT_FRIENDLY_TERRITORY`, `IN_ARREARS`, `TECH_REQUIRED`, `IDEMPOTENCY_KEY_REUSED` |
| 429 | Per-account budget exhausted (Phase 15B; policies in SECURITY_AUDIT.md §4), with `Retry-After` seconds | `RATE_LIMITED` |
| 500 | Bug; transaction rolled back | `INTERNAL_ERROR`, `ROUND_PROCESSING_FAILED` |
| 503 | Lock timeout / maintenance | `GAME_BUSY` (with `Retry-After`) |

Domain exceptions are typed (`sealed` hierarchy `GameRuleViolation`) and mapped in
one `@RestControllerAdvice`. Stack traces never leave the server. Every error
response includes `requestId` for log correlation.

## 4. Resource ownership rules

- The acting player is **always** derived from the token; there is no
  `playerId` in any request body.
- Every per-game resource lookup is scoped by `gameId` from the path *and* the
  viewer's membership. A resource id from another game yields 404, even if it exists.
- Write access to armies, battalions, cities and buildings requires
  `resource.nation_id = viewer's nation` (and for cities: owned **and** controlled).
- Read access: all public state of the game for members (no fog in MVP); private
  details (exact treasury, budget projections, research progress) only for the
  owning nation.

## 5. Versioning and compatibility

- Breaking changes → `/api/v2` (unlikely before 1.0; the frontend ships with the
  backend from one repo, so pre-1.0 changes are coordinated).
- Additive fields are non-breaking; the client ignores unknown fields.
