package api

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"time"

	"pdfutils/internal/auth"
	"pdfutils/internal/store"
)

const (
	refreshCookie = "refresh_token"
	oauthCookie   = "g_oauth"
	// Web clients send this header; it switches refresh tokens to an
	// httpOnly cookie and, being a custom header, blocks cross-site form posts.
	clientTypeHeader = "X-Client-Type"
)

func isWeb(r *http.Request) bool { return r.Header.Get(clientTypeHeader) == "web" }

type userJSON struct {
	ID            string    `json:"id"`
	Email         string    `json:"email"`
	Name          string    `json:"name"`
	AvatarURL     string    `json:"avatar_url"`
	Plan          string    `json:"plan"`
	EmailVerified bool      `json:"email_verified"`
	HasPassword   bool      `json:"has_password"`
	HasGoogle     bool      `json:"has_google"`
	CreatedAt     time.Time `json:"created_at"`
}

func toUserJSON(u *store.User) *userJSON {
	return &userJSON{ID: u.ID.String(), Email: u.Email, Name: u.Name, AvatarURL: u.AvatarURL, Plan: u.Plan,
		EmailVerified: u.EmailVerified, HasPassword: u.PasswordHash != nil, HasGoogle: u.GoogleSub != nil, CreatedAt: u.CreatedAt}
}

type sessionJSON struct {
	AccessToken  string    `json:"access_token"`
	TokenType    string    `json:"token_type"`
	ExpiresAt    time.Time `json:"expires_at"`
	RefreshToken string    `json:"refresh_token,omitempty"` // omitted for web (sent as cookie)
	User         *userJSON `json:"user"`
}

// newSession issues an access token and a rotating refresh token.
func (s *Server) newSession(w http.ResponseWriter, r *http.Request, u *store.User, web bool) (*sessionJSON, error) {
	access, exp, err := s.tokens.IssueAccess(u.ID, u.Plan)
	if err != nil {
		return nil, err
	}
	refresh, hash := auth.RandomToken()
	refreshExp := time.Now().Add(s.cfg.RefreshTokenTTL)
	if err := s.svc.Store.CreateRefreshToken(r.Context(), u.ID, hash, identityFrom(r.Context()).deviceID, refreshExp); err != nil {
		return nil, err
	}
	sess := &sessionJSON{AccessToken: access, TokenType: "Bearer", ExpiresAt: exp, User: toUserJSON(u)}
	if web {
		s.setCookie(w, refreshCookie, refresh, "/v1/auth", int(s.cfg.RefreshTokenTTL.Seconds()), http.SameSiteStrictMode)
	} else {
		sess.RefreshToken = refresh
	}
	return sess, nil
}

func (s *Server) setCookie(w http.ResponseWriter, name, value, path string, maxAge int, same http.SameSite) {
	http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: path, MaxAge: maxAge,
		HttpOnly: true, Secure: s.cfg.IsProd(), SameSite: same})
}

// startSession issues a session and moves the caller's anonymous files into
// the account so work done before signing in is kept.
func (s *Server) startSession(w http.ResponseWriter, r *http.Request, u *store.User, status int) {
	if d := identityFrom(r.Context()).deviceID; d != "" {
		if err := s.svc.Store.ClaimAnonFiles(r.Context(), s.anonDeviceKey(d), u.ID); err != nil {
			slog.WarnContext(r.Context(), "claim anon files", "err", err)
		}
	}
	sess, err := s.newSession(w, r, u, isWeb(r))
	if err != nil {
		internalErr(w, r, err)
		return
	}
	writeJSON(w, status, sess)
}

func validEmail(e string) bool {
	a, err := mail.ParseAddress(e)
	return err == nil && a.Address == strings.TrimSpace(e) && strings.Contains(e, ".") && len(e) <= 254
}

func validPassword(w http.ResponseWriter, pw string) bool {
	if len(pw) < 8 || len(pw) > 128 {
		writeErr(w, 422, "weak_password", "password must be 8 to 128 characters")
		return false
	}
	return true
}

