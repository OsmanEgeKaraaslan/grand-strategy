import cookies from './cookies.md?raw'
import privacy from './privacy.md?raw'
import terms from './terms.md?raw'

/**
 * The legal drafts shown on the public /legal pages. The texts are the single source: operators
 * review and edit the Markdown files in this folder (docs/DATA_PRIVACY_INVENTORY.md §6).
 *
 * The versions the player accepts come from the API (GET /me → terms), which reads them from the
 * server's configuration; SIGN_UP_VERSIONS must match that configuration so consent given on the
 * sign-up form is recorded instead of being asked for again.
 */
export const legalDocuments = {
  terms: { path: '/legal/terms', title: 'Terms of Service', source: terms },
  privacy: { path: '/legal/privacy', title: 'Privacy Policy', source: privacy },
  cookies: { path: '/legal/cookies', title: 'Cookie and Storage Notice', source: cookies },
} as const

export type LegalDocumentKey = keyof typeof legalDocuments

export const SIGN_UP_VERSIONS = { terms: 'draft-1', privacy: 'draft-1' } as const
