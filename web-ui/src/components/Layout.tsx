import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'

const links = [
  { to: '/',          label: 'Дашборд',  icon: '⬡', end: true },
  { to: '/projects',  label: 'Проекты',  icon: '◉', end: false },
  { to: '/scenarios', label: 'Сценарии', icon: '◈', end: false },
  { to: '/nodes',     label: 'Узлы',     icon: '◎', end: false },
]

export function Layout() {
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    const saved = localStorage.getItem('theme')
    return saved === 'light' ? 'light' : 'dark'
  })

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('theme', theme)
  }, [theme])

  const isLight = theme === 'light'

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-logo">
          <span className="logo-accent">DST</span>
          <span className="logo-text">Distributed Test Runner</span>
        </div>

        <nav className="sidebar-nav">
          {links.map(l => (
            <NavLink
              key={l.to}
              to={l.to}
              end={l.end}
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <span className="nav-icon">{l.icon}</span>
              {l.label}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          <button
            type="button"
            className="theme-toggle"
            aria-pressed={isLight}
            aria-label={isLight ? 'Переключить на темную тему' : 'Переключить на светлую тему'}
            title={isLight ? 'Темная тема' : 'Светлая тема'}
            onClick={() => setTheme(isLight ? 'dark' : 'light')}
          >
            <span className="theme-toggle-track" aria-hidden="true">
              <span className="theme-toggle-thumb">{isLight ? 'L' : 'D'}</span>
            </span>
            <span className="theme-toggle-text">{isLight ? 'Светлая' : 'Темная'}</span>
          </button>

          <p className="sidebar-footer-title">Внешние сервисы</p>
          <a href="http://localhost:8088"            target="_blank" rel="noreferrer" className="ext-link">Temporal UI</a>
          <a href="/swagger-ui.html" target="_blank" rel="noreferrer" className="ext-link">API Docs</a>
        </div>
      </aside>

      <div className="main-wrapper">
        <Outlet />
      </div>
    </div>
  )
}
