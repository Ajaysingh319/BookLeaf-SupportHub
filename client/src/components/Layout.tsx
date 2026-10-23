import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../features/auth/useAuth.ts'
import { useConfig } from '../features/config/useConfig.ts'

const AUTHOR_LINKS = [
  { to: '/author', label: 'Dashboard', end: true },
  { to: '/author/books', label: 'My Books', end: false },
  { to: '/author/tickets', label: 'My Tickets', end: true },
  { to: '/author/tickets/new', label: 'New Ticket', end: false },
]

const ADMIN_LINKS = [{ to: '/admin/tickets', label: 'Ticket Queue', end: false }]

export function Layout() {
  const { user, logout } = useAuth()
  const { brandName } = useConfig()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)
  const links = user?.role === 'ADMIN' ? ADMIN_LINKS : AUTHOR_LINKS

  const handleSignOut = async () => {
    setSigningOut(true)
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <div className="container header-inner">
          <span className="brand">{brandName}</span>
          <nav aria-label="Main">
            <ul className="nav-list">
              {links.map((link) => (
                <li key={link.to}>
                  <NavLink to={link.to} end={link.end} className="nav-link">
                    {link.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <div className="user-box">
            <span className="user-name">{user?.name}</span>
            <button type="button" className="button button-secondary" onClick={handleSignOut} disabled={signingOut}>
              {signingOut ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </div>
      </header>
      <main id="main" className="container main">
        <Outlet />
      </main>
    </>
  )
}