func (s *Server) signup(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Name     string `json:"name"`
	}
	if !decode(w, r, &req) {
		return
	}
	req.Email, req.Name = strings.TrimSpace(req.Email), strings.TrimSpace(req.Name)
	if !validEmail(req.Email) {
		writeErr(w, 422, "invalid_email", "enter a valid email address")
		return
	}
	if !validPassword(w, req.Password) {
		return
	}
	if len(req.Name) > 100 {
		req.Name = req.Name[:100]
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	u, err := s.svc.Store.CreateUser(r.Context(), req.Email, req.Name, &hash, nil, "", false)
	if errors.Is(err, store.ErrDuplicate) {
		writeErr(w, 409, "email_taken", "an account with this email already exists")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	s.sendVerification(r.Context(), u)
	s.startSession(w, r, u, 201)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decode(w, r, &req) {
		return
	}
	u, err := s.svc.Store.UserByEmail(r.Context(), req.Email)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		internalErr(w, r, err)
		return
	}
	if u == nil || u.PasswordHash == nil {
		auth.CheckPasswordDummy(req.Password)
		msg := "incorrect email or password"
		if u != nil {
			msg = "this account uses Google sign-in"
		}
		writeErr(w, 401, "invalid_credentials", msg)
		return
	}
	if !auth.CheckPassword(*u.PasswordHash, req.Password) {
		writeErr(w, 401, "invalid_credentials", "incorrect email or password")
		return
	}
	s.startSession(w, r, u, 200)
}

