import { NavLink, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'
import { initials } from '../utils/format'
import { Brand } from './Brand'

const MARKETER_LINKS = [
  { to: '/', label: 'Дашборд', end: true },
  { to: '/campaigns', label: 'Кампании', end: false },
  { to: '/upload', label: 'Загрузка данных', end: false },
  { to: '/forecasts', label: 'Прогнозы', end: false },
]

export function Layout({ children }: { children: ReactNode }) {
  const { user, isAdmin, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <>
      <div className="glow" />
      <nav>
        <div className="nav-left">
          <Brand to={isAdmin ? '/admin' : '/'} />
          <div className="nav-links">
            {isAdmin
              ? (
                <NavLink to="/admin" className={({ isActive }) => (isActive ? 'active' : undefined)}>
                  Администрирование
                </NavLink>
                )
              : MARKETER_LINKS.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.end}
                  className={({ isActive }) => (isActive ? 'active' : undefined)}
                >
                  {link.label}
                </NavLink>
              ))}
          </div>
        </div>
        <div className="nav-right">
          <div className="user-chip" title={user?.organization ? `Организация: ${user.organization}` : undefined}>
            <div className="avatar">{initials(user?.fullName ?? '')}</div>
            <span>
              {user?.fullName}
              <br />
              {user?.role}
              {user?.organization ? ` · ${user.organization}` : ''}
            </span>
          </div>
          <button className="ghost-btn" onClick={handleLogout}>Выйти</button>
        </div>
      </nav>
      <main>{children}</main>
    </>
  )
}
