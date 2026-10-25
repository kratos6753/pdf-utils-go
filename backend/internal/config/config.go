// Package config loads settings from environment variables.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env      string // development | production
	HTTPAddr string

	DatabaseURL string
	RedisAddr   string
	RedisPass   string

	S3Endpoint       string // empty => AWS default
	S3PublicEndpoint string // endpoint used in presigned URLs handed to clients; empty => stream through API
	S3Region         string
	S3Bucket         string
	S3AccessKey      string
	S3SecretKey      string
	S3PathStyle      bool

	// WebOrigins are allowed CORS origins (comma separated).
	WebOrigins []string
	// PublicURL is this API's public base URL, used for OAuth redirects.
	PublicURL string
	// WebURL is where the web app lives (post-login redirects, email links).
	WebURL string

	JWTSecret       string
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration

	GoogleClientID     string
	GoogleClientSecret string
	// GoogleMobileClientIDs are extra audiences accepted for mobile id_tokens.
	GoogleMobileClientIDs []string

	SMTPAddr string // host:port; empty => log emails instead of sending
	SMTPUser string
	SMTPPass string
	MailFrom string

	AnonRetention   time.Duration // how long anonymous files live
	JobTimeout      time.Duration
	WorkConcurrency int
	TrustProxy      bool // honor X-Forwarded-For (behind Caddy)
}

func Load() (*Config, error) {
	c := &Config{
		Env:                   get("APP_ENV", "development"),
		HTTPAddr:              get("HTTP_ADDR", ":8080"),
		DatabaseURL:           get("DATABASE_URL", "postgres://pdfutils:pdfutils@localhost:5432/pdfutils?sslmode=disable"),
		RedisAddr:             get("REDIS_ADDR", "localhost:6379"),
		RedisPass:             get("REDIS_PASSWORD", ""),
		S3Endpoint:            get("S3_ENDPOINT", ""),
		S3PublicEndpoint:      get("S3_PUBLIC_ENDPOINT", ""),
		S3Region:              get("S3_REGION", "us-east-1"),
		S3Bucket:              get("S3_BUCKET", "pdfutils"),
		S3AccessKey:           get("S3_ACCESS_KEY", ""),
		S3SecretKey:           get("S3_SECRET_KEY", ""),
		S3PathStyle:           getBool("S3_PATH_STYLE", false),
		WebOrigins:            getList("WEB_ORIGINS", "http://localhost:5173"),
		PublicURL:             strings.TrimRight(get("PUBLIC_URL", "http://localhost:8080"), "/"),
		WebURL:                strings.TrimRight(get("WEB_URL", "http://localhost:5173"), "/"),
		JWTSecret:             get("JWT_SECRET", ""),
		AccessTokenTTL:        getDuration("ACCESS_TOKEN_TTL", 15*time.Minute),
		RefreshTokenTTL:       getDuration("REFRESH_TOKEN_TTL", 30*24*time.Hour),
		GoogleClientID:        get("GOOGLE_CLIENT_ID", ""),
		GoogleClientSecret:    get("GOOGLE_CLIENT_SECRET", ""),
		GoogleMobileClientIDs: getList("GOOGLE_MOBILE_CLIENT_IDS", ""),
		SMTPAddr:              get("SMTP_ADDR", ""),
		SMTPUser:              get("SMTP_USER", ""),
		SMTPPass:              get("SMTP_PASSWORD", ""),
		MailFrom:              get("MAIL_FROM", "PDF Utils <no-reply@localhost>"),
		AnonRetention:         getDuration("ANON_RETENTION", 2*time.Hour),
		JobTimeout:            getDuration("JOB_TIMEOUT", 5*time.Minute),
		WorkConcurrency:       getInt("WORKER_CONCURRENCY", 4),
		TrustProxy:            getBool("TRUST_PROXY", false),
	}
	if c.JWTSecret == "" {
		if c.Env == "production" {
			return nil, fmt.Errorf("JWT_SECRET is required in production")
		}
		c.JWTSecret = "dev-insecure-secret-change-me"
	}
	if c.Env == "production" && len(c.JWTSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET must be at least 32 characters")
	}
	return c, nil
}

func (c *Config) IsProd() bool { return c.Env == "production" }

func get(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}

func getInt(k string, def int) int {
	if n, err := strconv.Atoi(get(k, "")); err == nil {
		return n
	}
	return def
}

func getBool(k string, def bool) bool {
	if b, err := strconv.ParseBool(get(k, "")); err == nil {
		return b
	}
	return def
}

func getDuration(k string, def time.Duration) time.Duration {
	if d, err := time.ParseDuration(get(k, "")); err == nil {
		return d
	}
	return def
}

func getList(k, def string) []string {
	var out []string
	for _, s := range strings.Split(get(k, def), ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}
