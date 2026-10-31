// Package api is the versioned HTTP API used by the web and mobile apps.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/redis/go-redis/v9"

	"pdfutils/internal/auth"
	"pdfutils/internal/config"
	"pdfutils/internal/mail"
	"pdfutils/internal/pdf"
	"pdfutils/internal/queue"
	"pdfutils/internal/ratelimit"
	"pdfutils/internal/service"
)

type Server struct {
	cfg     *config.Config
	svc     *service.Service
	queue   *queue.Client
	tokens  *auth.Tokens
	google  *auth.Google
	limiter *ratelimit.Limiter
	rdb     *redis.Client
	mailer  *mail.Mailer
}

func New(cfg *config.Config, svc *service.Service, q *queue.Client, rdb *redis.Client) *Server {
	return &Server{
		cfg:     cfg,
		svc:     svc,
		queue:   q,
		tokens:  auth.NewTokens(cfg.JWTSecret, cfg.AccessTokenTTL),
		google:  auth.NewGoogle(cfg.GoogleClientID, cfg.GoogleClientSecret, cfg.PublicURL+"/v1/auth/google/callback", cfg.GoogleMobileClientIDs),
		limiter: ratelimit.New(rdb),
		rdb:     rdb,
		mailer:  mail.New(cfg.SMTPAddr, cfg.SMTPUser, cfg.SMTPPass, cfg.MailFrom),
	}
}

func (s *Server) Handler() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID, s.realIP, requestLogger, recoverer)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   s.cfg.WebOrigins,
		AllowedMethods:   []string{"GET", "POST", "PATCH", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Authorization", "Content-Type", "X-Device-Id", "X-Client-Type", "Idempotency-Key"},
		ExposedHeaders:   []string{"X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"},
		AllowCredentials: true,
		MaxAge:           600,
	}))

	r.Get("/healthz", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, map[string]string{"status": "ok"}) })
	r.Get("/readyz", s.ready)

	r.Route("/v1", func(r chi.Router) {
		r.Route("/admin", s.adminRoutes)
		r.Group(func(r chi.Router) {
			r.Use(s.identify, s.requestLimit)
			s.appRoutes(r)
		})
	})
	return r
}

// appRoutes are the endpoints used by the web and mobile apps.
func (s *Server) appRoutes(r chi.Router) {
	r.Get("/config", s.clientConfig)
	r.Get("/tools", s.listTools)
	r.Get("/dl/{token}", s.downloadByLink)

	r.Post("/uploads", s.upload)
	r.Get("/files/{id}", s.getFile)
	r.Get("/files/{id}/link", s.fileLink)
	r.Delete("/files/{id}", s.deleteFile)

	r.Post("/tasks/{tool}", s.createTask)
	r.Get("/jobs/{id}", s.getJob)

	r.Route("/auth", func(r chi.Router) {
		r.Use(s.authLimit)
		r.Post("/signup", s.signup)
		r.Post("/login", s.login)
		r.Post("/refresh", s.refresh)
		r.Post("/logout", s.logout)
		r.Post("/verify-email", s.verifyEmail)
		r.Post("/forgot-password", s.forgotPassword)
		r.Post("/reset-password", s.resetPassword)
		r.Get("/google/start", s.googleStart)
		r.Get("/google/callback", s.googleCallback)
		r.Post("/google/token", s.googleToken)
	})

	r.Get("/me", s.me) // works for anonymous clients too (returns limits)
	r.Group(func(r chi.Router) {
		r.Use(requireUser)
		r.Patch("/me", s.updateMe)
		r.Delete("/me", s.deleteMe)
		r.Post("/me/password", s.changePassword)
		r.Post("/me/resend-verification", s.resendVerification)
		r.Get("/me/files", s.listMyFiles)
	})
	r.Post("/feedback", s.createFeedback)
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	checks := map[string]error{
		"postgres": s.svc.Store.Ping(ctx),
		"redis":    s.rdb.Ping(ctx).Err(),
		"storage":  s.svc.Storage.Ping(ctx),
	}
	status, out := 200, map[string]string{}
	for k, err := range checks {
		out[k] = "ok"
		if err != nil {
			status, out[k] = 503, err.Error()
		}
	}
	writeJSON(w, status, out)
}

// --- responses ---

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Details any    `json:"details,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: msg}})
}

func writeErrDetails(w http.ResponseWriter, status int, code, msg string, details any) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: msg, Details: details}})
}

func internalErr(w http.ResponseWriter, r *http.Request, err error) {
	slog.ErrorContext(r.Context(), "internal error", "err", err, "path", r.URL.Path, "request_id", middleware.GetReqID(r.Context()))
	writeErr(w, 500, "internal_error", "something went wrong")
}

// decode reads a JSON body (max 64 KB), rejecting unknown fields.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, 400, "bad_request", "invalid JSON body: "+err.Error())
		return false
	}
	return true
}

// --- tools ---

type toolInfo struct {
	Name     string `json:"name"`
	Accepts  string `json:"accepts"`
	MinFiles int    `json:"min_files"`
	MaxFiles int    `json:"max_files"`
}

func (s *Server) listTools(w http.ResponseWriter, r *http.Request) {
	var out []toolInfo
	for _, n := range pdf.Names() {
		t, _ := pdf.Lookup(n)
		lo, hi := t.FileLimits()
		out = append(out, toolInfo{Name: n, Accepts: string(t.Accepts()), MinFiles: lo, MaxFiles: hi})
	}
	writeJSON(w, 200, map[string]any{"tools": out})
}

// clientConfig tells clients which optional features are enabled.
func (s *Server) clientConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"google_auth": s.google != nil})
}
