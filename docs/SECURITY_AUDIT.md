# Security Audit — Phase 15B

Date: 2026-10-22. Scope: the code in this repository (Spring Boot API, React frontend, Flyway
schema, CI, container image). References: OWASP ASVS 4.0 (levels 1–2, selected chapters) and the
OWASP API Security Top 10 (2023). **This is a code-level review with regression tests, not a
penetration test.** Passing tests and scans do not make the application "secure"; §6 lists what
remains.

Severity: **High** = exploitable for account/game takeover or service outage by one user;
**Medium** = abuse or exposure needing effort or limited in impact; **Low** = hardening;
**Info** = verified, nothing to fix.

## 1. Summary of findings

| # | Finding | Severity | Status | Fix / test |
|---|---|---|---|---|
| F1 | No request rate limits: one account could flood search, friend requests, invitations, lobby creation, invitation-code guessing, matchmaking and game commands (API4 Unrestricted Resource Consumption) | High | **Fixed** | Per-account token buckets (§4); `RateLimitIT`, `RateLimiterTest` |
| F2 | Invitation codes guessable by brute force with no throttle | Medium | **Fixed** | `game-join` policy, 30 per 10 min; `RateLimitIT.guessingInvitationCodesIsThrottled` |
| F3 | Unbounded lobbies/games per account (storage and computer-opponent work) | Medium | **Fixed** | `strategy.game.max-unfinished-games` (10) on create and join; `RateLimitIT.aPlayerCanHoldOnlySoManyUnfinishedGames` |
| F4 | Computer-opponent worker queue unbounded; the 15 s recovery sweep could queue the same game repeatedly | Medium | **Fixed** | One queue entry per game, bounded queue (`strategy.ai.max-queued-games`), dropped triggers are picked up by the sweep; `AiTriggersTest` |
| F5 | No request body size limit (JSON bodies of any size were read) | Medium | **Fixed** | `RequestSizeLimitFilter`, 64 KB, 413 `PAYLOAD_TOO_LARGE`; `RateLimitIT.oversizedBodiesAreRefusedBeforeTheyAreRead` |
| F6 | Runtime dependencies with published advisories: Tomcat 11.0.24 (3 critical, in DIGEST/FORM authenticators and an access-control issue), Jackson 3.1.5 (5 databind, 2 core; several DoS) | High (as reported) | **Fixed** | Spring Boot 4.1.1 → **4.1.2** (patch release; brings Tomcat 11.0.27, Jackson 3.1.7). Rescan: 0 advisories (§5) |
| F7 | API responses lacked CSP and Referrer-Policy | Low | **Fixed** | `default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`; `RateLimitIT.apiResponsesCarryProtectiveHeaders` |
| F8 | A deleted account's Supabase token stays valid until it expires (no service-role key to revoke it) | Medium | **Mitigated** | Backend answers `403 ACCOUNT_DELETED` to every request of a deleted account; frontend signs out; operator removes the Supabase user (`AccountIT`) |
| F9 | Account deletion with only a stolen/borrowed session | Medium | **Mitigated** | Requires a password sign-in within 10 min (`amr` claim) and a typed confirmation (`AccountIT.deletionNeedsATypedConfirmationAndARecentSignIn`) |
| F10 | Password change in the UI could be done with a session alone | Low | **Fixed** | Account page verifies the current password by a fresh sign-in first (`Account.test.tsx`) |
| F11 | Account enumeration through recovery / resend | Info | Verified | Same message for every address; only throttling and network errors are shown (`Account.test.tsx`). Sign-up enumeration depends on Supabase "Confirm email" being on (§6) |
| F12 | Broken object-level authorization (API1) across all game endpoints | Info | Verified — no defect found | `AuthorizationMatrixIT`: 62 game endpoints × anonymous / outsider / foreign ids / other nation's assets / not your turn |
| F13 | Impersonation via client-supplied player ids | Info | Verified | No request DTO carries "who I am"; identity only from the token (`CurrentPlayerArgumentResolver`); module test forbids web access to computer actors |
| F14 | Forged results / unauthorized AI execution | Info | Verified | No endpoint writes results or triggers computer play; results are written only by round processing; `ModuleBoundariesTest.noWebLayerCanActForAComputer` |
| F15 | SQL injection | Info | Verified | All SQL uses bind parameters; the few concatenated fragments are code constants (column lists, a `LIMIT` int, a column chosen from two literals) |
| F16 | XSS | Info | Verified | React escaping only; no `dangerouslySetInnerHTML`/`innerHTML`/`eval`; legal Markdown is rendered to React elements and only `/…` or `https://` links (`Account.test.tsx`) |
| F17 | Unsafe redirects | Info | Verified | Post-login redirect uses router state (in-app paths only); Supabase links redirect to `window.location.origin` routes that must also be on Supabase's allow-list |
| F18 | Secrets and personal data in logs | Info | Verified | No tokens or emails logged; problem details never include stack traces (`server.error.include-*: never`); request paths can contain a username (only logged on unexpected errors) |