// refreshTokenFrom reads the refresh token from the body (mobile) or the
// cookie (web, only with the web client header as a CSRF guard).
func refreshTokenFrom(w http.ResponseWriter, r *http.Request) (string, bool) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if !decode(w, r, &req) {
		return "", false
	}
	if req.RefreshToken == "" && isWeb(r) {
		if c, err := r.Cookie(refreshCookie); err == nil {
			req.RefreshToken = c.Value
		}
	}
	return req.RefreshToken, true
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	tok, ok := refreshTokenFrom(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	fail := func() {
		if isWeb(r) {
			s.setCookie(w, refreshCookie, "", "/v1/auth", -1, http.SameSiteStrictMode)
		}
		writeErr(w, 401, "invalid_refresh_token", "session expired, please sign in again")
	}
	if tok == "" {
		fail()
		return
	}
	rt, err := s.svc.Store.RefreshTokenByHash(ctx, auth.HashToken(tok))
	if errors.Is(err, store.ErrNotFound) {
		fail()
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	if rt.RevokedAt != nil {
		// A rotated-out token was replayed: assume theft and end all sessions.
		// A short grace period tolerates clients that retry a refresh concurrently.
		if time.Since(*rt.RevokedAt) > 30*time.Second {
			slog.WarnContext(ctx, "refresh token reuse detected", "user_id", rt.UserID)
			_ = s.svc.Store.RevokeUserRefreshTokens(ctx, rt.UserID)
		}
		fail()
		return
	}
	if time.Now().After(rt.ExpiresAt) {
		fail()
		return
	}
	if revoked, err := s.svc.Store.RevokeRefreshToken(ctx, rt.ID); err != nil {
		internalErr(w, r, err)
		return
	} else if !revoked {
		fail()
		return
	}
	u, err := s.svc.Store.UserByID(ctx, rt.UserID)
	if errors.Is(err, store.ErrNotFound) {
		fail()
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	sess, err := s.newSession(w, r, u, isWeb(r))
	if err != nil {
		internalErr(w, r, err)
		return
	}
	writeJSON(w, 200, sess)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	tok, ok := refreshTokenFrom(w, r)
	if !ok {
		return
	}
	if tok != "" {
		if rt, err := s.svc.Store.RefreshTokenByHash(r.Context(), auth.HashToken(tok)); err == nil {
			_, _ = s.svc.Store.RevokeRefreshToken(r.Context(), rt.ID)
		}
	}
	if isWeb(r) {
		s.setCookie(w, refreshCookie, "", "/v1/auth", -1, http.SameSiteStrictMode)
	}
	w.WriteHeader(204)
}

// --- email verification & password reset ---

func (s *Server) sendVerification(ctx context.Context, u *store.User) {
	tok, hash := auth.RandomToken()
	if err := s.svc.Store.CreateEmailToken(ctx, u.ID, hash, "verify", time.Now().Add(48*time.Hour)); err != nil {
		slog.ErrorContext(ctx, "create verify token", "err", err)
		return
	}
	link := s.cfg.WebURL + "/verify-email?token=" + url.QueryEscape(tok)
	body := "Welcome to PDF Utils!\n\nConfirm your email address by opening this link:\n" + link + "\n\nThe link expires in 48 hours."
	if err := s.mailer.Send(context.WithoutCancel(ctx), u.Email, "Confirm your email", body); err != nil {
		slog.ErrorContext(ctx, "send verification email", "err", err)
	}
}

func (s *Server) verifyEmail(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if !decode(w, r, &req) {
		return
	}
	uid, err := s.svc.Store.ConsumeEmailToken(r.Context(), auth.HashToken(req.Token), "verify")
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 400, "invalid_token", "this link is invalid or has expired")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	if err := s.svc.Store.SetEmailVerified(r.Context(), uid); err != nil {
		internalErr(w, r, err)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) resendVerification(w http.ResponseWriter, r *http.Request) {
	u, err := s.svc.Store.UserByID(r.Context(), *identityFrom(r.Context()).owner.UserID)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	if !u.EmailVerified {
		s.sendVerification(r.Context(), u)
	}
	w.WriteHeader(202)
}

func (s *Server) forgotPassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
	}
	if !decode(w, r, &req) {
		return
	}
	// Always 202 so the endpoint does not reveal which emails have accounts.
	if u, err := s.svc.Store.UserByEmail(r.Context(), req.Email); err == nil {
		tok, hash := auth.RandomToken()
		if err := s.svc.Store.CreateEmailToken(r.Context(), u.ID, hash, "reset", time.Now().Add(time.Hour)); err == nil {
			link := s.cfg.WebURL + "/reset-password?token=" + url.QueryEscape(tok)
			body := "Someone asked to reset the password for your PDF Utils account.\n\nSet a new password here:\n" + link +
				"\n\nThe link expires in 1 hour. If this wasn't you, you can ignore this email."
			if err := s.mailer.Send(context.WithoutCancel(r.Context()), u.Email, "Reset your password", body); err != nil {
				slog.ErrorContext(r.Context(), "send reset email", "err", err)
			}
		}
	}
	w.WriteHeader(202)
}

func (s *Server) resetPassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token    string `json:"token"`
		Password string `json:"password"`
	}
	if !decode(w, r, &req) || !validPassword(w, req.Password) {
		return
	}
	ctx := r.Context()
	uid, err := s.svc.Store.ConsumeEmailToken(ctx, auth.HashToken(req.Token), "reset")
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 400, "invalid_token", "this link is invalid or has expired")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	if err := s.svc.Store.SetPassword(ctx, uid, hash); err != nil {
		internalErr(w, r, err)
		return
	}
	_ = s.svc.Store.SetEmailVerified(ctx, uid) // they proved access to the inbox
	_ = s.svc.Store.RevokeUserRefreshTokens(ctx, uid)
	w.WriteHeader(204)
}

// --- Google ---

// googleStart begins the web OAuth flow (PKCE + state in a short-lived cookie).
func (s *Server) googleStart(w http.ResponseWriter, r *http.Request) {
	if s.google == nil {
		writeErr(w, 404, "not_configured", "Google sign-in is not configured")
		return
	}
	state, _ := auth.RandomToken()
	verifier := auth.NewPKCEVerifier()
	// Lax so the cookie survives the top-level redirect back from Google.
	s.setCookie(w, oauthCookie, state+"."+verifier, "/v1/auth/google", 600, http.SameSiteLaxMode)
	http.Redirect(w, r, s.google.AuthURL(state, verifier), http.StatusFound)
}

