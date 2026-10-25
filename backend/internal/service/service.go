// Package service holds file and quota logic shared by the API and the worker.
package service

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"sync"
	"time"

	"github.com/google/uuid"

	"pdfutils/internal/config"
	"pdfutils/internal/storage"
	"pdfutils/internal/store"
)

type Service struct {
	Store   *store.Store
	Storage *storage.Storage
	Cfg     *config.Config

	mu     sync.Mutex
	limits map[string]cachedLimits
}

type cachedLimits struct {
	l   *store.Limits
	exp time.Time
}

func New(st *store.Store, sg *storage.Storage, cfg *config.Config) *Service {
	return &Service{Store: st, Storage: sg, Cfg: cfg, limits: map[string]cachedLimits{}}
}

// PlanLimits returns limits for a plan, cached for a minute.
func (s *Service) PlanLimits(ctx context.Context, plan string) (*store.Limits, error) {
	s.mu.Lock()
	c, ok := s.limits[plan]
	s.mu.Unlock()
	if ok && time.Now().Before(c.exp) {
		return c.l, nil
	}
	l, err := s.Store.PlanLimits(ctx, plan)
	if err != nil {
		return nil, fmt.Errorf("plan %q: %w", plan, err)
	}
	s.mu.Lock()
	s.limits[plan] = cachedLimits{l: l, exp: time.Now().Add(time.Minute)}
	s.mu.Unlock()
	return l, nil
}

// SaveFile uploads a local file to storage and records it for owner.
// Anonymous files expire; users' files are kept in their history.
func (s *Service) SaveFile(ctx context.Context, o store.Owner, kind, name, mime, path string, jobID *uuid.UUID) (*store.File, error) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer fh.Close()
	fi, err := fh.Stat()
	if err != nil {
		return nil, err
	}
	f := &store.File{ID: uuid.New(), Kind: kind, Name: name, Size: fi.Size(), Mime: mime, JobID: jobID}
	// Key prefixes let bucket lifecycle rules treat temporary and kept files differently.
	if o.IsUser() {
		f.UserID = o.UserID
		f.S3Key = fmt.Sprintf("users/%s/%s", o.UserID, f.ID)
	} else {
		ak := o.AnonKey
		exp := time.Now().Add(s.Cfg.AnonRetention)
		f.AnonKey, f.ExpiresAt = &ak, &exp
		f.S3Key = "tmp/" + f.ID.String()
	}
	if err := s.Storage.Put(ctx, f.S3Key, fh, f.Size, mime); err != nil {
		return nil, fmt.Errorf("upload to storage: %w", err)
	}
	if err := s.Store.CreateFile(ctx, f); err != nil {
		_ = s.Storage.Delete(context.WithoutCancel(ctx), f.S3Key)
		return nil, err
	}
	return f, nil
}

// Download copies a stored file to a local path.
func (s *Service) Download(ctx context.Context, f *store.File, dst string) error {
	r, err := s.Storage.Get(ctx, f.S3Key)
	if err != nil {
		return err
	}
	defer r.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, r); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// DeleteFiles removes files from storage and marks them deleted. Objects that
// fail to delete are skipped (and retried by a later cleanup run); it returns
// how many were removed.
func (s *Service) DeleteFiles(ctx context.Context, files []*store.File) (int, error) {
	ids := make([]uuid.UUID, 0, len(files))
	for _, f := range files {
		if err := s.Storage.Delete(ctx, f.S3Key); err != nil {
			slog.WarnContext(ctx, "delete object", "key", f.S3Key, "err", err)
			continue
		}
		ids = append(ids, f.ID)
	}
	if len(ids) == 0 {
		return 0, nil
	}
	return len(ids), s.Store.MarkFilesDeleted(ctx, ids)
}

// EnforceQuota evicts a user's oldest files until they fit in their plan's
// storage allowance. Files that pending jobs still need are never evicted.
func (s *Service) EnforceQuota(ctx context.Context, userID uuid.UUID) error {
	u, err := s.Store.UserByID(ctx, userID)
	if err != nil {
		return err
	}
	l, err := s.PlanLimits(ctx, u.Plan)
	if err != nil {
		return err
	}
	limit := int64(l.StorageMB) << 20
	for {
		used, err := s.Store.UserStorageBytes(ctx, userID)
		if err != nil || used <= limit {
			return err
		}
		victims, err := s.Store.EvictableFiles(ctx, userID, 20)
		if err != nil || len(victims) == 0 {
			return err
		}
		var batch []*store.File
		for _, f := range victims {
			batch = append(batch, f)
			if used -= f.Size; used <= limit {
				break
			}
		}
		if n, err := s.DeleteFiles(ctx, batch); err != nil || n == 0 {
			return err
		}
	}
}

// CleanupExpired deletes files past their retention. Safe to run concurrently.
func (s *Service) CleanupExpired(ctx context.Context) (int, error) {
	total := 0
	for {
		files, err := s.Store.ExpiredFiles(ctx, 200)
		if err != nil || len(files) == 0 {
			return total, err
		}
		n, err := s.DeleteFiles(ctx, files)
		total += n
		if err != nil || n < len(files) {
			return total, err
		}
		if len(files) < 200 {
			return total, nil
		}
	}
}
