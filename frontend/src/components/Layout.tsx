import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ChevronDown, History, LogOut, Menu, User as UserIcon, X } from 'lucide-react'
import clsx from 'clsx'
import { useAuth } from '../lib/auth'
import { categories, tools } from '../lib/tools'
import { ToolIcon } from './ToolCard'

export const APP_NAME = 'PDF Utils'

export function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2 text-lg font-extrabold tracking-tight">
      <img src="/favicon.svg" alt="" className="size-8" />
      <span>
        PDF<span className="text-brand">Utils</span>
      </span>
    </Link>
  )
}

function useClickOutside(onOutside: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const h = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && onOutside()
    document.addEventListener('mousedown', h)
    return () => document.removeEventListener('mousedown', h)
  }, [onOutside])
  return ref
}

function Dropdown({ label, children, align = 'left' }: { label: ReactNode; children: (close: () => void) => ReactNode; align?: 'left' | 'right' }) {
  const [open, setOpen] = useState(false)
  const ref = useClickOutside(() => setOpen(false))
  const { pathname } = useLocation()
  useEffect(() => {
    setOpen(false)
  }, [pathname])
  return (
    <div ref={ref} className="relative">
      <button className="btn-ghost px-3" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {label}
        <ChevronDown className={clsx('size-4 transition', open && 'rotate-180')} />
      </button>
      {open && (
        <div className={clsx('card absolute top-full z-50 mt-2 p-2 shadow-[var(--shadow-lift)]', align === 'right' ? 'right-0' : 'left-0')}>
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}

function AllToolsMenu() {
  return (
    <div className="grid w-[min(52rem,90vw)] grid-cols-2 gap-x-4 gap-y-5 p-3 lg:grid-cols-5">
      {categories.map((c) => (
        <div key={c.id}>
          <div className="mb-2 px-2 text-xs font-bold uppercase tracking-wider text-muted">{c.label}</div>
          {tools
            .filter((t) => t.category === c.id)
            .map((t) => (
              <Link key={t.slug} to={`/${t.slug}`} className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm font-medium hover:bg-surface-2">
                <ToolIcon tool={t} size="sm" />
                {t.title}
              </Link>
            ))}
        </div>
      ))}
    </div>
  )
}

function UserMenu() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  if (!user) return null
  const initial = (user.name || user.email)[0]?.toUpperCase()
  return (
    <Dropdown
      align="right"
      label={
        user.avatar_url ? (
          <img src={user.avatar_url} alt="" className="size-8 rounded-full" referrerPolicy="no-referrer" />
        ) : (
          <span className="grid size-8 place-items-center rounded-full bg-brand text-sm font-bold text-white">{initial}</span>
        )
      }
    >
      {(close) => (
        <div className="w-60">
          <div className="border-b border-line px-3 pb-2 pt-1">
            <div className="truncate text-sm font-semibold">{user.name || 'Your account'}</div>
            <div className="truncate text-xs text-muted">{user.email}</div>
          </div>
          <Link to="/history" onClick={close} className="mt-1 flex items-center gap-2 rounded-lg px-3 py-2 text-sm hover:bg-surface-2">
            <History className="size-4" /> File history
          </Link>
          <Link to="/account" onClick={close} className="flex items-center gap-2 rounded-lg px-3 py-2 text-sm hover:bg-surface-2">
            <UserIcon className="size-4" /> Account settings
          </Link>
          <button
            onClick={async () => {
              close()
              await logout()
              navigate('/')
            }}
            className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-danger hover:bg-danger-soft"
          >
            <LogOut className="size-4" /> Log out
          </button>
        </div>
      )}
    </Dropdown>
  )
}

const quickLinks = ['merge-pdf', 'split-pdf', 'compress-pdf']

function Header() {
  const { user, ready } = useAuth()
  const [mobileOpen, setMobileOpen] = useState(false)
  const { pathname } = useLocation()
  useEffect(() => {
    setMobileOpen(false)
  }, [pathname])

  return (
    <header className="sticky top-0 z-40 border-b border-line bg-surface/85 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-7xl items-center gap-2 px-4 sm:px-6">
        <Logo />
        <nav className="ml-6 hidden items-center gap-1 md:flex">
          {quickLinks.map((s) => {
            const t = tools.find((t) => t.slug === s)!
            return (
              <NavLink key={s} to={`/${s}`} className={({ isActive }) => clsx('btn-ghost px-3', isActive && 'text-brand')}>
                {t.title}
              </NavLink>
            )
          })}
          <Dropdown label="Convert">
            {() => (
              <div className="w-56">
                {tools
                  .filter((t) => t.category === 'convert')
                  .map((t) => (
                    <Link key={t.slug} to={`/${t.slug}`} className="flex items-center gap-2.5 rounded-lg px-2 py-2 text-sm font-medium hover:bg-surface-2">
                      <ToolIcon tool={t} size="sm" />
                      {t.title}
                    </Link>
                  ))}
              </div>
            )}
          </Dropdown>
          <Dropdown label="All tools">{() => <AllToolsMenu />}</Dropdown>
        </nav>
        <div className="ml-auto flex items-center gap-2">
          {ready && !user && (
            <>
              <Link to="/login" className="btn-ghost hidden sm:inline-flex">
                Log in
              </Link>
              <Link to="/signup" className="btn-primary">
                Sign up
              </Link>
            </>
          )}
          {user && <UserMenu />}
          <button className="btn-ghost px-2 md:hidden" onClick={() => setMobileOpen((o) => !o)} aria-label="Menu">
            {mobileOpen ? <X className="size-5" /> : <Menu className="size-5" />}
          </button>
        </div>
      </div>
      {mobileOpen && (
        <div className="max-h-[calc(100dvh-4rem)] overflow-y-auto border-t border-line bg-surface px-4 py-3 md:hidden">
          {categories.map((c) => (
            <div key={c.id} className="mb-3">
              <div className="mb-1 px-2 text-xs font-bold uppercase tracking-wider text-muted">{c.label}</div>
              {tools
                .filter((t) => t.category === c.id)
                .map((t) => (
                  <Link key={t.slug} to={`/${t.slug}`} className="flex items-center gap-2.5 rounded-lg px-2 py-2 text-sm font-medium hover:bg-surface-2">
                    <ToolIcon tool={t} size="sm" />
                    {t.title}
                  </Link>
                ))}
            </div>
          ))}
          {!user && (
            <Link to="/login" className="btn-secondary mt-2 w-full">
              Log in
            </Link>
          )}
        </div>
      )}
    </header>
  )
}

function Footer() {
  return (
    <footer className="mt-24 border-t border-line bg-surface">
      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-12 sm:px-6 md:grid-cols-[1.5fr_repeat(3,1fr)]">
        <div>
          <Logo />
          <p className="mt-3 max-w-xs text-sm text-muted">
            Every tool you need to work with PDFs, in one place. Free to use, no installation required.
          </p>
        </div>
        {categories.slice(0, 3).map((c) => (
          <div key={c.id}>
            <div className="mb-3 text-sm font-bold">{c.label}</div>
            <ul className="space-y-2 text-sm text-muted">
              {tools
                .filter((t) => t.category === c.id)
                .map((t) => (
                  <li key={t.slug}>
                    <Link to={`/${t.slug}`} className="hover:text-fg">
                      {t.title}
                    </Link>
                  </li>
                ))}
            </ul>
          </div>
        ))}
      </div>
      <div className="border-t border-line py-5 text-center text-xs text-muted">
        © {new Date().getFullYear()} {APP_NAME}. Files are encrypted in transit and deleted automatically.
      </div>
    </footer>
  )
}

export default function Layout() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [pathname])
  return (
    <div className="flex min-h-dvh flex-col">
      <Header />
      <main className="flex-1">
        <Outlet />
      </main>
      <Footer />
    </div>
  )
}
