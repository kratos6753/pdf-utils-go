package store

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"
)

const jobCols = `id, user_id, anon_key, tool, options, status, progress, error_code, error_message,
	input_ids::text[], output_id, created_at, started_at, finished_at`

func scanJob(row interface{ Scan(...any) error }) (*Job, error) {
	j := &Job{}
	var inputs []string
	err := row.Scan(&j.ID, &j.UserID, &j.AnonKey, &j.Tool, &j.Options, &j.Status, &j.Progress, &j.ErrorCode, &j.ErrorMessage,
		&inputs, &j.OutputID, &j.CreatedAt, &j.StartedAt, &j.FinishedAt)
	if err != nil {
		return nil, notFound(err)
	}
	for _, s := range inputs {
		id, err := uuid.Parse(s)
		if err != nil {
			return nil, err
		}
		j.InputIDs = append(j.InputIDs, id)
	}
	return j, nil
}

func uuidStrings(ids []uuid.UUID) []string {
	out := make([]string, len(ids))
	for i, id := range ids {
		out[i] = id.String()
	}
	return out
}

// CreateJob inserts a queued job. With a non-empty idempotency key, a retry
// returns the original job and created=false.
func (s *Store) CreateJob(ctx context.Context, o Owner, tool string, opts json.RawMessage, inputs []uuid.UUID, idemKey string) (job *Job, created bool, err error) {
	var key *string
	if idemKey != "" {
		key = &idemKey
	}
	job, err = scanJob(s.db.QueryRow(ctx, `
		INSERT INTO jobs (user_id, anon_key, tool, options, input_ids, idempotency_key)
		VALUES ($1, $2, $3, $4, $5::uuid[], $6) RETURNING `+jobCols,
		o.UserID, o.anonKey(), tool, opts, uuidStrings(inputs), key))
	if err == nil {
		return job, true, nil
	}
	if !isUniqueViolation(err) || key == nil {
		return nil, false, err
	}
	job, err = scanJob(s.db.QueryRow(ctx, `SELECT `+jobCols+` FROM jobs
		WHERE COALESCE(user_id::text, anon_key) = $1 AND idempotency_key = $2`, o.Subject()[2:], idemKey))
	return job, false, err
}

func (s *Store) JobForOwner(ctx context.Context, id uuid.UUID, o Owner) (*Job, error) {
	return scanJob(s.db.QueryRow(ctx, `SELECT `+jobCols+` FROM jobs WHERE id = $1 AND `+sprintf(ownerCond, 2, 3),
		id, o.UserID, o.anonKey()))
}

func (s *Store) JobByID(ctx context.Context, id uuid.UUID) (*Job, error) {
	return scanJob(s.db.QueryRow(ctx, `SELECT `+jobCols+` FROM jobs WHERE id = $1`, id))
}

// StartJob moves a job to processing; false if it was not queued (e.g. a
// redelivered task for a job that already finished).
func (s *Store) StartJob(ctx context.Context, id uuid.UUID) (bool, error) {
	tag, err := s.db.Exec(ctx, `UPDATE jobs SET status = 'processing', started_at = now(), progress = 10
		WHERE id = $1 AND status IN ('queued', 'processing')`, id)
	return tag.RowsAffected() == 1, err
}

func (s *Store) SetJobProgress(ctx context.Context, id uuid.UUID, p int) error {
	_, err := s.db.Exec(ctx, `UPDATE jobs SET progress = $2 WHERE id = $1 AND status = 'processing'`, id, p)
	return err
}

// Secrets (passwords) are stripped from options once a job finishes.
const scrubOptions = `options = options - 'password' - 'owner_password'`

func (s *Store) CompleteJob(ctx context.Context, id, outputID uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE jobs SET status = 'done', progress = 100, output_id = $2, finished_at = now(), `+scrubOptions+`
		WHERE id = $1`, id, outputID)
	return err
}

func (s *Store) FailJob(ctx context.Context, id uuid.UUID, code, msg string) error {
	_, err := s.db.Exec(ctx, `UPDATE jobs SET status = 'failed', error_code = $2, error_message = $3, finished_at = now(), `+scrubOptions+`
		WHERE id = $1`, id, code, msg)
	return err
}
