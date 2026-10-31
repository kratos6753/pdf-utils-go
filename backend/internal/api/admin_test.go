package api

import (
	"strings"
	"testing"
)

func TestValidateFeedback(t *testing.T) {
	ok := feedbackRequest{Name: " Ana ", Email: " ana@example.com ", Message: "  The merge tool is great!  "}
	if code, _ := validateFeedback(&ok); code != "" {
		t.Fatalf("valid request rejected: %s", code)
	}
	if ok.Name != "Ana" || ok.Email != "ana@example.com" || ok.Message != "The merge tool is great!" || ok.Topic != "feedback" {
		t.Fatalf("not normalized: %+v", ok)
	}

	cases := map[string]feedbackRequest{
		"invalid_name":      {Name: strings.Repeat("a", 101), Email: "a@b.co", Message: "long enough message"},
		"invalid_email":     {Email: "not-an-email", Message: "long enough message"},
		"invalid_topic":     {Email: "a@b.co", Topic: "spam", Message: "long enough message"},
		"message_too_short": {Email: "a@b.co", Message: "   short   "},
		"message_too_long":  {Email: "a@b.co", Message: strings.Repeat("x", 5001)},
	}
	for want, req := range cases {
		if got, _ := validateFeedback(&req); got != want {
			t.Errorf("%s: got %q", want, got)
		}
	}
	// Limits count characters, not bytes.
	unicode := feedbackRequest{Email: "a@b.co", Message: strings.Repeat("é", 5000)}
	if code, _ := validateFeedback(&unicode); code != "" {
		t.Fatalf("5000 accented characters rejected: %s", code)
	}
}

func TestAdminPasswordProblem(t *testing.T) {
	const initial = "Welcome123!"
	for _, tc := range []struct {
		next string
		ok   bool
	}{
		{"short-pass", false},
		{initial, false},
		{"current-password-1", false}, // same as current
		{strings.Repeat("a", 73), false},
		{"a much better passphrase", true},
	} {
		got := adminPasswordProblem("current-password-1", tc.next, initial)
		if (got == "") != tc.ok {
			t.Errorf("%q: problem = %q", tc.next, got)
		}
	}
}
