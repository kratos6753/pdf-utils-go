package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

const fileCols = `id, user_id, anon_key, job_id, kind, name, size, mime, s3_key, expires_at, created_at`

// ownerCond matches rows owned by Owner, with $N = user id and $N+1 = anon key.
const ownerCond = `(($%[1]d::uuid IS NOT NULL AND user_id = $%[1]d) OR ($%[1]d::uuid IS NULL AND anon_key = $%[2]d))`

func scanFile(row interface{ Scan(...any) error }) (*File, error) {
	f := &File{}
	err := row.Scan(&f.ID, &f.UserID, &f.AnonKey, &f.JobID, &f.Kind, &f.Name, &f.Size, &f.Mime, &f.S3Key, &f.ExpiresAt, &f.CreatedAt)
	if err != nil {
		return nil, notFound(err)
	}
	return f, nil
}

// CreateFile inserts f, filling in ID and CreatedAt if unset.
func (s *Store) CreateFile(ctx context.Context, f *File) error {
	if f.ID == uuid.Nil {
		f.ID = uuid.New()
	}
	return s.db.QueryRow(ctx, `
		INSERT INTO files (id, user_id, anon_key, job_id, kind, name, size, mime, s3_key, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING created_at`,
		f.ID, f.UserID, f.AnonKey, f.JobID, f.Kind, f.Name, f.Size, f.Mime, f.S3Key, f.ExpiresAt).Scan(&f.CreatedAt)
}

func (s *Store) FileForOwner(ctx context.Context, id uuid.UUID, o Owner) (*File, error) {
	return scanFile(s.db.QueryRow(ctx, `SELECT `+fileCols+` FROM files
		WHERE id = $1 AND deleted_at IS NULL AND `+sprintf(ownerCond, 2, 3), id, o.UserID, o.anonKey()))
}

// FilesForOwner returns the live files with the given ids that o owns, in the order of ids.
func (s *Store) FilesForOwner(ctx context.Context, ids []uuid.UUID, o Owner) ([]*File, error) {
	rows, err := s.db.Query(ctx, `SELECT `+fileCols+` FROM files
		WHERE id = ANY($1) AND deleted_at IS NULL AND `+sprintf(ownerCond, 2, 3), ids, o.UserID, o.anonKey())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[uuid.UUID]*File{}
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		byID[f.ID] = f
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]*File, 0, len(ids))
	for _, id := range ids {
		f, ok := byID[id]
		if !ok {
			return nil, ErrNotFound
		}
		out = append(out, f)
	}
	return out, nil
}

// FilesByIDs loads files without ownership checks (worker use), in order of ids.
func (s *Store) FilesByIDs(ctx context.Context, ids []uuid.UUID) ([]*File, error) {
	rows, err := s.db.Query(ctx, `SELECT `+fileCols+` FROM files WHERE id = ANY($1) AND deleted_at IS NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[uuid.UUID]*File{}
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		byID[f.ID] = f
	}
	out := make([]*File, 0, len(ids))
	for _, id := range ids {
		f, ok := byID[id]
		if !ok {
			return nil, ErrNotFound
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// ListUserFiles pages through a user's history, newest first. Pass a zero
// before time for the first page.
func (s *Store) ListUserFiles(ctx context.Context, userID uuid.UUID, kind string, beforeT time.Time, beforeID uuid.UUID, limit int) ([]*File, error) {
	if beforeT.IsZero() {
		beforeT = time.Now().Add(time.Hour)
	}
	rows, err := s.db.Query(ctx, `SELECT `+fileCols+` FROM files
		WHERE user_id = $1 AND deleted_at IS NULL AND ($2 = '' OR kind = $2) AND (created_at, id) < ($3, $4)
		ORDER BY created_at DESC, id DESC LIMIT $5`, userID, kind, beforeT, beforeID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*File
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// UserStorageBytes is the total size of a user's stored files.
func (s *Store) UserStorageBytes(ctx context.Context, userID uuid.UUID) (int64, error) {
	var n int64
	err := s.db.QueryRow(ctx, `SELECT COALESCE(SUM(size), 0) FROM files WHERE user_id = $1 AND deleted_at IS NULL`, userID).Scan(&n)
	return n, err
}

// EvictableFiles returns a user's oldest files that no pending job still needs.
func (s *Store) EvictableFiles(ctx context.Context, userID uuid.UUID, limit int) ([]*File, error) {
	rows, err := s.db.Query(ctx, `SELECT `+fileCols+` FROM files f
		WHERE user_id = $1 AND deleted_at IS NULL
		  AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.status IN ('queued', 'processing') AND f.id = ANY(j.input_ids))
		ORDER BY created_at ASC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*File
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// ExpiredFiles returns files past their retention time.
func (s *Store) ExpiredFiles(ctx context.Context, limit int) ([]*File, error) {
	rows, err := s.db.Query(ctx, `SELECT `+fileCols+` FROM files
		WHERE deleted_at IS NULL AND expires_at IS NOT NULL AND expires_at < now()
		ORDER BY expires_at LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*File
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (s *Store) MarkFilesDeleted(ctx context.Context, ids []uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE files SET deleted_at = now() WHERE id = ANY($1)`, ids)
	return err
}

// ClaimAnonFiles moves an anonymous client's unexpired files into a user's
// history, so work done before signing up is not lost.
func (s *Store) ClaimAnonFiles(ctx context.Context, anonKey string, userID uuid.UUID) error {
	if _, err := s.db.Exec(ctx, `
		UPDATE files SET user_id = $2, anon_key = NULL, expires_at = NULL
		WHERE anon_key = $1 AND deleted_at IS NULL AND expires_at > now()`, anonKey, userID); err != nil {
		return err
	}
	_, err := s.db.Exec(ctx, `UPDATE jobs SET user_id = $2, anon_key = NULL WHERE anon_key = $1`, anonKey, userID)
	return err
}

func (s *Store) AddUsage(ctx context.Context, subject string, tasks int, bytes int64) error {
	_, err := s.db.Exec(ctx, `
		INSERT INTO usage_daily (subject, day, tasks, bytes) VALUES ($1, current_date, $2, $3)
		ON CONFLICT (subject, day) DO UPDATE SET tasks = usage_daily.tasks + $2, bytes = usage_daily.bytes + $3`,
		subject, tasks, bytes)
	return err
}
