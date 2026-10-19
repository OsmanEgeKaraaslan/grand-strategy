/**
 * Build-time configuration from Vite env variables (see frontend/.env.example).
 * Only public values belong here: the Supabase publishable (anon) key is designed to be
 * shipped to browsers. Never put a Supabase secret or service-role key in a VITE_ variable.
 */
export interface AppConfig {
  apiBaseUrl: string
  supabaseUrl: string
  supabasePublishableKey: string
}

export type ConfigResult = { ok: true; config: AppConfig } | { ok: false; missing: string[] }

export function readConfig(env: Record<string, string | undefined> = import.meta.env): ConfigResult {
  const values = {
    VITE_API_BASE_URL: env.VITE_API_BASE_URL,
    VITE_SUPABASE_URL: env.VITE_SUPABASE_URL,
    VITE_SUPABASE_PUBLISHABLE_KEY: env.VITE_SUPABASE_PUBLISHABLE_KEY,
  }
  const missing = Object.entries(values)
    .filter(([, value]) => !value || value.trim() === '')
    .map(([name]) => name)
  if (missing.length > 0) {
    return { ok: false, missing }
  }
  return {
    ok: true,
    config: {
      apiBaseUrl: values.VITE_API_BASE_URL!.replace(/\/+$/, ''),
      supabaseUrl: values.VITE_SUPABASE_URL!,
      supabasePublishableKey: values.VITE_SUPABASE_PUBLISHABLE_KEY!,
    },
  }
}
