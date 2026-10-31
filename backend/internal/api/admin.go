package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"pdfutils/internal/auth"
	"pdfutils/internal/store"
)

// The admin API is separate from the app's: its own accounts (the admins table),
// its own token audience, and no anonymous identity.
func (s *Server) adminRoutes(r chi.Router) {
	r.Use(noStore, s.adminFloodLimit)
	r.Post("/login", s.adminLogin)
	r.Group(func(r chi.Router) {
		r.Use(s.requireAdmin)
		r.Get("/me", s.adminMe)
		r.Post("/password", s.adminChangePassword)
		r.Group(func(r chi.Router) {
			r.Use(requirePasswordChanged)
			r.Get("/feedback", s.adminListFeedback)
			r.Patch("/feedback/{id}", s.adminSetFeedbackRead)
		})
	})
}

const adminKey ctxKey = iota + 100

func adminFrom(ctx context.Context) *store.Admin { return ctx.Value(adminKey).(*store.Admin) }

func noStore(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) adminFloodLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.limited(w, r, "adminreq:"+clientIP(r), 120, time.Minute) {
			return
		}
		next.ServeHTTP(w, r)
	})
}

// requireAdmin accepts only admin tokens issued under the admin's current password.
func (s *Server) requireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			writeErr(w, 401, "unauthorized", "admin sign-in required")
			return
		}
		id, pv, err := s.tokens.ParseAdmin(tok)
		if err != nil {
			writeErr(w, 401, "invalid_token", "admin session is invalid or expired")
			return
		}
		a, err := s.svc.Store.AdminByID(r.Context(), id)
		if errors.Is(err, store.ErrNotFound) || (err == nil && pv != a.PasswordChangedAt.UnixMicro()) {
			writeErr(w, 401, "invalid_token", "admin session is invalid or expired")
			return
		}
		if err != nil {
			internalErr(w, r, err)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), adminKey, a)))
	})
}

func requirePasswordChanged(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if adminFrom(r.Context()).MustChangePassword {
			writeErr(w, 403, "password_change_required", "change your password to continue")
			return
		}
		next.ServeHTTP(w, r)
	})
}

type adminJSON struct {
	Email              string     `json:"email"`
	MustChangePassword bool       `json:"must_change_password"`
	LastLoginAt        *time.Time `json:"last_login_at"`
}

func toAdminJSON(a *store.Admin) adminJSON {
	return adminJSON{Email: a.Email, MustChangePassword: a.MustChangePassword, LastLoginAt: a.LastLoginAt}
}

func (s *Server) writeAdminSession(w http.ResponseWriter, r *http.Request, a *store.Admin) {
	tok, exp, err := s.tokens.IssueAdmin(a.ID, a.PasswordChangedAt.UnixMicro())
	if err != nil {
		internalErr(w, r, err)
		return
	}
	writeJSON(w, 200, map[string]any{"access_token": tok, "expires_at": exp, "admin": toAdminJSON(a)})
}

func (s *Server) adminLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decode(w, r, &req) {
		return
	}
	// Per IP, and per account so a distributed guess at one password is slow too.
	if s.limited(w, r, "adminlogin:"+clientIP(r), 10, 15*time.Minute) ||
		s.limited(w, r, "adminlogin:"+strings.ToLower(strings.TrimSpace(req.Email)), 30, time.Hour) {
		return
	}
	a, err := s.svc.Store.AdminByEmail(r.Context(), req.Email)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		internalErr(w, r, err)
		return
	}
	if a == nil {
		auth.CheckPasswordDummy(req.Password)
		writeErr(w, 401, "invalid_credentials", "wrong email or password")
		return
	}
	if !auth.CheckPassword(a.PasswordHash, req.Password) {
		writeErr(w, 401, "invalid_credentials", "wrong email or password")
		return
	}
	if err := s.svc.Store.TouchAdminLogin(r.Context(), a.ID); err != nil {
		internalErr(w, r, err)
		return
	}
	s.writeAdminSession(w, r, a)
}

func (s *Server) adminMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"admin": toAdminJSON(adminFrom(r.Context()))})
}

// adminPasswordProblem checks a new admin password; it returns "" when acceptable.
func adminPasswordProblem(current, next, initial string) string {
	switch {
	case utf8.RuneCountInString(next) < 12:
		return "use at least 12 characters"
	case len(next) > 72:
		return "use at most 72 bytes" // bcrypt's limit
	case next == current:
		return "choose a password different from the current one"
	case next == initial:
		return "choose a password different from the initial one"
	}
	return ""
}

func (s *Server) adminChangePassword(w http.ResponseWriter, r *http.Request) {
	a := adminFrom(r.Context())
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if !decode(w, r, &req) {
		return
	}
	if s.limited(w, r, "adminpw:"+a.ID.String(), 10, 15*time.Minute) {
		return
	}
	if !auth.CheckPassword(a.PasswordHash, req.CurrentPassword) {
		writeErr(w, 403, "wrong_password", "current password is incorrect")
		return
	}
	if p := adminPasswordProblem(req.CurrentPassword, req.NewPassword, s.cfg.AdminInitialPassword); p != "" {
		writeErr(w, 422, "weak_password", p)
		return
	}
	hash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	updated, err := s.svc.Store.SetAdminPassword(r.Context(), a.ID, hash)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	s.writeAdminSession(w, r, updated)
}

type feedbackJSON struct {
	ID        uuid.UUID  `json:"id"`
	UserID    *uuid.UUID `json:"user_id"`
	Name      string     `json:"name"`
	Email     string     `json:"email"`
	Topic     string     `json:"topic"`
	Message   string     `json:"message"`
	Client    string     `json:"client"`
	CreatedAt time.Time  `json:"created_at"`
	ReadAt    *time.Time `json:"read_at"`
}

func toFeedbackJSON(f *store.Feedback) feedbackJSON {
	return feedbackJSON{f.ID, f.UserID, f.Name, f.Email, f.Topic, f.Message, f.Client, f.CreatedAt, f.ReadAt}
}

// adminListFeedback pages through Contact us messages, newest first.
// Query: unread=1, limit (1-100), cursor (opaque).
func (s *Server) adminListFeedback(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit < 1 || limit > 100 {
		limit = 30
	}
	var beforeT time.Time
	beforeID := uuid.Nil
	if c := q.Get("cursor"); c != "" {
		var ok bool
		if beforeT, beforeID, ok = decodeCursor(c); !ok {
			writeErr(w, 400, "bad_request", "invalid cursor")
			return
		}
	}
	items, err := s.svc.Store.ListFeedback(ctx, q.Get("unread") == "1", beforeT, beforeID, limit+1)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	unread, err := s.svc.Store.CountUnreadFeedback(ctx)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	var next *string
	if len(items) > limit {
		items = items[:limit]
		last := items[limit-1]
		c := encodeCursor(last.CreatedAt, last.ID)
		next = &c
	}
	out := make([]feedbackJSON, len(items))
	for i, f := range items {
		out[i] = toFeedbackJSON(f)
	}
	writeJSON(w, 200, map[string]any{"feedback": out, "next_cursor": next, "unread": unread})
}

func (s *Server) adminSetFeedbackRead(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		writeErr(w, 404, "not_found", "message not found")
		return
	}
	var req struct {
		Read bool `json:"read"`
	}
	if !decode(w, r, &req) {
		return
	}
	f, err := s.svc.Store.SetFeedbackRead(r.Context(), id, req.Read)
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 404, "not_found", "message not found")
		return
	}
	if err != nil {
		internalErr(w, r, err)
		return
	}
	writeJSON(w, 200, toFeedbackJSON(f))
}
