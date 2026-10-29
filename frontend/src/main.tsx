import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createClient } from '@supabase/supabase-js'
import { App } from './App'
import { ApiFromAuth, ConfigError } from './AppProviders'
import { AuthProvider } from './auth/AuthContext'
import { isRecoveryRedirect, readAuthRedirectError } from './auth/redirects'
import { readConfig } from './config'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, refetchOnWindowFocus: true } },
})

const result = readConfig()
const root = createRoot(document.getElementById('root')!)

if (!result.ok) {
  root.render(<ConfigError missing={result.missing} />)
} else {
  // Before the client consumes the URL: a rejected email link is reported in its hash or query.
  const redirectError = readAuthRedirectError(window.location)
  const passwordRecovery = isRecoveryRedirect(window.location)
  // Supabase is used for authentication only; game data comes from the Spring API.
  const supabase = createClient(result.config.supabaseUrl, result.config.supabasePublishableKey, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  })
  root.render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <AuthProvider client={supabase} initialRedirectError={redirectError} initialPasswordRecovery={passwordRecovery}>
          <ApiFromAuth config={result.config}>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </ApiFromAuth>
        </AuthProvider>
      </QueryClientProvider>
    </StrictMode>,
  )
}
