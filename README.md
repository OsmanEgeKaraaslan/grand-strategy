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
| 1 — Backend foundation | Done. Spring Boot 4.1.1, PostgreSQL + Flyway, default-deny security, health endpoint, problem-details errors, Docker Compose, Dockerfile, CI |
| 2 — Auth, players, lobby, frontend | Done. Supabase sign-up/sign-in, JWT verification, player profiles, private two-player lobbies with invitation codes, React frontend |
| 3 — Nations, map, game start, sequential turns | Done. Fictional map, nation choice, transactional start, sequential turns with exactly-once round completion, SVG map game screen |
| 4 — Population, economy, workshops | Implemented: exact milligold economy (farming, Tier 1/2 workshops by priority), treasuries, Tier 1 workshop construction with Q-E8 timing, round-end income exactly once, occupied cities pay nobody, economy panels in the game screen. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 5 — Recruitment, armies, movement, battles | Implemented: recruitment from city population, starting forces (ruleset v2), per-battalion movement, armies as labels, deterministic battles with frontline protection, flanking and terrain (provisional coefficients), retreat or destruction, battle reports, simple occupation (control only), battalion upkeep. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 6 — Advanced combat, reinforcement, disbanding | Implemented: combat engine reviewed with 24 golden balance scenarios (coefficients unchanged, still provisional), clearer battle reports, reinforcement (≤ 500 per turn, pro-rata cost, real civilians), origin-aware soldier cohorts, disbanding with per-origin returns. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 7 — Sieges, fortifications, repairs | Implemented: fortresses (levels 1–3, build, upgrade, repair, upkeep), sieges of fortified provinces across global rounds (5% civilian attrition, artillery shortens sieges but damages the fortress and a workshop), capture of control only, workshop repairs, besieged-city restrictions; unfortified provinces keep simple occupation. All backend (unit + PostgreSQL) and frontend tests pass locally |
| 8 — Technology, research, doctrines, growth | Implemented: Military Academy, University and Barracks producing national research points, military and civilian technology levels, doctrines (mountain, river, siege, logistics) that change real combat, siege and upkeep calculations, Tier 1 → 2 workshop upgrades, 0.5% population growth. All backend (unit + PostgreSQL) and frontend tests pass locally |
| **9 — Diplomacy, war, peace, victory** | **Implemented**: peace by default, war declarations that gate movement, battles, occupation and sieges, normal alliances and defensive pacts, server-side war score, peace proposals settled atomically (provinces, reparations, ten-round tribute, population transfers), truce and withdrawal, round-30 match completion with stored results. All backend (unit + PostgreSQL) and frontend tests pass locally |
| Your manual playtest | Next: play a complete game (see “Manual two-player test” below), then Phase 10 |

Playable so far: create a lobby, invite a rival, choose nations, start, take turns on the map, earn gold and build workshops, recruit, reinforce and disband battalions, move them, fight battles, occupy open provinces, besiege fortresses and repair the damage, build research institutions, unlock technology and doctrines, upgrade workshops to Tier 2 and watch cities grow. Declare war, form alliances, negotiate peace with territory, gold, tribute and people, and play a complete game to its round-30 result. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring Web MVC, Spring Security (OAuth2 resource server), Spring Data JPA / Hibernate, Bean Validation, Actuator, Maven (wrapper included) |
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
   `http://localhost:5173/login` to the redirect URLs (the confirmation link returns there).
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

### Manual two-player test (complete game, Phase 9)

New games use ruleset v5. Two browser windows (one normal, one private), each signed in with its own
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
| `POSTGRES_*` | `strategy` …, port `5432` | — | Compose database only |

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
| [AGENTS.md](AGENTS.md) | Standing instructions for AI coding agents and contributors |
