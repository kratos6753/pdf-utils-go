// Package auth issues and verifies tokens and hashes passwords.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

var ErrInvalidToken = errors.New("invalid token")

type Tokens struct {
	secret    []byte
	accessTTL time.Duration
}

func NewTokens(secret string, accessTTL time.Duration) *Tokens {
	return &Tokens{secret: []byte(secret), accessTTL: accessTTL}
}

// Claims are carried in access tokens. Plan is included so limits can be
// applied without a database lookup; it may lag by up to one token lifetime.
type Claims struct {
	Plan string `json:"plan"`
	jwt.RegisteredClaims
}

func (t *Tokens) IssueAccess(userID uuid.UUID, plan string) (string, time.Time, error) {
	exp := time.Now().Add(t.accessTTL)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, Claims{
		Plan: plan,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID.String(),
			Audience:  jwt.ClaimStrings{"access"},
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(exp),
		},
	})
	s, err := tok.SignedString(t.secret)
	return s, exp, err
}

func (t *Tokens) ParseAccess(s string) (uuid.UUID, string, error) {
	var c Claims
	_, err := jwt.ParseWithClaims(s, &c, func(*jwt.Token) (any, error) { return t.secret, nil },
		jwt.WithValidMethods([]string{"HS256"}), jwt.WithAudience("access"), jwt.WithExpirationRequired())
	if err != nil {
		return uuid.Nil, "", ErrInvalidToken
	}
	id, err := uuid.Parse(c.Subject)
	if err != nil {
		return uuid.Nil, "", ErrInvalidToken
	}
	return id, c.Plan, nil
}

// AdminTTL is how long an admin session lasts; there is no refresh, admins sign in again.
const AdminTTL = 8 * time.Hour

// AdminClaims are carried in admin tokens. PasswordVersion pins the token to the
// password it was issued under, so changing the password signs out old sessions.
type AdminClaims struct {
	PasswordVersion int64 `json:"pv"`
	jwt.RegisteredClaims
}

// IssueAdmin returns an admin token. Its audience differs from user access
// tokens, so neither kind is accepted in place of the other.
func (t *Tokens) IssueAdmin(adminID uuid.UUID, passwordVersion int64) (string, time.Time, error) {
	exp := time.Now().Add(AdminTTL)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, AdminClaims{
		PasswordVersion: passwordVersion,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   adminID.String(),
			Audience:  jwt.ClaimStrings{"admin"},
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(exp),
		},
	})
	s, err := tok.SignedString(t.secret)
	return s, exp, err
}

func (t *Tokens) ParseAdmin(s string) (uuid.UUID, int64, error) {
	var c AdminClaims
	_, err := jwt.ParseWithClaims(s, &c, func(*jwt.Token) (any, error) { return t.secret, nil },
		jwt.WithValidMethods([]string{"HS256"}), jwt.WithAudience("admin"), jwt.WithExpirationRequired())
	if err != nil {
		return uuid.Nil, 0, ErrInvalidToken
	}
	id, err := uuid.Parse(c.Subject)
	if err != nil {
		return uuid.Nil, 0, ErrInvalidToken
	}
	return id, c.PasswordVersion, nil
}

// HMAC returns a keyed hash of s, used for anonymous ids and signed links.
func (t *Tokens) HMAC(s string) string {
	m := hmac.New(sha256.New, t.secret)
	m.Write([]byte(s))
	return hex.EncodeToString(m.Sum(nil))
}

// SignLink returns a token authorizing download of fileID until exp.
func (t *Tokens) SignLink(fileID uuid.UUID, exp time.Time) string {
	payload := fmt.Sprintf("%s.%d", fileID, exp.Unix())
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." + t.HMAC("dl:" + payload)[:32]
}

// VerifyLink checks a SignLink token and returns its file id.
func (t *Tokens) VerifyLink(tok string) (uuid.UUID, error) {
	var b64, mac string
	for i := len(tok) - 1; i >= 0; i-- {
		if tok[i] == '.' {
			b64, mac = tok[:i], tok[i+1:]
			break
		}
	}
	raw, err := base64.RawURLEncoding.DecodeString(b64)
	if err != nil {
		return uuid.Nil, ErrInvalidToken
	}
	payload := string(raw)
	if !hmac.Equal([]byte(mac), []byte(t.HMAC("dl:" + payload)[:32])) {
		return uuid.Nil, ErrInvalidToken
	}
	var idStr string
	var exp int64
	if _, err := fmt.Sscanf(payload, "%36s.%d", &idStr, &exp); err != nil || time.Now().Unix() > exp {
		return uuid.Nil, ErrInvalidToken
	}
	return uuid.Parse(idStr)
}

// RandomToken returns an opaque random token and its storage hash.
func RandomToken() (token, hash string) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, HashToken(token)
}

// HashToken hashes an opaque token for storage; tokens are high-entropy, so
// an unsalted SHA-256 is sufficient.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func HashPassword(pw string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(pw), 12)
	return string(b), err
}

func CheckPassword(hash, pw string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(pw)) == nil
}

// dummyHash lets failed logins for unknown emails take as long as real ones.
var dummyHash, _ = bcrypt.GenerateFromPassword([]byte("timing-equalizer"), 12)

func CheckPasswordDummy(pw string) { _ = bcrypt.CompareHashAndPassword(dummyHash, []byte(pw)) }
