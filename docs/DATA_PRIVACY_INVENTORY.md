# Data Privacy Inventory

Status: **Phase 15A — engineering inventory, not legal advice.** It records what personal data the
software processes today, where it lives and how it is removed, so the operator (and their legal
adviser) can complete the legal drafts. It does not establish compliance with any law.

Legend: **Implemented** = in code and tested; **Operator** = a decision or configuration only the
operator can make (placeholders `[OPERATOR: …]` in the legal drafts).

## 1. Systems that hold personal data

| System | Role | Holds | Configured by |
| --- | --- | --- | --- |
| Supabase Auth | Sign-up, sign-in, sessions, confirmation and recovery emails | Email, password hash, `user_metadata` (display name requested at sign-up, consented legal versions), sign-in timestamps, refresh tokens, Supabase's own auth/audit logs (IP address, user agent) | Operator (Supabase dashboard) |
| SMTP provider (via Supabase) | Delivers confirmation and recovery emails | Recipient email, message content, delivery logs | Operator |
| Game database (PostgreSQL, schema `game`) | All game and social data | See §2 | This repository (Flyway) |
| Spring Boot API logs | Diagnostics | Request id, HTTP method and path on unexpected errors (paths can contain a username or game id), player and Supabase user id on account deletion, computer-opponent activity | This repository; retention by operator |
| Reverse proxy (Caddy) / hosting | TLS, routing | Access logs with client IP if enabled | Operator |
| Static hosting / CDN (frontend) | Serves the web app | Request logs at the provider | Operator |
| Browser of the player | Session | Supabase session in local storage (§3) | Supabase JS client |

The backend never receives or stores email addresses or passwords; it identifies accounts by the
Supabase user id (`sub` claim) only.

## 2. Game database inventory

| Table / column | Personal data | Purpose | Visible to | Removed on deletion |
| --- | --- | --- | --- | --- |
| `player.supabase_user_id` | Pseudonymous account id | Links the token to the account | Server only | Kept (refuses the deleted user's still-valid tokens); operator removes the Supabase user |
| `player.display_name`, `username`, `emblem` | Public profile | Identify players to each other | All players | Replaced by `Deleted#<12 hex>` / `deleted_<12 hex>` / default emblem |
| `player.match_history_public`, `created_at`, `updated_at` | Profile settings, timestamps | Profile | Owner; join date public | History hidden; timestamps kept |
| `player.terms_version`, `privacy_version`, `terms_accepted_at`, `terms_acceptance` | Legal acceptance record | Evidence of acceptance | Owner | Kept (record of the acceptance) — **Operator** to confirm |
| `player.deleted_at` | Deletion time | Account lifecycle | Server | — |
| `game_player`, nations, game commands, battles, diplomacy, results | Gameplay of the account | The game itself; match history | Players of the same game | Kept, linked to the anonymized player (integrity of other players' games) |
| `friend_request`, `friendship`, `player_block` | Social graph | Friends and blocks | The two players involved | Deleted (both directions) |
| `notification` | Events for the player, naming other players | Notifications | Recipient | Deleted (for and about the player) |
| `game_invitation` | Who invited whom to which game | Invitations | Inviter, invitee | Pending ones cancelled (`reason = ACCOUNT_DELETED`); answered ones kept with their game |
| `matchmaking_ticket` | Search times, heartbeats, opponent | Quick Match | Owner | A waiting search is cancelled; matched tickets kept with their game |

## 3. Browser storage

Audited in the frontend source (Phase 15A): the only storage is the Supabase JS client's session in
`localStorage` (`sb-<project-ref>-auth-token`, and a PKCE code verifier only during PKCE flows).
There are no cookies set by the app, no analytics, advertising or third-party scripts, and no
external fonts. The session is strictly necessary for signing in, so **no consent banner is
implemented**; the Cookie and Storage Notice documents it. If non-essential storage is ever added,
the notice and the consent decision must be revisited first. Whether the CDN sets its own cookies
(for example bot protection) depends on its configuration — **Operator**.

## 4. Account lifecycle (implemented)

- **Sign-up**: Supabase Auth with email confirmation. The sign-up form requires consent to the
  current Terms and Privacy Policy; the consented versions travel in `user_metadata` and are recorded
  in `terms_acceptance` when the account is created on first login, only if they are the current ones.
- **Existing accounts** (created before V20) have no acceptance; they are asked to accept on their next
  visit. With `strategy.legal.require-acceptance=true` (default) the API answers `403
  TERMS_NOT_ACCEPTED` to everything except `/api/v1/me` and its sub-paths until they do.
- **New document version**: raise `LEGAL_TERMS_VERSION` / `LEGAL_PRIVACY_VERSION` (and
  `SIGN_UP_VERSIONS` in `frontend/src/legal/documents.ts`); everyone is asked again.
- **Password reset**: Supabase recovery email → `/reset-password`; the forgot-password page shows the
  same message for every address (no account enumeration).
- **Password change**: Account page; the current password is verified by a fresh Supabase sign-in.
- **Deletion**: Account page → `POST /api/v1/me/deletion`. Requires typing DELETE and a password
  sign-in within `strategy.account.deletion-reauthentication-window` (default 10 minutes, from the
  token's `amr` claim). Refused with `409 ACCOUNT_DELETION_BLOCKED` while the player is seated in an
  unfinished game. Effects as in §2; afterwards every request of the account gets `403
  ACCOUNT_DELETED` and the frontend signs out.
- **Supabase Auth user removal** is manual (the backend deliberately holds no service-role key):
  the operator lists deleted accounts with
  `SELECT supabase_user_id, deleted_at FROM game.player WHERE deleted_at IS NOT NULL;` and deletes
  those users in the Supabase dashboard (Authentication → Users). See
  [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md).

## 5. Open decisions for the operator

| Topic | Needed for | Status |
| --- | --- | --- |
| Operator identity, address, contact, representative/DPO | Privacy Policy §1, Terms §1/§9 | **Operator** |
| Legal bases per purpose | Privacy Policy §3 | **Operator** |
| Retention periods (accounts, logs, backups, Supabase Auth after deletion) | Privacy Policy §5 | **Operator** |
| Deadline for removing the Supabase user after deletion | Privacy Policy §5 | **Operator** |
| Deletion when a game can never finish (forfeit or leave rules) | Deletion flow | **OPEN** (Q-AC1 in OPEN_QUESTIONS.md) |
| Whether `terms_acceptance` rows are kept after deletion | Evidence vs. minimisation | **Operator** (kept today) |
| Minimum age | Terms §2, Privacy §8 | **Operator** |
| Governing law, disputes, consumer statements | Terms §8 | **Operator** |
| Hosting/SMTP providers, regions, transfers | Privacy §4 | **Operator** |
| Proxy access logs (IP) on or off, and retention | Privacy §2 | **Operator** |
| Data export (portability) | Rights | Not implemented — **Operator** to decide if required |

## 6. Where the documents live

The drafts are Markdown in `frontend/src/legal/` (`terms.md`, `privacy.md`, `cookies.md`), rendered
on the public routes `/legal/terms`, `/legal/privacy` and `/legal/cookies` (linked from sign-in,
sign-up, the acceptance screen and the account page). Placeholders render highlighted so an
unfinished draft is visible. They are **drafts requiring operator and legal review**; nothing in
this repository claims they are compliant.
