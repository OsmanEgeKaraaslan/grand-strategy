import { describe, expect, it } from 'vitest'
import { cloudflareHeaders, contentSecurityPolicy } from './securityHeaders'

describe('frontend security headers', () => {
  const origins = { apiBaseUrl: 'https://api.example.org/', supabaseUrl: 'https://abc.supabase.co' }

  it('allow only this site, the API and Supabase', () => {
    const csp = contentSecurityPolicy(origins)
    expect(csp).toContain("script-src 'self';")
    expect(csp).toContain("connect-src 'self' https://api.example.org https://abc.supabase.co")
    expect(csp).toContain("frame-src 'none'")
    expect(csp).toContain("frame-ancestors 'none'")
    expect(csp).toContain("object-src 'none'")
    expect(csp).not.toContain('unsafe-eval')
  })

  it('add Cloudflare Turnstile only when CAPTCHA is configured', () => {
    const csp = contentSecurityPolicy({ ...origins, turnstileSiteKey: '0x4AAA' })
    expect(csp).toContain("script-src 'self' https://challenges.cloudflare.com")
    expect(csp).toContain('frame-src https://challenges.cloudflare.com')
  })

  it('refuse a production build without the API or Supabase origin', () => {
    expect(() => contentSecurityPolicy({ supabaseUrl: 'https://abc.supabase.co' })).toThrow(/VITE_API_BASE_URL/)
  })

  it('write the Cloudflare Pages file for every path', () => {
    const file = cloudflareHeaders(origins)
    expect(file.startsWith('/*\n')).toBe(true)
    expect(file).toContain('Strict-Transport-Security: max-age=31536000; includeSubDomains')
    expect(file).toContain('X-Frame-Options: DENY')
  })
})
