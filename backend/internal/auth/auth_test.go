package auth

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestAccessToken(t *testing.T) {
	tk := NewTokens("secret-one", time.Minute)
	id := uuid.New()
	s, _, err := tk.IssueAccess(id, "free")
	if err != nil {
		t.Fatal(err)
	}
	got, plan, err := tk.ParseAccess(s)
	if err != nil || got != id || plan != "free" {
		t.Fatalf("parse = %v %q %v", got, plan, err)
	}
	if _, _, err := NewTokens("secret-two", time.Minute).ParseAccess(s); err == nil {
		t.Fatal("token verified with the wrong secret")
	}
	expired, _, _ := NewTokens("secret-one", -time.Minute).IssueAccess(id, "free")
	if _, _, err := tk.ParseAccess(expired); err == nil {
		t.Fatal("expired token accepted")
	}
}

func TestSignedLink(t *testing.T) {
	tk := NewTokens("secret", time.Minute)
	id := uuid.New()
	link := tk.SignLink(id, time.Now().Add(time.Minute))
	if got, err := tk.VerifyLink(link); err != nil || got != id {
		t.Fatalf("verify = %v %v", got, err)
	}
	if _, err := tk.VerifyLink(tk.SignLink(id, time.Now().Add(-time.Second))); err == nil {
		t.Fatal("expired link accepted")
	}
	other := tk.SignLink(uuid.New(), time.Now().Add(time.Minute))
	forged := link[:strings.LastIndex(link, ".")] + other[strings.LastIndex(other, "."):]
	if _, err := tk.VerifyLink(forged); err == nil {
		t.Fatal("forged link accepted")
	}
	for _, bad := range []string{"", ".", "abc", "a.b.c"} {
		if _, err := tk.VerifyLink(bad); err == nil {
			t.Fatalf("garbage %q accepted", bad)
		}
	}
}

func TestPassword(t *testing.T) {
	h, err := HashPassword("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !CheckPassword(h, "correct horse") || CheckPassword(h, "wrong") {
		t.Fatal("password check broken")
	}
}
