import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createClient } from '@supabase/supabase-js'
import { App } from './App'
import { ApiFromAuth, ConfigError } from './AppProviders'
import { AuthProvider } from './auth/AuthContext'
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
  // Supabase is used for authentication only; game data comes from the Spring API.
  const supabase = createClient(result.config.supabaseUrl, result.config.supabasePublishableKey, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  })
  root.render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <AuthProvider client={supabase}>
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