func (s *Server) googleCallback(w http.ResponseWriter, r *http.Request) {
	failURL := s.cfg.WebURL + "/login?error=google"
	if s.google == nil {
		http.Redirect(w, r, failURL, http.StatusFound)
		return
	}
	var state, verifier string
	if c, err := r.Cookie(oauthCookie); err == nil {
		state, verifier, _ = strings.Cut(c.Value, ".")
	}
	s.setCookie(w, oauthCookie, "", "/v1/auth/google", -1, http.SameSiteLaxMode)
	if state == "" || r.URL.Query().Get("state") != state || r.URL.Query().Get("code") == "" {
		http.Redirect(w, r, failURL, http.StatusFound)
		return
	}
	gi, err := s.google.Exchange(r.Context(), r.URL.Query().Get("code"), verifier)
	if err != nil {
		slog.WarnContext(r.Context(), "google exchange", "err", err)
		http.Redirect(w, r, failURL, http.StatusFound)
		return
	}
	u, err := s.userForGoogle(r.Context(), gi)
	if err != nil {
		slog.WarnContext(r.Context(), "google login", "err", err)
		http.Redirect(w, r, failURL, http.StatusFound)
		return
	}
	// The web app picks up the session by calling /v1/auth/refresh with the cookie.
	if _, err := s.newSession(w, r, u, true); err != nil {
		internalErr(w, r, err)
		return
	}
	http.Redirect(w, r, s.cfg.WebURL+"/auth/callback", http.StatusFound)
}

// googleToken signs in a mobile (or web One Tap) client with a Google ID token.
func (s *Server) googleToken(w http.ResponseWriter, r *http.Request) {
	if s.google == nil {
		writeErr(w, 404, "not_configured", "Google sign-in is not configured")
		return
	}
	var req struct {
		IDToken string `json:"id_token"`
	}
	if !decode(w, r, &req) {
		return
	}
	gi, err := s.google.VerifyIDToken(r.Context(), req.IDToken)
	if err != nil {
		writeErr(w, 401, "invalid_google_token", "could not verify Google sign-in")
		return
	}
	u, err := s.userForGoogle(r.Context(), gi)
	if err != nil {
		writeErr(w, 401, "invalid_google_token", err.Error())
		return
	}
	s.startSession(w, r, u, 200)
}

// userForGoogle finds or creates the account for a Google identity, linking
// it to an existing email account when Google has verified the email.
func (s *Server) userForGoogle(ctx context.Context, gi *auth.GoogleIdentity) (*store.User, error) {
	if u, err := s.svc.Store.UserByGoogleSub(ctx, gi.Sub); err == nil {
		return u, nil
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	if !gi.EmailVerified || gi.Email == "" {
		return nil, errors.New("your Google account email is not verified")
	}
	u, err := s.svc.Store.UserByEmail(ctx, gi.Email)
	switch {
	case err == nil:
		if !u.EmailVerified {
			// Whoever registered this unverified address with a password may not
			// own it; Google just proved the real owner is signing in.
			if err := s.svc.Store.ClearPassword(ctx, u.ID); err != nil {
				return nil, err
			}
			if err := s.svc.Store.RevokeUserRefreshTokens(ctx, u.ID); err != nil {
				return nil, err
			}
		}
		if err := s.svc.Store.LinkGoogle(ctx, u.ID, gi.Sub, gi.Picture); err != nil {
			return nil, err
		}
		return s.svc.Store.UserByID(ctx, u.ID)
	case errors.Is(err, store.ErrNotFound):
		sub := gi.Sub
		return s.svc.Store.CreateUser(ctx, gi.Email, gi.Name, nil, &sub, gi.Picture, true)
	default:
		return nil, err
	}
}
