import { describe, expect, it } from 'vitest'
import { readConfig } from './config'

describe('readConfig', () => {
  it('reads and normalizes the public configuration', () => {
    expect(
      readConfig({
        VITE_API_BASE_URL: 'http://localhost:8080/',
        VITE_SUPABASE_URL: 'http://127.0.0.1:54321',
        VITE_SUPABASE_PUBLISHABLE_KEY: 'pk',
      }),
    ).toEqual({
      ok: true,
      config: { apiBaseUrl: 'http://localhost:8080', supabaseUrl: 'http://127.0.0.1:54321', supabasePublishableKey: 'pk' },
    })
  })

  it('lists missing variables instead of failing obscurely', () => {
    expect(readConfig({ VITE_API_BASE_URL: 'http://localhost:8080', VITE_SUPABASE_URL: ' ' })).toEqual({
      ok: false,
      missing: ['VITE_SUPABASE_URL', 'VITE_SUPABASE_PUBLISHABLE_KEY'],
    })
  })
})
