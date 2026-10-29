# Fictional Grand Strategy (working title)

A browser-based, multiplayer, **classic sequential turn-based** grand strategy game set
in an entirely fictional world. Players govern nations made of irregular provinces,
grow numeric populations into a working economy, recruit infantry, cavalry and
artillery, fight deterministic terrain-dependent battles, besiege and starve cities,
research technology, and — later — negotiate alliances and peace treaties.

The project has two equal goals:

1. A genuinely playable product, starting with a small two-player prototype.
2. A portfolio-grade demonstration of professional Java / Spring Boot backend
   engineering: transactional state machines, concurrency control, idempotency,
   deterministic simulation, relational modelling, security and testing.

## Current status

| Phase | Status |
|---|---|
| 0 — Specification and architecture | Done. Final P0 decisions recorded 2026-10-17 ([decision log](docs/OPEN_QUESTIONS.md#decision-log)) |
| 1 — Backend foundation | Done. Spring Boot 4.1 (now 4.1.2), PostgreSQL + Flyway, default-deny security, health endpoint, problem-details errors, Docker Compose, Dockerfile, CI |
| 2 — Auth, players, lobby, frontend | Done. Supabase sign-up/sign-in, JWT verification, player profiles, private two-player lobbies with invitation codes, React frontend |
| 3 — Nations, map, game start, sequential turns | Done. Fictional map, nation choice, transactional start, sequential turns with exactly-once round completion, SVG map game screen |
| 4 — Population, economy, workshops | Implemented: exact milligold economy (farming, Tier 1/2 workshops by priority), treasuries, Tier 1 workshop construction with Q-E8 timing, round-end income exactly once, occupied cities pay nobody, economy panels in the game screen. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 5 — Recruitment, armies, movement, battles | Implemented: recruitment from city population, starting forces (ruleset v2), per-battalion movement, armies as labels, deterministic battles with frontline protection, flanking and terrain (provisional coefficients), retreat or destruction, battle reports, simple occupation (control only), battalion upkeep. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 6 — Advanced combat, reinforcement, disbanding | Implemented: combat engine reviewed with 24 golden balance scenarios (coefficients unchanged, still provisional), clearer battle reports, reinforcement (≤ 500 per turn, pro-rata cost, real civilians), origin-aware soldier cohorts, disbanding with per-origin returns. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 7 — Sieges, fortifications, repairs | Implemented: fortresses (levels 1–3, build, upgrade, repair, upkeep), sieges of fortified provinces across global rounds (5% civilian attrition, artillery shortens sieges but damages the fortress and a workshop), capture of control only, workshop repairs, besieged-city restrictions; unfortified provinces keep simple occupation. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 8 — Technology, research, doctrines, growth | Implemented: Military Academy, University and Barracks producing national research points, military and civilian technology levels, doctrines (mountain, river, siege, logistics) that change real combat, siege and upkeep calculations, Tier 1 → 2 workshop upgrades, 0.5% population growth. All backend (unit + PostgreSQL) and frontend tests pass locally |
| **9 — Diplomacy, war, peace, victory** | **Implemented**: peace by default, war declarations that gate movement, battles, occupation and sieges, normal alliances and defensive pacts, server-side war score, peace proposals settled atomically (provinces, reparations, ten-round tribute, population transfers), truce and withdrawal, round-30 match completion with stored results. All backend (unit + PostgreSQL) and frontend tests pass locally |
| **10 — World expansion, N-faction multiplayer, coalitions** | **Implemented**: Grand Campaign map (72 provinces, 10 factions), 2–4 human players with passive game-controlled nations, starting values per tier, gold gifts, defensive calls to arms, coalition battles, attached battalions, peace conferences with revisions and approvals that pause the game, separate peace, elimination and domination (ruleset v6). All backend (unit + PostgreSQL) and frontend tests pass locally |
| **11 — Autonomous nation AI** | **Implemented**: every nation nobody chooses is played by the computer (ruleset v7) — it builds, recruits, researches, moves, fights, besieges, allies, answers calls, negotiates and concludes peace through the same rules and command pipeline as players ([docs/AI.md](docs/AI.md)). All backend (unit + PostgreSQL) and frontend tests pass locally |
| **12–13 — Profiles, friends, invitations, Quick Match** | **Implemented**: unique usernames, emblems and public profiles with statistics from stored results; friend requests, friendships, blocks and in-app notifications; host invitations of friends into private lobbies; *My active games* with resume; unranked Quick Match (two humans, eight computer nations) on a race-safe PostgreSQL queue; social panel on the dashboard and inside a running game. All backend (unit + PostgreSQL) and frontend tests pass locally |
| **15 — Closed beta readiness: accounts, legal, security** | **Implemented**: password recovery and change, resend confirmation, account deletion (anonymization), versioned Terms/Privacy acceptance, draft legal pages with operator placeholders ([privacy inventory](docs/DATA_PRIVACY_INVENTORY.md)); authorization matrix over every endpoint, per-account rate limits, resource bounds, security headers, dependency patch upgrade ([security audit](docs/SECURITY_AUDIT.md)); production configuration, Docker/Caddy templates and backup scripts ([production readiness](docs/PRODUCTION_READINESS.md)). Nothing deployed; legal texts are drafts. All backend (unit + PostgreSQL) and frontend tests pass locally |
| Next | Operator decisions (legal texts, SMTP, Supabase production settings), staging and a restore drill, then Phase 16 (deployment and closed beta). Gameplay balancing is not started |

Playable so far: create a lobby, invite a rival, choose nations, start, take turns on the map, earn gold and build workshops, recruit, reinforce and disband battalions, move them, fight battles, occupy open provinces, besiege fortresses and repair the damage, build research institutions, unlock technology and doctrines, upgrade workshops to Tier 2 and watch cities grow. Declare war, form alliances, negotiate peace with territory, gold, tribute and people, and play a complete game to its round-30 result. Pick a username, add friends, invite them into a private game, resume any of your games from the dashboard, or find an opponent with Quick Match. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1.2, Spring Web MVC, Spring Security (OAuth2 resource server), Spring Data JPA / Hibernate, Bean Validation, Actuator, Maven (wrapper included) |
| Database | PostgreSQL 17 (Docker locally, Supabase PostgreSQL in production), Flyway |
| Auth | Supabase Auth (email + password); access tokens verified by Spring Security against the project's JWKS |
| Frontend | React 19, TypeScript (strict), Vite, React Router, TanStack Query, `@supabase/supabase-js` (auth only) |
| Testing | JUnit 5, Mockito, Spring MockMvc, Testcontainers (PostgreSQL); Vitest + Testing Library |
| Infrastructure | Docker, GitHub Actions, Linux VPS (backend), Cloudflare Pages (frontend) |

## Required software

| Tool | Version | Needed for |
|---|---|---|
| JDK | **21** (e.g. Eclipse Temurin 21) | Building and running the backend |
| Node.js | **22 LTS** (npm 10) | Building and running the frontend |
| Docker (Docker Desktop or Engine + Compose v2) | recent | Local PostgreSQL and the PostgreSQL integration tests |
| Supabase project | hosted project **or** the Supabase CLI local stack | Real sign-up/sign-in |
| Maven | not required | The Maven wrapper (`backend/mvnw`) downloads Maven itself |

## Project structure

```
.
├── backend/                         Spring Boot application (Maven)
│   ├── Dockerfile                   Production image (multi-stage, non-root, layered jar)
│   └── src/main/java/com/reach/strategy/
│       ├── platform/                Technical foundation: security (Supabase JWT, CORS),
│       │                            error handling (RFC 9457), request ids, health
│       ├── identity/                Players: profile, first-login provisioning, GET/PATCH /me
│       ├── world/                   Map templates + validation, per-game world (nations, provinces, cities)
│       ├── economy/                 Employment, income, treasuries, workshops, construction, round-end economy
│       └── game/                    Lobby, nation choice, start, sequential turns, round processing
│   └── src/main/resources/db/migration/   V1 baseline … V6 economy (balance values in resources/rulesets/v1)
├── frontend/                        React + TypeScript + Vite
│   └── src/
│       ├── auth/                    Supabase session context, protected routes
│       ├── api/                     Central API client, query hooks, API types
│       ├── pages/                   Login, Register, Dashboard, Lobby, Game (map + turns)
│       └── components/              Shell, SVG province map, alerts, badges, spinner
├── compose.yaml                     Local PostgreSQL 17
├── .env.example                     Backend environment variables
├── frontend/.env.example            Frontend (public) environment variables
├── .github/workflows/               backend-ci.yml, frontend-ci.yml
├── docs/                            Design documentation
└── AGENTS.md                        Standing instructions for coding agents
```

## Supabase setup

The backend needs only your project's **URL** (it fetches the public signing keys); the
frontend needs the URL and the **publishable (anon) key**. No secret or service-role key is
used anywhere in this project — never put one in a `VITE_` variable.

**Hosted project:**

1. Create a project at supabase.com.
2. *Authentication → Sign In / Providers*: keep **Email** enabled. Choose whether
   **Confirm email** is on (the register page handles both cases).
3. *Authentication → URL Configuration*: set **Site URL** to `http://localhost:5173` and add
   `http://localhost:5173/login` (email confirmation) and `http://localhost:5173/reset-password`
   (password recovery) to the redirect URLs. Production settings (Confirm email, SMTP, rate limits,
   exact redirect URLs): [docs/PRODUCTION_READINESS.md §5](docs/PRODUCTION_READINESS.md).
4. *Project Settings → JWT Keys*: make sure the project uses **JWT Signing Keys**
   (asymmetric, ES256 or RS256). Projects still on the legacy shared JWT secret must migrate:
   the backend deliberately rejects HS256 tokens.
5. Copy the project URL and the publishable key (*Project Settings → API Keys*).

**Local alternative:** with the Supabase CLI, `supabase start` runs Auth at
`http://127.0.0.1:54321` — the default `SUPABASE_URL` of the `local` profile. Use the
publishable/anon key it prints.

## Running locally

1. **Database** (from the repository root):
   ```bash
   docker compose up -d
   ```
2. **Backend** (from `backend/`; on Windows use `mvnw.cmd`). Set `SUPABASE_URL` unless you
   use the Supabase CLI default:
   ```bash
   SUPABASE_URL=https://your-project-ref.supabase.co ./mvnw spring-boot:run
   ```
   Check it: `curl http://localhost:8080/api/v1/health`. Flyway applies migrations on start.
3. **Frontend** (from `frontend/`):
   ```bash
   cp .env.example .env.local
   ```
   Edit `.env.local` (API URL, Supabase URL, publishable key), then:
   ```bash
   npm install
   npm run dev
   ```
   Open http://localhost:5173. The dev server must run on port 5173 because that origin
   is on the backend's CORS allow-list.

Two-player test: register two accounts (e.g. a normal and a private browser window), create
a game with one, copy the invitation code, join with the other, pick different nations, and
start as the host. Both players land on the map; the host takes the first turn, then the
guest, then round 2 begins. Changes appear on the other screen within ~3 seconds (polling).

Economy check: click one of your provinces to see its farmers, workshop workers and income, order
a Tier 1 workshop (40 gold) on your turn, and end the round — your treasury grows by the projected
income and the workshop becomes active, producing from the following round.

### Manual account and security test (Phase 15)

Needs a Supabase project with the redirect URLs above (and, to see the emails, Confirm email on —
the built-in sender is enough locally).

1. **Sign-up consent**: on *Register* the button refuses until the Terms checkbox is ticked; the
   legal links open `/legal/terms`, `/legal/privacy`, `/legal/cookies` (drafts, placeholders
   highlighted). After confirming the email and signing in, no acceptance screen appears.
2. **Existing account**: sign in with an account created before Phase 15 — the *Before you play*
   screen appears; accept it and the dashboard opens.
3. **Resend confirmation**: register a second address, use *Resend confirmation email* (then wait a
   minute); on *Sign in* with the unconfirmed address the resend button also appears.
4. **Forgot password**: *Forgot your password?* → any address shows the same "If an account
   exists…" message. Open the email link → *Choose a new password* → saved, signed in. Open the same
   link again → "This link is invalid or has expired".
5. **Change password**: header *Account* → wrong current password is refused; right one changes it.
6. **Delete account**: while in a lobby → refused with the number of unfinished games. In an account
   without unfinished games: password + `DELETE` → signed out, *Account deleted* page; the old handle's
   profile is gone, a former friend no longer lists it, finished matches show `Deleted#…`. Sign in
   again with the same email → signed out at once (account deleted). Remove the user in Supabase
   (PRODUCTION_READINESS.md §5.4).
7. **Rate limit**: type quickly in player search more than 30 times a minute → "You are doing that
   too often".

### Manual social test (Phases 12–13)

Use three separate authenticated sessions — e.g. a normal window, a private window and a second
browser — each signed in with its own account (A, B, C).

1. **Profiles.** In each window open your name in the header → *Profile settings*: pick a username
   (try `admin` → reserved; try another window's username in capitals → already taken), an emblem and
   whether your recent matches are public. The dashboard note disappears once a username is chosen.
2. **Friends.** In A's *Social → Friends* search B's username (exact, or its first three letters) and
   press *Add friend*. B's bell shows 1 unread; B accepts in *Friends* (or C declines a request from A).
   Both lists now show each other; open B's profile from A (relation *Friend*). Try blocking C from A:
   C can no longer send A requests.
3. **Private game by invitation.** A creates a private game (Grand Campaign, 2 players). In the lobby's
   *Invite friends* card A invites B. B sees it in *Social → Invitations* (and the bell), presses *Join*
   and lands in the lobby. Both choose different nations; A starts. B's bell says the game started.
4. **Resume.** Close B's tab, reopen the dashboard: *My active games* shows the game with B's faction,
   round and *Your turn* when it is B's turn; *Resume* opens it where it was.
5. **In-game social.** Inside the game open the *Social* icon in the left toolbar: friends, requests,
   invitations, games and Quick Match open over the map; select an army first and check that the
   selection and camera are unchanged after closing the window.
6. **Quick Match.** In A press *Find a match* (dashboard or in-game). The card says *Searching for an
   opponent…* without any time estimate and offers alternatives; cancel and search again. In C press
   *Find a match*: both see *Match found* with both factions and *Play now*; exactly one new game with
   eight computer nations appears in both game lists. Close A's tab before pressing *Play now*, sign in
   again: the same match is offered, not a new search.
7. **Expiry.** Start a search in one window and close it; after about 90 seconds a new searcher is
   not paired with it, and the closed window shows *Your last search stopped…* when reopened.

### Manual test with computer opponents (Phase 11)

New games use ruleset v7: every nation nobody chooses is played by the computer. Two browser windows
(normal and private), two accounts.

1. Create a game (map *Grand Campaign*, *2 human players*), join, choose e.g. Castavar and Nordmark,
   start. The *Players* card lists eight seats tagged *Computer*.
2. End both human turns. The turn bar shows "<nation> (computer) is playing its turn…" for each
   computer in turn; a round of eight computers takes a few seconds (backend log lines
   `Computer <nation> round <n>: <action> — <reason>`; table `game.ai_turn` has the duration of
   every computer turn, `game.ai_decision` the reason of every major decision).
3. **Development:** after a few rounds the map's cities of computer nations show new workshops,
   battalions appear and grow; the diplomacy history shows their alliances.
4. **Small nations expanding / large powers threatening:** from round 3 computers declare wars on
   weaker neighbours (Aurelia often attacks Frisia); watch occupation colours, battle reports and,
   later, peace conferences between computers that pause the game briefly and end in cessions.
5. **War against you:** keep your army small and a computer neighbour will declare war, march to
   your border, attack your weak stacks and besiege your fortresses; recruit and garrison to deter it.
6. **Coalitions:** offer a defensive pact to a computer neighbour (it answers on its turn), call it to
   arms when attacked, and see whether it honours or declines (which ends the alliance).
7. **Negotiation:** ask a computer enemy for peace; a clearly stronger one refuses while the war is
   young. In a conference it drafts demands, judges every revision you submit (approve / reject /
   counter-draft) and never approves an older revision.
8. **Performance:** note how long the computers take per round on your machine.

### Manual multiplayer test (Grand Campaign, Phase 10)

New games use ruleset v6 and the Grand Campaign map. Use three browser profiles (or normal and private
windows of different browsers), each signed in with its own account.

1. In window 1 create a game: map *Grand Campaign*, *3 human players*. Join with the invitation code in
   windows 2 and 3. Choose Castavar, Nordmark and Vostrava (the other factions are greyed out: they
   are controlled by the game). Start.
2. **Turns:** only the three human nations take turns; the seven game-controlled nations never block.
   Pan and zoom the map with the buttons, the mouse wheel or by dragging.
3. **Gift:** on Castavar's turn open *Send gold*, choose Nordmark, enter an amount, confirm.
4. **Alliance:** on Nordmark's turn propose a *Defensive pact* to Vostrava; accept it on Vostrava's turn.
5. **War and call to arms:** Castavar declares war on Nordmark. On Nordmark's turn the war card shows
   *Call to arms* with Vostrava; call. On Vostrava's turn the call appears under *Calls to arms*:
   *Honour — join the war* (or *Decline*, which ends the alliance after confirmation).
6. **Coalition battle with a lent battalion:** the four powers never border each other; armies meet in
   the lands between them. Nordmark and Vostrava both border Aestia (game-controlled): both declare
   war on Aestia, march into the same Aestian province (e.g. Zemgale), and on Vostrava's turn select
   its battalion there and *Lend* it to Nordmark. On Nordmark's turn it is listed as “Lent by Vostrava —
   you command it”; select it with Nordmark's own battalions and attack a neighbouring Aestian
   province. The battle report has a line per nation; each battalion moves at most once per round.
7. **Peace conference:** in the Castavar–Nordmark war a leader presses *Request peace conference*; the
   other leader accepts (at any time). All windows show the pause banner and *End turn* disappears.
   Revise the draft (e.g. Vostrava pays gold to Castavar) — Vostrava must now approve as well; approve
   in every window that shows *Approve revision n*. The last approval settles the treaty and play
   resumes with the same player. Vostrava (not a leader there) can instead ask Castavar for a
   *separate peace*. Against Aestia, the game-controlled nation agrees to talk at once and gives up
   only provinces you already occupy.
8. **Elimination / domination:** a treaty that takes every province of a nation eliminates it (its
   soldiers go home as civilians); when one nation is left the game ends with reason “Every other
   nation was eliminated”.

### Manual two-player test (complete game, Phase 9)

Games on the Sundered Reach created before Phase 10 use ruleset v5. Two browser windows (one normal, one private), each signed in with its own
account; host = first window.

1. Create a game in window 1, join with the invitation code in window 2, choose Varn and Ostra, start.
2. **Peace by default:** the Diplomacy card shows the other nation “At peace”; selecting your
   battalions in Elmsford shows Sablewater as “Closed — not at war, no military access”.
3. Build a workshop and (when you have 60 gold) a Military Academy; research military level 2 once
   40 points have accumulated.
4. **War:** on your turn press *Declare war* → *Confirm declaration*. The other window shows the
   war after its next poll. Move into enemy land, fight a battle, leave a battalion alone in an
   unfortified enemy province (occupied at the round end) and one in a fortified province (siege).
5. **War score:** the war card shows battles, sieges, occupation, total and net score.
6. **Peace:** the leader on turn presses *Negotiate peace*, adds terms (cede a province, gold,
   tribute, move 1,000–2,000 people), checks them, sends, and confirms the major settlement. On the
   other player's turn the proposal appears in its inbox: *Accept* (with confirmation), *Reject* or
   *Counter-proposal*.
7. After acceptance: ownership on the map changes, occupation ends, battalions in former enemy land
   withdraw home, sieges are lifted, a ten-round truce starts and tribute is paid every round (see
   the Tribute section and History).
8. Keep ending turns: after round 30 both windows show the result screen (winner or draw, standings,
   statistics) above the read-only final map; every command is refused from then on.

Shortcut for step 8 while testing locally (development database only): set the round of your
test game close to the end, e.g.
`UPDATE game.game SET current_round = 30, turn_number = 59, active_seat = 0 WHERE id = '<game id>';`
— then end both turns.

**Existing local games:** the next backend start applies migration V6 automatically. Games that
were already running get the Phase 4 starting economy (50 gold per nation and one active Tier 1
workshop in each capital); rounds played before the upgrade are not paid retroactively.

## Running tests

Backend (from `backend/`):

```bash
./mvnw test      # unit, JWT and web-slice tests (no Docker needed)
./mvnw verify    # + PostgreSQL integration tests via Testcontainers (needs Docker)
```

Integration tests (`*IT`) are **skipped, not failed**, when no Docker daemon is available;
CI requires Docker, so they cannot be silently skipped there. Authentication tests sign real
ES256 tokens against an in-process JWKS server — no Supabase project or network is needed.

Frontend (from `frontend/`):

```bash
npm test         # Vitest + Testing Library
npm run lint     # oxlint
npm run build    # type-check (tsc -b) + production build
```

## Environment variables

Backend — see [.env.example](.env.example). Spring Boot does not read `.env` files itself:
set the variables in your shell or IDE run configuration (Docker Compose does read `.env`).

| Variable | Default (`local`) | `prod` | Purpose |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `prod` (set in the Docker image) | Profile selection |
| `DB_URL` | `jdbc:postgresql://localhost:5432/strategy` | **required** | JDBC URL (Supabase: direct or session-mode connection) |
| `DB_USERNAME` / `DB_PASSWORD` | `strategy` / `strategy` | **required** | Database credentials |
| `DB_POOL_SIZE` | `10` | `10` | Hikari maximum pool size |
| `SERVER_PORT` | `8080` | `8080` | HTTP port |
| `SUPABASE_URL` | `http://127.0.0.1:54321` (Supabase CLI) | **required** | Project URL; issuer and JWKS are derived from it |
| `SUPABASE_JWT_AUDIENCE` | `authenticated` | `authenticated` | Required `aud` claim |
| `SUPABASE_JWT_ALGORITHMS` | `ES256,RS256` | `ES256,RS256` | Accepted (asymmetric) signing algorithms |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | **set to the frontend origin** | Browser origins allowed to call the API |
| `RATE_LIMITS_ENABLED` | `true` | `true` | Per-account request budgets (policies in `application.yml`) |
| `GAME_MAX_UNFINISHED_PER_PLAYER` | `10` | `10` | Lobbies and running games per account |
| `LEGAL_TERMS_VERSION` / `LEGAL_PRIVACY_VERSION` | `draft-1` | must match `frontend/src/legal/documents.ts` | Versions players accept |
| `LEGAL_REQUIRE_ACCEPTANCE` | `true` | `true` | Refuse game requests until accepted |
| `POSTGRES_*` | `strategy` …, port `5432` | — | Compose database only |

Production-only variables (migration role, proxy headers, thread limits) are listed in
[deploy/.env.production.example](deploy/.env.production.example).

Frontend — see [frontend/.env.example](frontend/.env.example) (copy to `.env.local`, git-ignored):

| Variable | Example | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Spring API origin |
| `VITE_SUPABASE_URL` | `https://your-project-ref.supabase.co` | Supabase project URL |
| `VITE_SUPABASE_PUBLISHABLE_KEY` | `sb_publishable_…` (or legacy anon key) | Public client key — **never** the secret/service-role key |

Never commit real credentials. `.env` and `.env.local` are git-ignored.

## Docker image

```bash
cd backend
docker build -t strategy-backend .
docker run --rm -p 8080:8080 -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e SUPABASE_URL=... -e CORS_ALLOWED_ORIGINS=... strategy-backend
```

The image runs the `prod` profile as a non-root user and logs structured JSON (ECS).

## Development philosophy

- **Modular monolith.** One deployable Spring Boot application with enforced module
  boundaries (checked by a test). No microservices, brokers, Redis or Kubernetes without
  a demonstrated need.
- **The server is authoritative.** Clients send intents; the server validates, applies and
  persists. The frontend uses Supabase for authentication only and never touches game tables.
- **Deterministic rules engine.** All game rules are pure Java functions over immutable
  inputs, using exact integer / fixed-point arithmetic.
- **Transactions are the unit of truth.** Mutations are all-or-nothing and serialized per
  game by a database row lock.
- **Approved vs proposed vs open.** Every rule in the documentation is labelled. Balance
  values are versioned ruleset configuration, not law.
- **Vertical slices.** Each phase ships backend and frontend for its features.

## Documentation map

| Document | Content |
|---|---|
| [docs/GAME_DESIGN.md](docs/GAME_DESIGN.md) | Gameplay rules, labelled APPROVED / PROPOSED / OPEN |
| [docs/ECONOMY.md](docs/ECONOMY.md) | Population, employment, income, upkeep, money arithmetic |
| [docs/COMBAT_AND_SIEGE.md](docs/COMBAT_AND_SIEGE.md) | Deterministic combat model, sieges, attrition, worked examples |
| [docs/DIPLOMACY.md](docs/DIPLOMACY.md) | Wars, alliances, war score, peace terms and settlement, tribute, match completion |
| [docs/TURN_SYSTEM.md](docs/TURN_SYSTEM.md) | Sequential turns, round processing, concurrency, idempotency |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, layering, security boundaries, deployment |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | Relational model, constraints, indexes, ER diagram |
| [docs/API_DESIGN.md](docs/API_DESIGN.md) | REST endpoints, auth, errors, conventions |
| [docs/BACKGROUND_JOBS.md](docs/BACKGROUND_JOBS.md) | Analytics, weekly digest, announcements, email outbox |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phases, deliverables, acceptance criteria, non-goals |
| [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md) | Open mechanics and the decision log |
| [docs/DATA_PRIVACY_INVENTORY.md](docs/DATA_PRIVACY_INVENTORY.md) | Personal data, browser storage, account lifecycle, operator decisions |
| [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md) | Phase 15B findings, fixes, rate limits, remaining risks |
| [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) | Deployment prerequisites, Supabase/SMTP settings, backups, rollback |
| [AGENTS.md](AGENTS.md) | Standing instructions for AI coding agents and contributors |
