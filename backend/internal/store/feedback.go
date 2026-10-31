package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

type Feedback struct {
	ID        uuid.UUID
	UserID    *uuid.UUID
	Name      string
	Email     string
	Topic     string
	Message   string
	Client    string
	CreatedAt time.Time
	ReadAt    *time.Time
}

const feedbackCols = `id, user_id, name, email, topic, message, client, created_at, read_at`

func scanFeedback(row interface{ Scan(...any) error }) (*Feedback, error) {
	f := &Feedback{}
	if err := row.Scan(&f.ID, &f.UserID, &f.Name, &f.Email, &f.Topic, &f.Message, &f.Client, &f.CreatedAt, &f.ReadAt); err != nil {
		return nil, notFound(err)
	}
	return f, nil
}

// CreateFeedback stores a message. The sender is linked only if their account
// still exists: an access token can outlive a deleted account by a few minutes.
func (s *Store) CreateFeedback(ctx context.Context, f *Feedback) (*Feedback, error) {
	return scanFeedback(s.db.QueryRow(ctx, `
		INSERT INTO feedback (user_id, name, email, topic, message, client)
		VALUES ((SELECT id FROM users WHERE id = $1), $2, $3, $4, $5, $6) RETURNING `+feedbackCols,
		f.UserID, f.Name, f.Email, f.Topic, f.Message, f.Client))
}

// ListFeedback pages through messages, newest first, starting before (beforeT, beforeID).
func (s *Store) ListFeedback(ctx context.Context, unreadOnly bool, beforeT time.Time, beforeID uuid.UUID, limit int) ([]*Feedback, error) {
	if beforeT.IsZero() {
		beforeT = time.Now().Add(time.Hour)
	}
	rows, err := s.db.Query(ctx, `SELECT `+feedbackCols+` FROM feedback
		WHERE (NOT $1 OR read_at IS NULL) AND (created_at, id) < ($2, $3)
		ORDER BY created_at DESC, id DESC LIMIT $4`, unreadOnly, beforeT, beforeID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Feedback
	for rows.Next() {
		f, err := scanFeedback(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (s *Store) CountUnreadFeedback(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRow(ctx, `SELECT count(*) FROM feedback WHERE read_at IS NULL`).Scan(&n)
	return n, err
}

// SetFeedbackRead marks a message read or unread and returns it.
func (s *Store) SetFeedbackRead(ctx context.Context, id uuid.UUID, read bool) (*Feedback, error) {
	return scanFeedback(s.db.QueryRow(ctx, `
		UPDATE feedback SET read_at = CASE WHEN $2 THEN COALESCE(read_at, now()) END
		WHERE id = $1 RETURNING `+feedbackCols, id, read))
}
