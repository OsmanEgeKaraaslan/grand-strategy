# Scalability plan: Supabase database, one VPS today, several VPSs later

Status: **PROPOSED** (2026-11-09); the product owner started it on 2026-11-09. Each phase needs
their go-ahead, and every new dependency or piece of infrastructure (Redis, a load-test tool, a
metrics registry, a paid load balancer) needs written approval first (AGENTS.md §1).

| Phase | State |
|---|---|
| 1. Supabase on one VPS | **Code done (2026-11-09):** optional `auth` schema in the backup, game-only restore, session-pooler and pool-size settings documented. 🛠 Supabase project, roles, backup timers and the restore drill are manual steps. |
| 3. Database load per player | **Done (2026-11-09):** state polls check the version before building the read model. Map caching was already in place (see Phase 3). |
| 2, 4–7 | Not started |

## Chosen setup (product owner, 2026-11-09)

| Part | Start | Later |
|---|---|---|
| Frontend | Cloudflare Pages (free) | unchanged |
| Backend | **one cheap VPS** (Docker Compose), reached through a Cloudflare Tunnel | **2–N cheap VPSs**, each running a tunnel connector; optional Cloudflare Load Balancer |
| Database | **Supabase PostgreSQL, free tier** | Supabase **Pro** (at the public launch at the latest), then bigger compute sizes and a read replica |
| Auth | Supabase Auth (same project) | unchanged |
| Backups | nightly encrypted dump on the VPS, copied to **Backblaze B2**, 30-day retention (`deploy/backup/`) | unchanged; one backup job for the whole cluster |

**Goal.** The same backend image runs on one VPS or on several behind a load balancer. Supporting
more concurrent players means spending more (a bigger Supabase compute size, more VPSs), not
changing the system.

## 1. Feasibility

Possible. All VPSs share the one Supabase PostgreSQL primary, so adding app servers never requires
sharding; sharding only becomes relevant if that single primary is saturated, which Supabase's
largest compute sizes put very far away for this game. Even then the schema keys every per-game
table by `game_id` with composite foreign keys, so sharding by game would be a migration project,
not a rewrite.

### Already in place

- Stateless API: Supabase JWTs are verified per request; no server session.
- Consistency lives in PostgreSQL: game commands lock the game row (`SELECT … FOR UPDATE`),
  matchmaking uses transaction-level advisory locks, idempotency keys are stored in the database.
  Two VPSs working on the same game cannot corrupt it.
- Row-level security is enabled on every game table (V23), so Supabase's public data API cannot read
  game data; only the backend's own database role can.
- One Docker image configured only through environment variables; graceful shutdown; separate
  liveness and readiness probes (readiness includes the database).
- In-memory caches hold immutable data only (`WorldService` map templates, `AiObserver` map data).
- The game-state poll already answers unchanged requests with `304` via an ETag on `stateVersion`.
- Backup pipeline: encrypted `pg_dump`, offsite copy to B2 with checksums, a B2 key without delete
  rights, 30-day retention, staleness alert (PRODUCTION_READINESS §8).

### Single-instance assumptions to remove before a second VPS

| # | Where | Problem with several VPSs | Severity |
|---|---|---|---|
| S1 | `ai/application/AiTurnRunner.java` (`running`, `dirty`), `AiTriggers.java` (`queued`, recovery sweep) | The "this game's computer turn is running" guard is per process. Two VPSs can play the same computer nation at once; each action is individually legal, so both apply (duplicate recruits, moves). The 15 s recovery sweep runs on every VPS. Also bites one VPS during a deploy, when old and new containers overlap. | **Correctness bug: blocks a second VPS** |
| S2 | `platform/ratelimit/RateLimiter.java` | Token buckets live in memory: N VPSs give N× the budget; a restart resets them (SECURITY_AUDIT §4). | Abuse protection weakens |
| S3 | Flyway runs on every container at startup | Safe (Flyway locks), but rolling deploys need old and new versions to run side by side against one schema. | Deploy hygiene |
| S4 | `AiNegotiator` `idle` memo | Per-process memo; harmless duplication, verify during Phase 2. | Low |

## 2. Supabase free tier: what it means and when to leave it

