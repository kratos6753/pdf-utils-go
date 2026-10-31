import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../lib/auth'

// Public page (no sign-in needed) explaining how to delete an account. Google Play
// requires a link like this for apps that let people create accounts.
export default function DeleteAccount() {
  const { user } = useAuth()
  useEffect(() => {
    document.title = 'Delete your account – PDF Utils'
  }, [])

  return (
    <div className="mx-auto max-w-2xl space-y-6 px-4 py-10">
      <h1 className="text-3xl font-extrabold tracking-tight">Delete your PDF Utils account</h1>
      <p className="text-muted">
        This applies to PDF Utils on the web and the PDF Utils Android app (by chirudev.site). They share one account, so deleting it in either
        place deletes it everywhere.
      </p>

      <section className="card p-6">
        <h2 className="text-lg font-bold">How to delete it</h2>
        <p className="mt-3 text-sm font-semibold">On the web</p>
        <ol className="mt-1 list-decimal space-y-1 pl-5 text-sm">
          <li>
            Sign in at <Link className="text-brand hover:underline" to="/login?next=%2Faccount">pdf-utils.chirudev.site</Link> with the same email
            or Google account you use in the app.
          </li>
          <li>Open <b>Account settings</b>.</li>
          <li>Under <b>Delete account</b>, type DELETE and click <b>Delete my account</b>.</li>
        </ol>
        <p className="mt-4 text-sm font-semibold">In the Android app</p>
        <ol className="mt-1 list-decimal space-y-1 pl-5 text-sm">
          <li>Open the <b>Account</b> tab.</li>
          <li>Tap <b>Delete account</b>, type DELETE and tap <b>Delete forever</b>.</li>
        </ol>
        <p className="mt-4 text-sm text-muted">
          Forgot your password? Use <Link className="text-brand hover:underline" to="/forgot-password">reset password</Link> to get back in first.
        </p>
        {user && (
          <Link to="/account" className="btn-primary mt-5">
            Go to Account settings
          </Link>
        )}
      </section>

      <section className="card p-6">
        <h2 className="text-lg font-bold">What gets deleted</h2>
        <p className="mt-3 text-sm">Deletion happens immediately and can't be undone. We permanently delete:</p>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
          <li>your account: name, email address, password hash and linked Google sign-in</li>
          <li>every file in your history, both the files you uploaded and the results</li>
          <li>your job records and usage counters</li>
          <li>all sign-in sessions, so every device is signed out</li>
        </ul>
        <h2 className="mt-6 text-lg font-bold">What we keep</h2>
        <p className="mt-3 text-sm">
          Nothing tied to your account. Messages you sent through{' '}
          <Link className="text-brand hover:underline" to="/contact">
            Contact us
          </Link>{' '}
          are kept, no longer linked to the account, so we can finish answering them; ask us and we'll delete them. If database backups are
          enabled, a copy may remain in an encrypted backup for up to 14 days until it is overwritten. Files processed without an account are
          never linked to anyone and are deleted automatically within a few hours.
        </p>
      </section>
    </div>
  )
}
