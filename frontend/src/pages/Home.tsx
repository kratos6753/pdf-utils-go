import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search, ShieldCheck, Smartphone, Zap } from 'lucide-react'
import clsx from 'clsx'
import { categories, tools, type Category } from '../lib/tools'
import { ToolCard } from '../components/ToolCard'
import { useAuth } from '../lib/auth'

export default function Home() {
  const { user } = useAuth()
  const [q, setQ] = useState('')
  const [cat, setCat] = useState<Category | 'all'>('all')
  useEffect(() => {
    document.title = 'PDF Utils – every PDF tool in one place'
  }, [])

  const shown = useMemo(() => {
    const s = q.trim().toLowerCase()
    return tools.filter((t) => (cat === 'all' || t.category === cat) && (!s || `${t.title} ${t.description}`.toLowerCase().includes(s)))
  }, [q, cat])

  return (
    <div>
      <section className="mx-auto max-w-4xl px-4 pb-10 pt-16 text-center sm:pt-20">
        <h1 className="text-4xl font-extrabold tracking-tight text-balance sm:text-6xl">
          Every tool you need to work with <span className="text-brand">PDFs</span>
        </h1>
        <p className="mx-auto mt-5 max-w-2xl text-lg text-muted text-balance">
          Merge, split, compress, convert, protect and unlock PDFs, free and right in your browser.
        </p>
        <div className="relative mx-auto mt-8 max-w-md">
          <Search className="pointer-events-none absolute left-4 top-1/2 size-5 -translate-y-1/2 text-muted" />
          <input className="input rounded-2xl py-3.5 pl-12 text-base" placeholder="Search tools…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search tools" />
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-4 sm:px-6">
        <div className="mb-8 flex flex-wrap justify-center gap-2" role="tablist">
          {[{ id: 'all' as const, label: 'All' }, ...categories].map((c) => (
            <button
              key={c.id}
              role="tab"
              aria-selected={cat === c.id}
              onClick={() => setCat(c.id)}
              className={clsx('rounded-full border px-4 py-1.5 text-sm font-semibold transition', cat === c.id ? 'border-fg bg-fg text-bg' : 'border-line bg-surface hover:border-muted')}
            >
              {c.label}
            </button>
          ))}
        </div>
        {shown.length ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {shown.map((t) => (
              <ToolCard key={t.slug} tool={t} />
            ))}
          </div>
        ) : (
          <p className="py-16 text-center text-muted">No tools match “{q}”.</p>
        )}
      </section>

      <section className="mx-auto mt-24 grid max-w-6xl gap-6 px-4 sm:px-6 md:grid-cols-3">
        {[
          { icon: ShieldCheck, title: 'Private by default', text: 'Files travel over HTTPS and are deleted automatically. Your account history is yours alone.' },
          { icon: Zap, title: 'Fast and free', text: 'Most tasks finish in seconds. No installs, no watermarks on your files.' },
          { icon: Smartphone, title: 'Works everywhere', text: 'Use it on desktop or phone. Native mobile apps are on the way.' },
        ].map((f) => (
          <div key={f.title} className="card p-6">
            <f.icon className="size-7 text-brand" />
            <h3 className="mt-4 font-bold">{f.title}</h3>
            <p className="mt-1.5 text-sm leading-relaxed text-muted">{f.text}</p>
          </div>
        ))}
      </section>

      {!user && (
        <section className="mx-auto mt-16 max-w-4xl px-4">
          <div className="card flex flex-col items-center justify-between gap-6 bg-gradient-to-br from-brand-soft to-surface p-8 text-center sm:flex-row sm:text-left">
            <div>
              <h2 className="text-2xl font-bold">Need to process more?</h2>
              <p className="mt-1 text-muted">Create a free account for higher limits, bigger files and a history of your documents.</p>
            </div>
            <Link to="/signup" className="btn-primary btn-lg shrink-0">
              Sign up free
            </Link>
          </div>
        </section>
      )}
    </div>
  )
}
