package store

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/google/uuid"
)

const userCols = `id, email, password_hash, google_sub, name, avatar_url, plan, email_verified, created_at`

func scanUser(row interface{ Scan(...any) error }) (*User, error) {
	u := &User{}
	err := row.Scan(&u.ID, &u.Email, &u.PasswordHash, &u.GoogleSub, &u.Name, &u.AvatarURL, &u.Plan, &u.EmailVerified, &u.CreatedAt)
	if err != nil {
		return nil, notFound(err)
	}
	return u, nil
}

func normEmail(e string) string { return strings.ToLower(strings.TrimSpace(e)) }

// CreateUser inserts a user. passwordHash/googleSub may be nil.
func (s *Store) CreateUser(ctx context.Context, email, name string, passwordHash, googleSub *string, avatar string, verified bool) (*User, error) {
	u, err := scanUser(s.db.QueryRow(ctx, `
		INSERT INTO users (email, name, password_hash, google_sub, avatar_url, email_verified)
		VALUES ($1, $2, $3, $4, $5, $6) RETURNING `+userCols,
		normEmail(email), name, passwordHash, googleSub, avatar, verified))
	if err != nil && isUniqueViolation(err) {
		return nil, ErrDuplicate
	}
	return u, err
}

func (s *Store) UserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	return scanUser(s.db.QueryRow(ctx, `SELECT `+userCols+` FROM users WHERE id = $1`, id))
}

func (s *Store) UserByEmail(ctx context.Context, email string) (*User, error) {
	return scanUser(s.db.QueryRow(ctx, `SELECT `+userCols+` FROM users WHERE email = $1`, normEmail(email)))
}

func (s *Store) UserByGoogleSub(ctx context.Context, sub string) (*User, error) {
	return scanUser(s.db.QueryRow(ctx, `SELECT `+userCols+` FROM users WHERE google_sub = $1`, sub))
}

// LinkGoogle attaches a Google identity to an existing account. Google has
// verified the email, so the account becomes verified too.
func (s *Store) LinkGoogle(ctx context.Context, id uuid.UUID, sub, avatar string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE users SET google_sub = $2, email_verified = true,
			avatar_url = CASE WHEN avatar_url = '' THEN $3 ELSE avatar_url END, updated_at = now()
		WHERE id = $1`, id, sub, avatar)
	return err
}

func (s *Store) SetEmailVerified(ctx context.Context, id uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE users SET email_verified = true, updated_at = now() WHERE id = $1`, id)
	return err
}

func (s *Store) SetPassword(ctx context.Context, id uuid.UUID, hash string) error {
	_, err := s.db.Exec(ctx, `UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1`, id, hash)
	return err
}

func (s *Store) ClearPassword(ctx context.Context, id uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE users SET password_hash = NULL, updated_at = now() WHERE id = $1`, id)
	return err
}

func (s *Store) UpdateProfile(ctx context.Context, id uuid.UUID, name string) error {
	_, err := s.db.Exec(ctx, `UPDATE users SET name = $2, updated_at = now() WHERE id = $1`, id, name)
	return err
}

func (s *Store) DeleteUser(ctx context.Context, id uuid.UUID) error {
	_, err := s.db.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	return err
}

// PlanLimits returns the limits for a plan code.
func (s *Store) PlanLimits(ctx context.Context, code string) (*Limits, error) {
	var raw []byte
	if err := s.db.QueryRow(ctx, `SELECT limits FROM plans WHERE code = $1`, code).Scan(&raw); err != nil {
		return nil, notFound(err)
	}
	l := &Limits{}
	return l, json.Unmarshal(raw, l)
}

// --- refresh tokens ---

type RefreshToken struct {
	ID        uuid.UUID
	UserID    uuid.UUID
	DeviceID  string
	ExpiresAt time.Time
	RevokedAt *time.Time
}

func (s *Store) CreateRefreshToken(ctx context.Context, userID uuid.UUID, hash, deviceID string, exp time.Time) error {
	_, err := s.db.Exec(ctx, `INSERT INTO refresh_tokens (user_id, token_hash, device_id, expires_at) VALUES ($1, $2, $3, $4)`,
		userID, hash, deviceID, exp)
	return err
}

func (s *Store) RefreshTokenByHash(ctx context.Context, hash string) (*RefreshToken, error) {
	t := &RefreshToken{}
	err := s.db.QueryRow(ctx, `SELECT id, user_id, device_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = $1`, hash).
		Scan(&t.ID, &t.UserID, &t.DeviceID, &t.ExpiresAt, &t.RevokedAt)
	return t, notFound(err)
}

// RevokeRefreshToken revokes a token, reporting whether it was still active.
// The conditional update makes concurrent rotation of one token safe.
func (s *Store) RevokeRefreshToken(ctx context.Context, id uuid.UUID) (bool, error) {
	tag, err := s.db.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL`, id)
	return tag.RowsAffected() == 1, err
}

func (s *Store) RevokeUserRefreshTokens(ctx context.Context, userID uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, userID)
	return err
}

// --- email tokens ---

func (s *Store) CreateEmailToken(ctx context.Context, userID uuid.UUID, hash, purpose string, exp time.Time) error {
	_, err := s.db.Exec(ctx, `INSERT INTO email_tokens (token_hash, user_id, purpose, expires_at) VALUES ($1, $2, $3, $4)`,
		hash, userID, purpose, exp)
	return err
}

// ConsumeEmailToken marks a token used and returns its user, if it is valid.
func (s *Store) ConsumeEmailToken(ctx context.Context, hash, purpose string) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.db.QueryRow(ctx, `
		UPDATE email_tokens SET used_at = now()
		WHERE token_hash = $1 AND purpose = $2 AND used_at IS NULL AND expires_at > now()
		RETURNING user_id`, hash, purpose).Scan(&id)
	return id, notFound(err)
}
