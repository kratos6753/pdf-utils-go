// Package mail sends transactional email over SMTP, or logs it in development.
package mail

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/mail"
	"net/smtp"
	"strings"
)

type Mailer struct {
	addr, user, pass string
	from             string
}

func New(addr, user, pass, from string) *Mailer {
	return &Mailer{addr: addr, user: user, pass: pass, from: from}
}

func (m *Mailer) Send(ctx context.Context, to, subject, body string) error {
	if m.addr == "" {
		slog.InfoContext(ctx, "email (SMTP not configured, not sent)", "to", to, "subject", subject, "body", body)
		return nil
	}
	from, err := mail.ParseAddress(m.from)
	if err != nil {
		return fmt.Errorf("MAIL_FROM: %w", err)
	}
	if strings.ContainsAny(to+subject, "\r\n") {
		return fmt.Errorf("invalid header value")
	}
	msg := "From: " + from.String() + "\r\nTo: " + to + "\r\nSubject: " + subject +
		"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body
	var a smtp.Auth
	if m.user != "" {
		host, _, _ := net.SplitHostPort(m.addr)
		a = smtp.PlainAuth("", m.user, m.pass, host)
	}
	return smtp.SendMail(m.addr, a, from.Address, []string{to}, []byte(msg))
}
