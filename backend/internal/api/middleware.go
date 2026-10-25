package api

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"

	"pdfutils/internal/store"
)

type ctxKey int

const identityKey ctxKey = iota

// identity describes who is calling: a signed-in user or an anonymous client.
type identity struct {
	owner    store.Owner
	plan     string // "anon" for anonymous clients
	ip       string
	deviceID string // client-generated, may be empty
}

func identityFrom(ctx context.Context) *identity { return ctx.Value(identityKey).(*identity) }

var deviceIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)

// identify resolves the caller from the bearer token, else from the
// X-Device-Id header (hashed), else from the client IP (hashed).
func (s *Server) identify(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := &identity{ip: clientIP(r), plan: "anon"}
		if d := r.Header.Get("X-Device-Id"); deviceIDRe.MatchString(d) {
			id.deviceID = d
		}
		if h := r.Header.Get("Authorization"); h != "" {
			tok, ok := strings.CutPrefix(h, "Bearer ")
			uid, plan, err := s.tokens.ParseAccess(tok)
			switch {
			case ok && err == nil:
				id.owner = store.Owner{UserID: &uid}
				id.plan = plan
			case !strings.HasPrefix(r.URL.Path, "/v1/auth/"):
				// Let clients with a stale token still reach /auth/refresh.
				writeErr(w, 401, "invalid_token", "access token is invalid or expired")
				return
			}
		}
		switch {
		case id.owner.IsUser():
		case id.deviceID != "":
			id.owner = store.Owner{AnonKey: s.anonDeviceKey(id.deviceID)}
		default:
			id.owner = store.Owner{AnonKey: s.tokens.HMAC("ip:" + id.ip)[:40]}
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), identityKey, id)))
	})
}

func requireUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !identityFrom(r.Context()).owner.IsUser() {
			writeErr(w, 401, "unauthorized", "sign in required")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// requestLimit is a coarse per-IP flood guard for every endpoint.
func (s *Server) requestLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.limited(w, r, "req:"+identityFrom(r.Context()).ip, 300, time.Minute) {
			return
		}
		next.ServeHTTP(w, r)
	})
}

// authLimit throttles credential endpoints harder against brute force.
func (s *Server) authLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && s.limited(w, r, "auth:"+identityFrom(r.Context()).ip, 20, time.Minute) {
			return
		}
		next.ServeHTTP(w, r)
	})
}

// limited counts a hit and writes a 429 if over limit. Redis failures fail open.
func (s *Server) limited(w http.ResponseWriter, r *http.Request, key string, limit int, window time.Duration) bool {
	res, err := s.limiter.Allow(r.Context(), key, limit, window)
	if err != nil {
		slog.WarnContext(r.Context(), "rate limiter unavailable", "err", err)
		return false
	}
	if !res.Allowed {
		w.Header().Set("Retry-After", strconv.Itoa(int(time.Until(res.Reset).Seconds())+1))
		writeErr(w, 429, "rate_limited", "too many requests, slow down")
		return true
	}
	return false
}

func (s *Server) realIP(next http.Handler) http.Handler {
	if s.cfg.TrustProxy {
		return middleware.RealIP(next)
	}
	return next
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func requestLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		next.ServeHTTP(ww, r)
		slog.InfoContext(r.Context(), "http",
			"method", r.Method, "path", r.URL.Path, "status", ww.Status(), "bytes", ww.BytesWritten(),
			"ms", time.Since(start).Milliseconds(), "request_id", middleware.GetReqID(r.Context()))
	})
}

func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil && v != http.ErrAbortHandler {
				slog.ErrorContext(r.Context(), "panic", "value", v, "stack", string(debug.Stack()))
				writeErr(w, 500, "internal_error", "something went wrong")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func parseID(w http.ResponseWriter, s string) (uuid.UUID, bool) {
	id, err := uuid.Parse(s)
	if err != nil {
		writeErr(w, 404, "not_found", "not found")
		return uuid.Nil, false
	}
	return id, true
}

func (s *Server) anonDeviceKey(deviceID string) string {
	return s.tokens.HMAC("device:" + deviceID)[:40]
}
