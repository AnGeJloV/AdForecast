import { Navigate, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './auth/AuthContext'
import { PageLoader } from './components/Ui'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { DashboardPage } from './pages/DashboardPage'
import { CampaignsPage } from './pages/CampaignsPage'
import { CampaignPage } from './pages/CampaignPage'
import { UploadPage } from './pages/UploadPage'
import { ForecastsPage } from './pages/ForecastsPage'
import { AdminPage } from './pages/AdminPage'

function FullScreenLoader() {
  return (
    <div className="auth-loader">
      <PageLoader />
    </div>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) {
    return <FullScreenLoader />
  }
  if (!user) {
    return <Navigate to="/login" replace />
  }
  return children
}

function MarketerOnly({ children }: { children: ReactNode }) {
  const { isAdmin } = useAuth()
  if (isAdmin) {
    return <Navigate to="/admin" replace />
  }
  return children
}

function AdminOnly({ children }: { children: ReactNode }) {
  const { isAdmin } = useAuth()
  if (!isAdmin) {
    return <Navigate to="/" replace />
  }
  return children
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { user, loading, isAdmin } = useAuth()
  if (loading) {
    return <FullScreenLoader />
  }
  if (user) {
    return <Navigate to={isAdmin ? '/admin' : '/'} replace />
  }
  return children
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
      <Route path="/register" element={<GuestOnly><RegisterPage /></GuestOnly>} />
      <Route path="/" element={<RequireAuth><MarketerOnly><DashboardPage /></MarketerOnly></RequireAuth>} />
      <Route path="/campaigns" element={<RequireAuth><MarketerOnly><CampaignsPage /></MarketerOnly></RequireAuth>} />
      <Route path="/campaigns/:id" element={<RequireAuth><MarketerOnly><CampaignPage /></MarketerOnly></RequireAuth>} />
      <Route path="/upload" element={<RequireAuth><MarketerOnly><UploadPage /></MarketerOnly></RequireAuth>} />
      <Route path="/forecasts" element={<RequireAuth><MarketerOnly><ForecastsPage /></MarketerOnly></RequireAuth>} />
      <Route path="/admin" element={<RequireAuth><AdminOnly><AdminPage /></AdminOnly></RequireAuth>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
