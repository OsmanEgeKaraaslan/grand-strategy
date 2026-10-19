import { useMemo, type ReactNode } from 'react'
import { ApiProvider } from './api/ApiContext'
import { createApiClient } from './api/client'
import { useAuth } from './auth/AuthContext'
import type { AppConfig } from './config'

/** Builds the API client from the current Supabase session. */
export function ApiFromAuth({ config, children }: { config: AppConfig; children: ReactNode }) {
  const { accessToken, signOut } = useAuth()
  const client = useMemo(
    () =>
      createApiClient({
        baseUrl: config.apiBaseUrl,
        accessToken,
        // A rejected token means the session is no longer valid: sign out so the router
        // sends the player back to the login page.
        onUnauthorized: () => void signOut(),
      }),
    [config.apiBaseUrl, accessToken, signOut],
  )
  return <ApiProvider client={client}>{children}</ApiProvider>
}

export function ConfigError({ missing }: { missing: string[] }) {
  return (
    <div className="auth-frame">
      <div className="auth-card">
        <h1>Configuration missing</h1>
        <p className="muted">Copy frontend/.env.example to frontend/.env.local and set:</p>
        <ul>
          {missing.map((name) => (
            <li key={name}>
              <code>{name}</code>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
