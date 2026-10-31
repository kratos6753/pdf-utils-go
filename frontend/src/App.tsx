import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { AuthProvider, useAuth } from './lib/auth'
import { toolBySlug } from './lib/tools'
import Layout from './components/Layout'
import Home from './pages/Home'

// Tool pages pull in pdf.js; load them on demand so the home page stays light.
const ToolPage = lazy(() => import('./pages/ToolPage'))
const History = lazy(() => import('./pages/History'))
const Account = lazy(() => import('./pages/Account'))
const DeleteAccount = lazy(() => import('./pages/DeleteAccount'))
const Privacy = lazy(() => import('./pages/Privacy'))
const Contact = lazy(() => import('./pages/Contact'))
const Admin = lazy(() => import('./pages/admin/Admin'))
import { AuthCallback, ForgotPassword, Login, ResetPassword, Signup, VerifyEmail } from './pages/Auth'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

const spinner = (
  <div className="grid min-h-[50vh] place-items-center">
    <Loader2 className="size-8 animate-spin text-brand" />
  </div>
)

function RequireUser({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth()
  const { pathname } = useLocation()
  if (!ready) return spinner
  return user ? <Suspense fallback={spinner}>{children}</Suspense> : <Navigate to={`/login?next=${encodeURIComponent(pathname)}`} replace />
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  return user ? <Navigate to="/" replace /> : children
}

function NotFound() {
  return (
    <div className="mx-auto max-w-md px-4 py-32 text-center">
      <div className="text-6xl font-extrabold text-brand">404</div>
      <h1 className="mt-4 text-2xl font-bold">Page not found</h1>
      <Link to="/" className="btn-primary mt-8">
        Back to all tools
      </Link>
    </div>
  )
}

function ToolRoute() {
  const { slug = '' } = useParams()
  const tool = toolBySlug(slug)
  // key: remount with fresh state when switching tools
  return tool ? (
    <Suspense fallback={spinner}>
      <ToolPage key={tool.slug} tool={tool} />
    </Suspense>
  ) : (
    <NotFound />
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<Home />} />
              <Route path="login" element={<GuestOnly><Login /></GuestOnly>} />
              <Route path="signup" element={<GuestOnly><Signup /></GuestOnly>} />
              <Route path="forgot-password" element={<ForgotPassword />} />
              <Route path="reset-password" element={<ResetPassword />} />
              <Route path="verify-email" element={<VerifyEmail />} />
              <Route path="auth/callback" element={<AuthCallback />} />
              <Route path="history" element={<RequireUser><History /></RequireUser>} />
              <Route path="account" element={<RequireUser><Account /></RequireUser>} />
              <Route path="delete-account" element={<Suspense fallback={spinner}><DeleteAccount /></Suspense>} />
              <Route path="privacy" element={<Suspense fallback={spinner}><Privacy /></Suspense>} />
              <Route path="contact" element={<Suspense fallback={spinner}><Contact /></Suspense>} />
              <Route path="admin" element={<Suspense fallback={spinner}><Admin /></Suspense>} />
              <Route path=":slug" element={<ToolRoute />} />
              <Route path="*" element={<NotFound />} />
            </Route>
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
