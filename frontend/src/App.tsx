import { Navigate, Route, Routes } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
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
      <Route
        path="/"
        element={
          <RequireAuth>
            <DashboardPage />
          </RequireAuth>
        }
      />
      <Route
        path="/games/:gameId"
        element={
          <RequireAuth>
            <LobbyPage />
          </RequireAuth>
        }
      />
      <Route
        path="/games/:gameId/play"
        element={
          <RequireAuth>
            <GamePage />
          </RequireAuth>
        }
      />
      <Route
        path="/profile"
        element={
          <RequireAuth>
            <MyProfileRedirect />
          </RequireAuth>
        }
      />
      <Route
        path="/players/:username"
        element={
          <RequireAuth>
            <ProfilePage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
