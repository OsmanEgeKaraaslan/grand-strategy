import { Navigate, Route, Routes } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { TermsGate } from './auth/TermsGate'
import { AccountDeletedPage, AccountPage } from './pages/AccountPage'
import { ForgotPasswordPage } from './pages/ForgotPasswordPage'
import { LegalPage } from './pages/LegalPage'
import { ResetPasswordPage } from './pages/ResetPasswordPage'
import { AdminPage } from './pages/AdminPage'
import { DashboardPage } from './pages/DashboardPage'
import { GamePage } from './pages/GamePage'
import { LobbyPage } from './pages/LobbyPage'
import { LoginPage } from './pages/LoginPage'
import { MyProfileRedirect, ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/account-deleted" element={<AccountDeletedPage />} />
      <Route path="/legal/terms" element={<LegalPage document="terms" />} />
      <Route path="/legal/privacy" element={<LegalPage document="privacy" />} />
      <Route path="/legal/cookies" element={<LegalPage document="cookies" />} />
      <Route
        path="/account"
        element={
          <RequireAuth>
            <AccountPage />
          </RequireAuth>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <TermsGate>
              <DashboardPage />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route
        path="/games/:gameId"
        element={
          <RequireAuth>
            <TermsGate>
              <LobbyPage />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route
        path="/games/:gameId/play"
        element={
          <RequireAuth>
            <TermsGate>
              <GamePage />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route
        path="/profile"
        element={
          <RequireAuth>
            <TermsGate>
              <MyProfileRedirect />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route
        path="/players/:username"
        element={
          <RequireAuth>
            <TermsGate>
              <ProfilePage />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route
        path="/admin"
        element={
          <RequireAuth>
            <TermsGate>
              <AdminPage />
            </TermsGate>
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
