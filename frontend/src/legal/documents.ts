import cookies from './cookies.md?raw'
import privacy from './privacy.md?raw'
import terms from './terms.md?raw'

/**
 * The legal documents shown on the public /legal pages. The Markdown files in this folder are the
 * single source (docs/DATA_PRIVACY_INVENTORY.md §6); `{{CONTACT_EMAIL}}` in them is replaced by the
 * deployment's contact address (VITE_CONTACT_EMAIL, frontend/.env.example).
 *
 * The versions the player accepts come from the API (GET /me → terms), which reads them from the
 * server's configuration; SIGN_UP_VERSIONS must match that configuration so consent given on the
 * sign-up form is recorded instead of being asked for again.
 */
export function withContact(source: string, contactEmail: string | undefined = import.meta.env.VITE_CONTACT_EMAIL): string {
  const address = contactEmail?.trim()
  return source.split('{{CONTACT_EMAIL}}').join(address ? address : 'the contact address (not yet published)')
}

export const legalDocuments = {
  terms: { path: '/legal/terms', title: 'Terms of Service', source: withContact(terms) },
  privacy: { path: '/legal/privacy', title: 'Privacy Policy', source: withContact(privacy) },
  cookies: { path: '/legal/cookies', title: 'Cookie and Storage Notice', source: withContact(cookies) },
} as const

export type LegalDocumentKey = keyof typeof legalDocuments

export const SIGN_UP_VERSIONS = { terms: '1.0', privacy: '1.0' } as const
