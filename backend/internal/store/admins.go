package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

type Admin struct {
	ID                 uuid.UUID
	Email              string
	PasswordHash       string
	MustChangePassword bool
	PasswordChangedAt  time.Time
	LastLoginAt        *time.Time
	CreatedAt          time.Time
}

const adminCols = `id, email, password_hash, must_change_password, password_changed_at, last_login_at, created_at`

func scanAdmin(row interface{ Scan(...any) error }) (*Admin, error) {
	a := &Admin{}
	if err := row.Scan(&a.ID, &a.Email, &a.PasswordHash, &a.MustChangePassword, &a.PasswordChangedAt, &a.LastLoginAt, &a.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	return a, nil
}

func (s *Store) HasAdmin(ctx context.Context) (bool, error) {
	var ok bool
	err := s.db.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM admins)`).Scan(&ok)
	return ok, err
}

// EnsureAdmin creates the first admin, who must change the password on first
// login. It does nothing once any admin exists, so it's safe to run on every start.
func (s *Store) EnsureAdmin(ctx context.Context, email, passwordHash string) error {
	_, err := s.db.Exec(ctx, `
		INSERT INTO admins (email, password_hash)
		SELECT $1, $2 WHERE NOT EXISTS (SELECT 1 FROM admins)
		ON CONFLICT (email) DO NOTHING`, normEmail(email), passwordHash)
	return err
}

func (s *Store) AdminByID(ctx context.Context, id uuid.UUID) (*Admin, error) {
	return scanAdmin(s.db.QueryRow(ctx, `SELECT `+adminCols+` FROM admins WHERE id = $1`, id))
}

func (s *Store) AdminByEmail(ctx context.Context, email string) (*Admin, error) {
	return scanAdmin(s.db.QueryRow(ctx, `SELECT `+adminCols+` FROM admins WHERE email = $1`, normEmail(email)))
}

func (s *Store) TouchAdminLogin(ctx context.Context, id uuid.UUID) error {
	_, err := s.db.Exec(ctx, `UPDATE admins SET last_login_at = now() WHERE id = $1`, id)
	return err
}

// SetAdminPassword stores a new password and invalidates tokens issued before now.
func (s *Store) SetAdminPassword(ctx context.Context, id uuid.UUID, hash string) (*Admin, error) {
	return scanAdmin(s.db.QueryRow(ctx, `
		UPDATE admins SET password_hash = $2, must_change_password = false, password_changed_at = now()
		WHERE id = $1 RETURNING `+adminCols, id, hash))
}
