import { useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import clsx from 'clsx'
import type { Field, Values } from '../lib/tools'

function PasswordInput({ value, onChange, placeholder, autoFocus }: { value: string; onChange: (v: string) => void; placeholder?: string; autoFocus?: boolean }) {
  const [show, setShow] = useState(false)
  return (
    <div className="relative">
      <input
        className="input pr-11"
        type={show ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete="new-password"
        autoFocus={autoFocus}
      />
      <button type="button" onClick={() => setShow((s) => !s)} className="absolute inset-y-0 right-0 grid w-11 place-items-center text-muted hover:text-fg" aria-label={show ? 'Hide password' : 'Show password'}>
        {show ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  )
}

export function Options({
  fields,
  values,
  onChange,
  confirm,
  onConfirm,
}: {
  fields: Field[]
  values: Values
  onChange: (v: Values) => void
  confirm: Record<string, string>
  onConfirm: (c: Record<string, string>) => void
}) {
  const set = (k: string, v: string | number | boolean) => onChange({ ...values, [k]: v })
  return (
    <div className="space-y-5">
      {fields
        .filter((f) => !f.showIf || f.showIf(values))
        .map((f) => {
          const id = `opt-${f.key}`
          const val = values[f.key]
          return (
            <div key={f.key}>
              {f.type !== 'toggle' && (
                <label htmlFor={id} className="label">
                  {f.label}
                  {f.type === 'range' && (
                    <span className="float-right font-semibold text-brand">
                      {f.max <= 1 ? `${Math.round(Number(val) * 100)}%` : `${val}${f.unit ?? ''}`}
                    </span>
                  )}
                </label>
              )}
              {f.type === 'choice' && f.layout === 'cards' && (
                <div className="space-y-2" role="radiogroup" id={id}>
                  {f.options.map((o) => (
                    <button
                      key={o.value}
                      type="button"
                      role="radio"
                      aria-checked={val === o.value}
                      onClick={() => set(f.key, o.value)}
                      className={clsx(
                        'flex w-full items-center gap-3 rounded-xl border p-3.5 text-left transition',
                        val === o.value ? 'border-brand bg-brand-soft' : 'border-line hover:border-muted',
                      )}
                    >
                      <span className={clsx('grid size-5 shrink-0 place-items-center rounded-full border-2', val === o.value ? 'border-brand' : 'border-line')}>
                        {val === o.value && <span className="size-2.5 rounded-full bg-brand" />}
                      </span>
                      <span>
                        <span className="block text-sm font-semibold">{o.label}</span>
                        {o.hint && <span className="block text-xs text-muted">{o.hint}</span>}
                      </span>
                    </button>
                  ))}
                </div>
              )}
              {f.type === 'choice' && f.layout !== 'cards' && (
                <div className="flex flex-wrap gap-2" role="radiogroup" id={id}>
                  {f.options.map((o) => (
                    <button
                      key={o.value}
                      type="button"
                      role="radio"
                      aria-checked={val === o.value}
                      onClick={() => set(f.key, o.value)}
                      className={clsx(
                        'rounded-lg border px-3 py-1.5 text-sm font-medium transition',
                        val === o.value ? 'border-brand bg-brand-soft text-brand' : 'border-line hover:border-muted',
                      )}
                    >
                      {o.label}
                    </button>
                  ))}
                </div>
              )}
              {(f.type === 'text' || f.type === 'pages') && (
                <input
                  id={id}
                  className="input"
                  value={String(val ?? '')}
                  onChange={(e) => set(f.key, e.target.value)}
                  placeholder={f.placeholder}
                  maxLength={f.type === 'text' ? f.maxLength : 500}
                  inputMode={f.type === 'pages' ? 'numeric' : undefined}
                />
              )}
              {f.type === 'password' && (
                <div className="space-y-2">
                  <PasswordInput value={String(val ?? '')} onChange={(v) => set(f.key, v)} placeholder="Enter password" autoFocus />
                  {f.confirm && (
                    <PasswordInput value={confirm[f.key] ?? ''} onChange={(v) => onConfirm({ ...confirm, [f.key]: v })} placeholder="Repeat password" />
                  )}
                </div>
              )}
              {f.type === 'range' && (
                <input
                  id={id}
                  type="range"
                  className="w-full accent-[var(--color-brand)]"
                  min={f.min}
                  max={f.max}
                  step={f.step}
                  value={Number(val)}
                  onChange={(e) => set(f.key, Number(e.target.value))}
                />
              )}
              {f.type === 'color' && (
                <div className="flex items-center gap-3">
                  <input id={id} type="color" className="h-10 w-14 cursor-pointer rounded-lg border border-line bg-surface p-1" value={String(val)} onChange={(e) => set(f.key, e.target.value)} />
                  <span className="font-mono text-sm text-muted">{String(val)}</span>
                </div>
              )}
              {f.type === 'toggle' && (
                <label className="flex cursor-pointer items-center justify-between gap-3">
                  <span className="text-sm font-medium">{f.label}</span>
                  <input type="checkbox" className="peer sr-only" checked={Boolean(val)} onChange={(e) => set(f.key, e.target.checked)} />
                  <span className="relative h-6 w-11 shrink-0 rounded-full bg-line transition peer-checked:bg-brand after:absolute after:left-0.5 after:top-0.5 after:size-5 after:rounded-full after:bg-white after:shadow after:transition peer-checked:after:translate-x-5" />
                </label>
              )}
              {f.hint && <p className="mt-1.5 text-xs text-muted">{f.hint}</p>}
            </div>
          )
        })}
    </div>
  )
}
