// Command pdfutils runs the API server, the job worker, or migrations.
//
//	pdfutils server   # HTTP API (runs migrations first)
//	pdfutils worker   # processes queued jobs
//	pdfutils migrate  # apply database migrations and exit
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"pdfutils/internal/api"
	"pdfutils/internal/config"
	"pdfutils/internal/queue"
	"pdfutils/internal/service"
	"pdfutils/internal/storage"
	"pdfutils/internal/store"
	"pdfutils/internal/worker"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: pdfutils server|worker|migrate")
		os.Exit(2)
	}
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(1)
	}
	var h slog.Handler = slog.NewTextHandler(os.Stderr, nil)
	if cfg.IsProd() {
		h = slog.NewJSONHandler(os.Stderr, nil)
	}
	slog.SetDefault(slog.New(h))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1], cfg); err != nil {
		slog.Error("exit", "err", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, cmd string, cfg *config.Config) error {
	st, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer st.Close()

	if cmd == "migrate" || cmd == "server" {
		if err := st.Migrate(ctx); err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
		if cmd == "migrate" {
			return nil
		}
	}

	sg, err := storage.New(ctx, cfg)
	if err != nil {
		return err
	}
	if !cfg.IsProd() {
		if err := sg.EnsureBucket(ctx); err != nil {
			return fmt.Errorf("ensure bucket: %w", err)
		}
	}
	svc := service.New(st, sg, cfg)
	redisOpt := queue.RedisOpt(cfg.RedisAddr, cfg.RedisPass)

	switch cmd {
	case "worker":
		slog.Info("worker starting", "concurrency", cfg.WorkConcurrency)
		return worker.New(svc).Run(ctx, redisOpt, cfg.WorkConcurrency)
	case "server":
		rdb := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr, Password: cfg.RedisPass})
		defer rdb.Close()
		q := queue.NewClient(redisOpt, cfg.JobTimeout)
		defer q.Close()

		srv := &http.Server{
			Addr:              cfg.HTTPAddr,
			Handler:           api.New(cfg, svc, q, rdb).Handler(),
			ReadHeaderTimeout: 10 * time.Second,
			IdleTimeout:       120 * time.Second,
		}
		errc := make(chan error, 1)
		go func() {
			slog.Info("api listening", "addr", cfg.HTTPAddr)
			errc <- srv.ListenAndServe()
		}()
		select {
		case err := <-errc:
			return err
		case <-ctx.Done():
		}
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
	return fmt.Errorf("unknown command %q", cmd)
}
