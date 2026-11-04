import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import { cloudflareHeaders } from './securityHeaders.ts'

/** Writes Cloudflare Pages' `_headers` (CSP and other security headers) into every production build. */
function securityHeaders(mode: string): Plugin {
  return {
    name: 'security-headers',
    apply: 'build',
    generateBundle() {
      const env = loadEnv(mode, process.cwd(), 'VITE_')
      if (!env.VITE_API_BASE_URL || !env.VITE_SUPABASE_URL) {
        // CI builds only check that the app compiles; a deployable build always has both values.
        this.warn('VITE_API_BASE_URL or VITE_SUPABASE_URL is not set: no _headers file written (not deployable)')
        return
      }
      this.emitFile({
        type: 'asset',
        fileName: '_headers',
        source: cloudflareHeaders({
          apiBaseUrl: env.VITE_API_BASE_URL,
          supabaseUrl: env.VITE_SUPABASE_URL,
          turnstileSiteKey: env.VITE_TURNSTILE_SITE_KEY,
        }),
      })
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [react(), securityHeaders(mode)],
  server: {
    // The backend's local CORS allow-list contains this origin (CORS_ALLOWED_ORIGINS).
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
}))