| Free-tier limit (check Supabase's pricing page; values change) | Effect here | Mitigation |
|---|---|---|
| Project **pauses after about a week without activity** | Sign-in and the database stop until someone clicks *Restore* in the dashboard. | Acceptable in a private beta; leave the free tier before the public launch. |
| **500 MB** database | Over the limit the database turns read-only. | Watch `pg_database_size`; archive finished games (Phase 5); move to Pro at ~400 MB. |
| Small shared compute (~0.5 GB) and few connections | Slows down under load; a pool of 10 per VPS uses connections quickly. | Session pooler; `DB_POOL_SIZE` derived from the VPS count (Phase 1). |
| **No Supabase backups** | Data loss if the project is lost. | Our own nightly B2 backups, including accounts (Phase 1). |
| ~5 GB egress, 50,000 monthly active users | Enough for a beta. | Phase 3 lowers egress. |

**Move to Supabase Pro ($25/month, data stays, one click) when any of these happens:** the public
launch; the project paused on real players; the database passes ~400 MB; sustained slowness at the
smallest compute size. Keeping the project awake with scheduled pings is not a plan: Supabase does
not promise it works, and it fixes neither backups nor the size limit.

## 3. Phases

### Phase 1: Move the database to Supabase, on one VPS (~1 session, mostly configuration)

- **Region.** Create the Supabase project in the same region as the VPS (e.g. both in Frankfurt);
  every request runs several queries and each pays the distance.
- **Connection.** Point the backend at Supabase's **session pooler** (port 5432, works over IPv4) or
  the direct host (IPv6 only unless the IPv4 add-on is bought): `DB_URL=jdbc:postgresql://…:5432/postgres?sslmode=require`.
  Do **not** use the transaction pooler (port 6543): it breaks session-level locks and
  `LISTEN/NOTIFY`, and needs `prepareThreshold=0`.
- **Roles.** The backend uses its own role (PRODUCTION_READINESS §5.5), the backup job a read-only
  `strategy_backup` role. Never the `service_role` key anywhere.
- **Pool size.** `DB_POOL_SIZE × number of VPSs` (+ the backup job and migrations) must stay below the
  connection limit of the Supabase compute size; start with 5–10 on one VPS.
- **Migrations.** Flyway creates the `game` schema on first start (V1–V24 run unchanged).
- **Moving existing data (optional).** `pg_dump --schema=game` from the old database, `restore.sh`
  into Supabase, during a short maintenance window. Player rows are keyed by the Supabase user id,
  which does not change because Auth stays in the same project.
- **Backups.**
  - `BACKUP_DATABASE_URL` points at Supabase (session pooler); `pg_dump` 17 or newer (the backup image
    already provides it).
  - **Also dump the `auth` schema** (accounts and password hashes), done: `BACKUP_INCLUDE_AUTH=true`
    adds it to the same encrypted file after step 3 of `deploy/supabase/app-role.sql`; `restore.sh`
    still restores only `game`. Without it, a lost Supabase project would bring game data back but
    players could not sign in, and new accounts would get new ids that no longer match their games.
    Restoring `auth` into a new Supabase project is not officially supported, so rehearse it once
    (PRODUCTION_READINESS §8).
  - B2 bucket in the same region family, lifecycle rule 30 days, key without `deleteFiles`
    (PRODUCTION_READINESS §8.1).
  - **Restore drill** into a scratch Supabase project before the public launch: backups have not yet
    been verified against Supabase and B2 (PRODUCTION_READINESS §8, 🟡).
- **Spend safety.** On Pro, keep the **spend cap ON**; B2 caps and alerts on; VPS billing alerts.

### Phase 2: Instance-safe correctness (~1 session): required before a second VPS

- **Computer turns are claimed in the database** before they are played, with a
  **transaction-level** advisory lock (`pg_try_advisory_xact_lock`) or a lease table claimed with
  `FOR UPDATE SKIP LOCKED`. Session-level locks are avoided so the design keeps working behind any
  Supabase pooler. Only the holder plays; other VPSs skip the game. The recovery sweep claims instead
  of dispatching everything on every VPS. The in-memory sets remain a local optimisation. Fixes S1.
- **Rate limiter behind an interface**: `memory` (default, one VPS) and `postgres` (several VPSs, a
  small table with atomic updates; no new infrastructure). A `redis` implementation only if the
  database becomes the bottleneck (*approval needed*). Fixes S2.
- **Multi-instance tests**: two application contexts against one PostgreSQL (Testcontainers):
  - a double end-turn split across instances takes effect exactly once;
  - one game's computer turn never runs on both instances;
  - killing one instance mid-computer-turn: the other resumes it (progress is stored per action,
    docs/AI.md).

### Phase 3: Lower database load per player (~1 session, pays off on one VPS and on the free tier)

- **Cheap ETag check** (done): `GameStateService.version` reads the game row and the caller's
  membership (two indexed lookups, same access rules as the full read) and the endpoint answers `304`
  before the world snapshot is built. Most 3 s polls are unchanged, so most database work disappears.
- **No per-row queries** (done 2026-11-20): on a Grand Campaign game the economy overview took 273
  SQL statements (siege and technology checks per city and per nation), military 43 and battles 33.
  Besieged cities/provinces and every nation's research effects are now read once per request:
  economy 20, military 19, battles 15. `QueryBudgetIT` fails when an endpoint exceeds its statement
  budget, so an N+1 cannot come back unnoticed.
- **Map templates** (already in place, unchanged): `GET /maps/{id}` is cached privately for one day by
  the browser. Neither longer nor shared (CDN) caching fits: the request carries the bearer token, and
  templates are not strictly immutable (V22 renamed factions in place).
- Optional later: live push (server-sent events fed by PostgreSQL `LISTEN/NOTIFY`, which reaches every
  VPS without a broker; requires the session pooler or direct connection), polling as fallback.

### Phase 4: Deploys and the second VPS (~1 session)

- **Migrations as a separate one-shot step** before a deploy; running containers only validate.
  Fixes S3.
- **Expand/contract rule** for every future migration: never drop or rename a column in the same
  release that stops using it, so old and new versions coexist during a rolling deploy. Add to
  AGENTS.md §3 when adopted.
- **Rolling deploy**: update one VPS at a time; readiness takes it out of rotation while it restarts.
- **Load balancing**:
  1. Run a `cloudflared` connector for the **same tunnel** on every VPS. Cloudflare spreads requests
     across connectors and stops using one that disconnects. No extra cost.
  2. When health-based routing is needed, add the **Cloudflare Load Balancer** (~$5/month,
     *approval needed*) with the readiness endpoint as its health check.
- **Backups stay one job**: run the backup and offsite timers on exactly one VPS (it dumps the shared
  database), with the staleness alert as the check that it keeps running.
- **Configuration per VPS** stays environment-only (`.env.production`, PRODUCTION_READINESS §11);
  `STRATEGY_ROLE=all` everywhere until Phase 6.

### Phase 5: Growing the database

1. Supabase **Pro**, then larger compute sizes as load grows (keep `DB_POOL_SIZE × VPSs` under each
   size's connection limit).
2. **Archive finished games** to keep the hot data small (and under the free-tier size until Pro).
3. A **read replica** for heavy read-only endpoints (history, profiles).
4. Only if the largest compute size is not enough: partition large per-game tables by game, then
   shard by `game_id`.

### Phase 6: Optional, when computer turns dominate

- Server roles `STRATEGY_ROLE=all|api|worker`: some VPSs serve HTTP only, others play computer turns,
  so each kind scales on its own. Not needed while every VPS runs `all`.

### Phase 7: Proof and drills (ongoing)

- Metrics (Micrometer) and a load test (k6 or Gatling) against agreed targets, e.g. "N players online,
  p95 < 200 ms" (*approval needed for both*).
- Kill a VPS during a computer turn; restore from a B2 backup into a scratch project; pause/restore
  drill on the free tier.

## 4. Cost by stage (approximate; check current Supabase and VPS prices)

| Stage | Players online at once (estimate) | Setup | Monthly |
|---|---|---|---|
| **Private beta** | a few dozen | Supabase free + 1 small VPS + B2 | **~$5–10** |
| **Public launch** | up to ~100 | Supabase Pro (smallest compute) + 1 VPS | **~$30–40** |
| **Growing** | ~500–1,000 | Supabase Small/Medium + 2 VPSs (+ Cloudflare LB) | **~$50–100** |
| **Busy** | ~2,000–5,000 | Supabase Large/XL + 3–4 VPSs | **~$170–270** |

Player counts are estimates until the Phase 7 load test; Phase 3 moves each step to more players.
Cloudflare Pages and Tunnel are free; B2 costs cents for 30 nightly dumps.

## 5. Order and effort

1. **Phase 1** (Supabase + backups incl. `auth`, restore drill), on one VPS.
2. **Phase 3** (cheap ETag check, map caching): more players per VPS and per database size.
3. **Phase 2** before the second VPS (also fixes the deploy-overlap case on one VPS).
4. **Phase 4** when the second VPS is added.
5. Phases 5–7 as traffic calls for them.

Estimate for one developer: **about 3–4 working sessions for Phases 1–4**, plus trial runs. Game
rules and the public API do not change. Each phase updates [ARCHITECTURE.md](ARCHITECTURE.md),
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) and, where a decision is taken,
[OPEN_QUESTIONS.md](OPEN_QUESTIONS.md).