## 2. Authentication and session (ASVS V2, V3; API2)

- Tokens: Supabase access tokens verified against the project JWKS; asymmetric algorithms only
  (ES256/RS256), `iss`, `aud=authenticated`, `role=authenticated`, `exp`, `nbf`, canonical UUID
  `sub`, non-anonymous (`SupabaseJwtDecodersTest`, `SecurityConfigTest`, 23 cases incl. HS256,
  `alg:none`, foreign key, expired, wrong audience/issuer/role).
- No server sessions, no cookies: CSRF protection is not applicable to the bearer-token API and is
  disabled deliberately. CORS: explicit origins, no credentials.
- Session lifetime, refresh-token rotation, reuse detection and inactivity timeouts are **Supabase
  project settings** (§6, PRODUCTION_READINESS.md). Logging out calls `supabase.auth.signOut()`
  (revokes the refresh token); an already-issued access token stays valid until it expires — keep
  the JWT expiry short (Supabase default 1 h).
- Password reset, email confirmation, resend and password change run on Supabase Auth directly
  from the browser with the publishable key only; no app endpoint handles credentials, so the app has
  no recovery endpoint to rate-limit — Supabase applies its own email and sign-in rate limits (§6).

## 3. Authorization audit (API1, API3, API5)

Every controller was read; every game-scoped handler resolves the caller's membership through the
game module (404 `GAME_NOT_FOUND` for non-members, indistinguishable from a missing game) before
anything else, and every lookup of a city, battalion, battle, siege, war, proposal, conference, call
or alliance is scoped by `game_id` (and nation where it matters). `AuthorizationMatrixIT` turns this
into a regression matrix:

| Caller | Expectation | Endpoints |
|---|---|---|
| Anonymous | 401 `UNAUTHENTICATED`, no state change | All 62 game endpoints + 28 account/social/lobby/matchmaking endpoints |
| Signed-in outsider | 404 `GAME_NOT_FOUND`, no state change | All 62 game endpoints (reads, lobby actions, every command) |
| Member using ids of another game | 4xx, foreign ids never echoed, other game unchanged | Every endpoint taking a city or battalion id |
| Member on their own turn targeting another nation's city/battalion | 4xx, no state change | Every command on cities and battalions |
| Member when it is not their turn | 409 `NOT_ACTIVE_PLAYER` | End turn, recruit, build |
| Lobby outsider / non-host | 404 / 403 `NOT_HOST` | View, nation choice, start, sent invitations |
| Other players' social objects | 404, unchanged | Friend request accept/decline/cancel, marking notifications read |

Existing suites already cover host-only invitations, blocked players, double-accept races,
matchmaking ownership, peace-conference participants and computer seats (InvitationsIT,
FriendsIT, MatchmakingIT, PeaceConferenceIT, ComputerTurnsIT, ComputerActorLimitsIT).

Function-level authorization (API5): there are no admin or operator endpoints. Mass assignment
(API3): request records list their fields explicitly and unknown JSON properties are rejected
(`fail-on-unknown-properties`).

## 4. Rate limits and resource protection (API4, ASVS V11/V12)

Implemented in `platform.ratelimit` without a new dependency (the plan's Bucket4j was not needed for
one instance). Budgets are per **account** (verified token subject), so forwarded-for headers are
never consulted and a shared NAT address does not penalise other players. Requests without a valid
token are refused by the security chain before reaching any limited endpoint.

