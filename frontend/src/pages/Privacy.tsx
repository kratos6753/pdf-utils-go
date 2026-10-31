import { useEffect, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

// Also the privacy policy URL for the Google Play listing and the Android app's Account tab.
const UPDATED = 'October 31, 2026'
const CONTACT = 'chiruthedeveloper@gmail.com'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-xl font-bold">{title}</h2>
      <div className="space-y-3 text-sm leading-relaxed [&_ul]:list-disc [&_ul]:space-y-1.5 [&_ul]:pl-5">{children}</div>
    </section>
  )
}

export default function Privacy() {
  useEffect(() => {
    document.title = 'Privacy policy – PDF Utils'
  }, [])

  return (
    <div className="mx-auto max-w-2xl space-y-10 px-4 py-10">
      <header>
        <h1 className="text-3xl font-extrabold tracking-tight">Privacy policy</h1>
        <p className="mt-2 text-sm text-muted">Last updated {UPDATED}</p>
        <p className="mt-4 text-sm leading-relaxed">
          This policy covers PDF Utils on the web (pdf-utils.chirudev.site) and the PDF Utils Android app, both published by chirudev.site
          ("we"). In short: we only collect what's needed to process your files and run your account, we don't sell or share it for advertising,
          and we don't use analytics or ad trackers.
        </p>
      </header>

      <Section title="Files you process">
        <p>
          When you use a tool, the files you choose are uploaded to our server, processed, and the result is stored so you can download it. We
          don't look at, analyse or use the contents of your files for anything else.
        </p>
        <ul>
          <li>
            <b>Without an account:</b> uploads and results are deleted automatically about 2 hours after upload.
          </li>
          <li>
            <b>With an account:</b> your files are kept in your history until you delete them, or until you delete your account.
          </li>
          <li>Passwords you enter for the Protect and Unlock tools are used for that one job only and are erased as soon as it finishes.</li>
        </ul>
      </Section>

      <Section title="Account information">
        <p>If you create an account, we store:</p>
        <ul>
          <li>your email address and name</li>
          <li>a hash of your password (never the password itself), if you sign up with email</li>
          <li>
            if you continue with Google: your Google account ID, email address and name, which Google shares with us when you agree. We don't get
            access to your Gmail, Drive or contacts.
          </li>
          <li>whether your email is verified, your plan, and how much storage you use</li>
          <li>sign-in sessions (a hashed token per signed-in device), which expire after 30 days</li>
        </ul>
        <p>We use your email only for account messages: verifying your address and resetting your password. We don't send marketing email.</p>
      </Section>

      <Section title="Messages you send us">
        <p>
          When you use the{' '}
          <Link className="text-brand hover:underline" to="/contact">
            Contact us
          </Link>{' '}
          form we store your name (if you give it), email address, topic and message, plus which app you sent it from, so we can read and reply
          to it. Messages are kept until they've been dealt with. If you delete your account, your messages are no longer linked to it; email
          us if you'd like them deleted too.
        </p>
      </Section>

      <Section title="Technical data">
        <ul>
          <li>
            <b>Device identifier:</b> the app and the website create a random ID on your device. It ties anonymous uploads to you so only you can
            download them, and lets us apply per-device limits. It isn't linked to your phone's hardware or advertising ID.
          </li>
          <li>
            <b>IP address:</b> used briefly to rate-limit abuse (for example repeated sign-in attempts). These counters expire within about an hour.
            Our request logs don't record IP addresses.
          </li>
          <li>
            <b>Usage records:</b> a record of each job (which tool, when, and whether it worked) and daily totals of tasks and bytes processed, used
            to enforce plan limits.
          </li>
        </ul>
        <p>
          We don't use cookies for tracking. The website sets one cookie to keep you signed in and stores a sign-in flag and the device ID in your
          browser's local storage. The Android app keeps its sign-in token encrypted on your device.
        </p>
      </Section>

      <Section title="Android app permissions">
        <p>
          The app only needs internet access. You choose files with the system file or photo picker, or by sharing them to PDF Utils, so the app
          can't see anything you don't pick. Results are saved to <i>Downloads/PDF Utils</i> only when you tap Save.
        </p>
      </Section>

      <Section title="Who processes your data">
        <p>We don't sell your data or share it with advertisers. We use these service providers to run PDF Utils:</p>
        <ul>
          <li>
            <b>Amazon Web Services</b> (United States): hosting, the database and file storage.
          </li>
          <li>
            <b>Google</b>: Sign in with Google, and sending account emails.
          </li>
          <li>
            <b>Google Play</b>: distributes the Android app and may collect crash and performance reports under Google's own privacy policy.
          </li>
        </ul>
        <p>We may disclose information if required by law.</p>
      </Section>

      <Section title="Security">
        <p>
          All traffic between your device and our server is encrypted with HTTPS (TLS), and the Android app refuses unencrypted connections.
          Stored files are encrypted at rest. Access to the servers is restricted.
        </p>
      </Section>

      <Section title="Your choices and rights">
        <ul>
          <li>Delete any file from your history at any time.</li>
          <li>Change your name or password in Account settings.</li>
          <li>
            Delete your account and all associated data, immediately, from Account settings on the web or in the app. See{' '}
            <Link className="text-brand hover:underline" to="/delete-account">
              how to delete your account
            </Link>
            .
          </li>
          <li>
            Ask us for a copy of your data or any other privacy request by emailing the address below or using the{' '}
            <Link className="text-brand hover:underline" to="/contact">
              contact form
            </Link>
            .
          </li>
        </ul>
      </Section>

      <Section title="Children">
        <p>PDF Utils isn't directed at children under 13, and we don't knowingly collect their personal information.</p>
      </Section>

      <Section title="Changes and contact">
        <p>If we change this policy, we'll update the date at the top of this page.</p>
        <p>
          Questions or requests: <b>{CONTACT}</b>
        </p>
      </Section>
    </div>
  )
}
