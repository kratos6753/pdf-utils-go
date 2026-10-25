import { Link } from 'react-router-dom'
import clsx from 'clsx'
import { categoryColor, type Tool } from '../lib/tools'

export function ToolIcon({ tool, size = 'md' }: { tool: Tool; size?: 'sm' | 'md' | 'lg' }) {
  const Icon = tool.icon
  const color = categoryColor[tool.category]
  return (
    <span
      className={clsx(
        'grid shrink-0 place-items-center rounded-xl',
        size === 'sm' && 'size-7 rounded-lg',
        size === 'md' && 'size-12',
        size === 'lg' && 'size-16 rounded-2xl',
      )}
      style={{ background: `color-mix(in srgb, ${color} 14%, transparent)`, color }}
    >
      <Icon className={clsx(size === 'sm' ? 'size-4' : size === 'md' ? 'size-6' : 'size-8')} strokeWidth={2.2} />
    </span>
  )
}

export function ToolCard({ tool }: { tool: Tool }) {
  return (
    <Link
      to={`/${tool.slug}`}
      className="card group flex flex-col gap-4 p-6 transition hover:-translate-y-0.5 hover:border-transparent hover:shadow-[var(--shadow-lift)]"
    >
      <ToolIcon tool={tool} />
      <div>
        <h3 className="text-lg font-bold">{tool.title}</h3>
        <p className="mt-1.5 text-sm leading-relaxed text-muted">{tool.description}</p>
      </div>
    </Link>
  )
}