| Policy | Budget | Applies to |
|---|---|---|
| `api` | 600 / min | Every authenticated API request |
| `game-commands` | 120 / min | State-changing game and lobby endpoints (incl. end turn, peace-term checks) |
| `game-creation` | 10 / h | `POST /games` |
| `game-join` | 30 / 10 min | `POST /games/join` (invitation codes) |
| `player-search` | 30 / min | `GET /players/search` |
| `profile-reads` | 60 / min | `GET /profiles/{username}` |
| `friend-requests` | 30 / h | `POST /friends/requests` |
| `invitations` | 30 / h | `POST /invitations` |
| `social-actions` | 60 / min | Other social writes (answers, blocks, notifications read, matchmaking cancel/acknowledge) |
| `matchmaking` | 20 / 10 min | `POST /matchmaking/quick/join` |
| `matchmaking-heartbeat` | 30 / min | `POST /matchmaking/quick/heartbeat` (the client sends 6 / min) |
| `account` | 20 / 10 min | `PATCH /me`, terms acceptance, deletion |

Exceeding a budget returns **429** `RATE_LIMITED` with `Retry-After`; the frontend shows "You are
doing that too often…". All values are configuration (`strategy.rate-limits.*`,
`RATE_LIMITS_ENABLED`); an unknown policy name on an endpoint stops the application at startup.

Other bounds: 64 KB request bodies; list sizes in requests (`@Size`, e.g. 100 battalions, 50 peace
terms); search results 10; notifications 30 per page and 200 kept; 50 pending friend requests; 10
pending invitations per lobby; 10 unfinished games per account (5 for Quick Match); computer turns
budgeted per turn (`maxActionsPerTurn`, `maxFailuresPerTurn`), one run per game at a time, 2 worker
threads, at most 500 queued games; HikariCP pool 10.

**Single-instance limitation:** buckets live in memory. With several API instances each enforces its
own budget (effective limit × instances), and a restart resets them. Moving to a shared store is
only needed when scaling out (§6).

## 5. Dependency review

| Ecosystem | Tool | Result |
|---|---|---|
| npm (frontend, all deps incl. dev) | `npm audit` | 0 vulnerabilities |
| Maven runtime (105 packages) | OSV.dev batch query of `dependency:list` | Before: Tomcat 11.0.24 (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5), jackson-databind 3.1.5 (GHSA-cxp5-3px4-pw24, -gx83-3vf8-gh7j, -q4xh-88c3-wmh7, -wjgm-6hv5-3cvf, -wv8q-qhhj-9h54), jackson-core 3.1.5 (GHSA-7hhh-6rmp-j9qf, -p6pp-m3f8-5c89). After Spring Boot 4.1.2: **0** |

Exposure note: the Tomcat authenticator advisories concern DIGEST/FORM authentication, which this
application does not use; the upgrade was still applied because it is a patch release and the
Jackson DoS issues affect every JSON request. Maven test-scope dependencies and the GitHub Actions
were not scanned; OWASP Dependency-Check was not run (it needs the NVD feed). Recommended: enable
Dependabot (or an OSV scan in CI) for Maven, npm and Actions — **operator decision**, not done here.

## 6. Remaining risks and operator prerequisites

- **Supabase settings** decide part of the security posture: "Confirm email" must be on (otherwise
  sign-up reveals registered addresses and unverified emails can play); password minimum length and
  leaked-password protection; email and sign-in rate limits; JWT expiry; refresh-token rotation and
  reuse interval; allowed redirect URLs limited to the production origin; custom SMTP. See
  PRODUCTION_READINESS.md §5.
- **Unauthenticated floods** (invalid tokens, health endpoint) are not rate-limited by the app; the
  reverse proxy/CDN must absorb them. Caddy has no built-in rate limiter — use the hosting
  provider's firewall or a CDN in front of the API if this becomes a problem.
- **Multi-instance** deployments need a shared rate-limit store (§4).
- **Supabase user removal after account deletion is manual** until an operator tool with the
  service-role key exists outside the app.
- **Frontend CSP** is a deployment header (template in PRODUCTION_READINESS.md §6); the app does not
  set it itself.
- **No automated DAST/penetration test** was performed; no third-party review of the legal drafts.
- **Dependency scanning is not in CI yet** (§5).
- Supabase access tokens are stored in `localStorage` (supabase-js default), readable by any script
  running on the origin; mitigated by the absence of third-party scripts and by the CSP.
