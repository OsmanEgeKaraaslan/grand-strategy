# Cookie and Storage Notice

**DRAFT — version draft-1. Not reviewed by a lawyer.** It describes what the application stores in your browser as of this version; passages marked `[OPERATOR: …]` must be completed by the operator.

## What we store

The game sets **no cookies of its own** and uses **no analytics, advertising or tracking** technologies.

It stores one kind of data in your browser's local storage:

| Item | Set by | Purpose | Lifetime |
| --- | --- | --- | --- |
| `sb-<project>-auth-token` | Supabase Auth client | Keeps you signed in: your session's access and refresh tokens | Until you log out, or the session expires or is revoked |
| `sb-<project>-auth-token-code-verifier` (only during some sign-in flows) | Supabase Auth client | Completes a sign-in link securely | Removed when the sign-in completes |

This storage is strictly necessary to provide the service you asked for (being signed in), so no consent banner is shown. Logging out removes the session from your browser.

## Third parties

`[OPERATOR: whether the website host or CDN (for example Cloudflare) sets its own cookies — such as bot-protection cookies — with the configuration you use, and their purpose]`.

## Changes

If the game ever adds storage that is not strictly necessary (for example analytics), this notice will change first and you will be asked for consent where the law requires it.
