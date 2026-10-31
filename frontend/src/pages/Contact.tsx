import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, Loader2, Send } from 'lucide-react'
import { api, ApiError, type User } from '../lib/api'
import { useAuth } from '../lib/auth'

const topics = [
  { value: 'feedback', label: 'Feedback or idea' },
  { value: 'bug', label: 'Something is broken' },
  { value: 'question', label: 'Question' },
  { value: 'other', label: 'Other' },
]

export default function Contact() {
  const { user, ready } = useAuth()
  useEffect(() => {
    document.title = 'Contact us – PDF Utils'
  }, [])
  // Remount once the session loads so a signed-in user's details are filled in.
  return <ContactForm key={ready ? (user?.id ?? 'guest') : 'loading'} user={user} />
}

function ContactForm({ user }: { user: User | null }) {
  const [name, setName] = useState(user?.name ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [topic, setTopic] = useState('feedback')
  const [message, setMessage] = useState('')
  const [website, setWebsite] = useState('') // honeypot
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [sent, setSent] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await api('/feedback', { json: { name, email, topic, message, website } })
      setSent(true)
    } catch (err) {
      setError(err instanceof ApiError ? err.message.charAt(0).toUpperCase() + err.message.slice(1) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  if (sent) {
    return (
      <div className="mx-auto max-w-md px-4 py-24 text-center">
        <CheckCircle2 className="mx-auto size-12 text-ok" />
        <h1 className="mt-4 text-2xl font-bold">Thanks, we got your message</h1>
        <p className="mt-2 text-sm text-muted">We read every message. If you asked something, we'll reply to {email}.</p>
        <Link to="/" className="btn-primary mt-8">
          Back to all tools
        </Link>
      </div>
    )
  }

  const left = 5000 - message.length
  return (
    <div className="mx-auto max-w-xl px-4 py-10">
      <h1 className="text-3xl font-extrabold tracking-tight">Contact us</h1>
      <p className="mt-2 text-muted">Found a bug, have an idea, or need help? Send us a message.</p>

      <form onSubmit={submit} className="card mt-8 space-y-5 p-6">
        <div className="grid gap-5 sm:grid-cols-2">
          <div>
            <label className="label" htmlFor="name">
              Name <span className="font-normal text-muted">(optional)</span>
            </label>
            <input id="name" className="input" autoComplete="name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div>
            <label className="label" htmlFor="email">
              Email
            </label>
            <input id="email" className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
        </div>
        <div>
          <label className="label" htmlFor="topic">
            Topic
          </label>
          <select id="topic" className="input" value={topic} onChange={(e) => setTopic(e.target.value)}>
            {topics.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="message">
            Message
          </label>
          <textarea
            id="message"
            className="input min-h-40 resize-y"
            required
            minLength={10}
            maxLength={5000}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder={topic === 'bug' ? 'What did you do, what did you expect, and what happened instead?' : undefined}
          />
          {left < 500 && <p className="mt-1 text-right text-xs text-muted">{left} characters left</p>}
        </div>
        {/* Hidden from people; bots that fill every field get silently dropped. */}
        <div className="absolute -left-[9999px]" aria-hidden>
          <label htmlFor="website">Website</label>
          <input id="website" tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} />
        </div>
        {error && (
          <div className="rounded-xl bg-danger-soft p-3 text-sm text-danger" role="alert">
            {error}
          </div>
        )}
        <button className="btn-primary w-full py-3" disabled={busy}>
          {busy ? <Loader2 className="size-4 animate-spin" /> : <Send className="size-4" />} Send message
        </button>
        <p className="text-center text-xs text-muted">
          We use your email only to reply. See our{' '}
          <Link to="/privacy" className="text-brand hover:underline">
            privacy policy
          </Link>
          .
        </p>
      </form>
    </div>
  )
}
