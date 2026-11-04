/**
 * Security headers of the deployed frontend (SECURITY_AUDIT §7, P2), written by the production build
 * as Cloudflare Pages' `_headers` file. The Content-Security-Policy allows only this site's own files
 * plus the origins the app really talks to: the game API, Supabase (sign-in) and, when CAPTCHA is on,
 * Cloudflare Turnstile. The Supabase session lives in localStorage, so this policy is what keeps an
 * injected script from loading code from anywhere else or sending a session away.
 */
export interface HeaderOrigins {
  apiBaseUrl?: string
  supabaseUrl?: string
  turnstileSiteKey?: string
}

const TURNSTILE = 'https://challenges.cloudflare.com'

function origin(url: string | undefined, name: string): string {
  if (!url || url.trim() === '') throw new Error(`${name} must be set for a production build (security headers)`)
  return new URL(url).origin
}

export function contentSecurityPolicy({ apiBaseUrl, supabaseUrl, turnstileSiteKey }: HeaderOrigins): string {
  const captcha = Boolean(turnstileSiteKey?.trim())
  const connect = [...new Set(["'self'", origin(apiBaseUrl, 'VITE_API_BASE_URL'), origin(supabaseUrl, 'VITE_SUPABASE_URL')])]
  return [
    "default-src 'self'",
    `script-src 'self'${captcha ? ` ${TURNSTILE}` : ''}`,
    // React sets inline style properties; no inline <style> or script is used.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src ${connect.join(' ')}`,
    `frame-src ${captcha ? TURNSTILE : "'none'"}`,
    "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ')
}

/** The `_headers` file for every path of the site. */
export function cloudflareHeaders(origins: HeaderOrigins): string {
  return [
    '/*',
    `  Content-Security-Policy: ${contentSecurityPolicy(origins)}`,
    '  Strict-Transport-Security: max-age=31536000; includeSubDomains',
    '  X-Content-Type-Options: nosniff',
    '  X-Frame-Options: DENY',
    '  Referrer-Policy: strict-origin-when-cross-origin',
    '  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()',
    '',
  ].join('\n')
}
