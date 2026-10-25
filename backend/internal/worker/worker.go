// Package worker processes queued PDF jobs.
package worker

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"github.com/hibiken/asynq"

	"pdfutils/internal/pdf"
	"pdfutils/internal/queue"
	"pdfutils/internal/service"
	"pdfutils/internal/store"
)

type Worker struct {
	svc *service.Service
}

func New(svc *service.Service) *Worker { return &Worker{svc: svc} }

// Run processes jobs and runs periodic cleanup until ctx is cancelled.
func (w *Worker) Run(ctx context.Context, opt asynq.RedisClientOpt, concurrency int) error {
	srv := asynq.NewServer(opt, asynq.Config{
		Concurrency: concurrency,
		Logger:      asynqLogger{},
		// Retry quickly: users are watching a progress bar.
		RetryDelayFunc: func(n int, _ error, _ *asynq.Task) time.Duration { return time.Duration(n+1) * 2 * time.Second },
	})
	mux := asynq.NewServeMux()
	mux.HandleFunc(queue.TypeProcessJob, w.handle)
	if err := srv.Start(mux); err != nil {
		return err
	}
	go w.cleanupLoop(ctx)
	<-ctx.Done()
	srv.Shutdown()
	return nil
}

func (w *Worker) cleanupLoop(ctx context.Context) {
	t := time.NewTicker(5 * time.Minute)
	defer t.Stop()
	for {
		n, err := w.svc.CleanupExpired(ctx)
		if err != nil {
			slog.Error("cleanup expired files", "err", err)
		} else if n > 0 {
			slog.Info("cleaned up expired files", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (w *Worker) handle(ctx context.Context, t *asynq.Task) error {
	id, err := uuid.Parse(string(t.Payload()))
	if err != nil {
		return fmt.Errorf("bad payload: %w", asynq.SkipRetry)
	}
	log := slog.With("job_id", id)
	ok, err := w.svc.Store.StartJob(ctx, id)
	if err != nil {
		return err
	}
	if !ok {
		return nil // already finished
	}
	job, err := w.svc.Store.JobByID(ctx, id)
	if err != nil {
		return err
	}
	start := time.Now()
	out, err := w.process(ctx, job)
	if err == nil {
		log.Info("job done", "tool", job.Tool, "ms", time.Since(start).Milliseconds())
		return w.svc.Store.CompleteJob(context.WithoutCancel(ctx), id, out.ID)
	}

	if ue, ok := pdf.AsUserError(err); ok {
		log.Info("job rejected", "tool", job.Tool, "code", ue.Code, "msg", ue.Message)
		_ = w.svc.Store.FailJob(context.WithoutCancel(ctx), id, ue.Code, ue.Message)
		return nil
	}
	retry, _ := asynq.GetRetryCount(ctx)
	maxRetry, _ := asynq.GetMaxRetry(ctx)
	log.Error("job failed", "tool", job.Tool, "attempt", retry+1, "err", err)
	if retry >= maxRetry || errors.Is(ctx.Err(), context.DeadlineExceeded) {
		_ = w.svc.Store.FailJob(context.WithoutCancel(ctx), id, "internal_error", "something went wrong while processing your file")
		return nil
	}
	return err
}

func (w *Worker) process(ctx context.Context, job *store.Job) (*store.File, error) {
	tool, ok := pdf.Lookup(job.Tool)
	if !ok {
		return nil, &pdf.UserError{Code: "unknown_tool", Message: "unknown tool"}
	}
	dir, err := os.MkdirTemp("", "job-"+job.ID.String())
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	inDir, outDir := filepath.Join(dir, "in"), filepath.Join(dir, "out")
	if err := os.Mkdir(inDir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Mkdir(outDir, 0o700); err != nil {
		return nil, err
	}

	files, err := w.svc.Store.FilesByIDs(ctx, job.InputIDs)
	if errors.Is(err, store.ErrNotFound) {
		return nil, &pdf.UserError{Code: "file_expired", Message: "an input file has expired; please upload it again"}
	} else if err != nil {
		return nil, err
	}
	inputs := make([]pdf.Input, len(files))
	for i, f := range files {
		// Local names are index-based with an extension from the sniffed type;
		// the original name is only used for naming the output.
		p := filepath.Join(inDir, fmt.Sprintf("%03d%s", i, pdf.ExtForMime(f.Mime)))
		if err := w.svc.Download(ctx, f, p); err != nil {
			return nil, fmt.Errorf("download input: %w", err)
		}
		inputs[i] = pdf.Input{Path: p, Name: f.Name}
	}
	_ = w.svc.Store.SetJobProgress(ctx, job.ID, 30)

	out, err := tool.Run(ctx, inputs, job.Options, outDir)
	if err != nil {
		return nil, err
	}
	_ = w.svc.Store.SetJobProgress(ctx, job.ID, 90)

	owner := job.Owner()
	saved, err := w.svc.SaveFile(ctx, owner, "output", out.Name, out.Mime, out.Path, &job.ID)
	if err != nil {
		return nil, err
	}
	_ = w.svc.Store.AddUsage(ctx, owner.Subject(), 1, saved.Size)
	if owner.IsUser() {
		if err := w.svc.EnforceQuota(ctx, *owner.UserID); err != nil {
			slog.Warn("enforce quota", "user_id", owner.UserID, "err", err)
		}
	}
	return saved, nil
}

type asynqLogger struct{}

func (asynqLogger) Debug(args ...any) {}
func (asynqLogger) Info(args ...any)  { slog.Info(fmt.Sprint(args...)) }
func (asynqLogger) Warn(args ...any)  { slog.Warn(fmt.Sprint(args...)) }
func (asynqLogger) Error(args ...any) { slog.Error(fmt.Sprint(args...)) }
func (asynqLogger) Fatal(args ...any) { slog.Error(fmt.Sprint(args...)); os.Exit(1) }
