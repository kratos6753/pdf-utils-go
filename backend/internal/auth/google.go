package auth

import (
	"context"
	"errors"
	"slices"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
	"google.golang.org/api/idtoken"
)

// GoogleIdentity is the verified subset of a Google ID token.
type GoogleIdentity struct {
	Sub           string
	Email         string
	EmailVerified bool
	Name          string
	Picture       string
}

type Google struct {
	oauth     *oauth2.Config
	audiences []string // web client id + mobile client ids
}

// NewGoogle returns nil when Google sign-in is not configured.
func NewGoogle(clientID, clientSecret, redirectURL string, mobileClientIDs []string) *Google {
	if clientID == "" {
		return nil
	}
	return &Google{
		oauth: &oauth2.Config{
			ClientID:     clientID,
			ClientSecret: clientSecret,
			RedirectURL:  redirectURL,
			Endpoint:     google.Endpoint,
			Scopes:       []string{"openid", "email", "profile"},
		},
		audiences: append([]string{clientID}, mobileClientIDs...),
	}
}

// AuthURL starts the web code flow. verifier is a PKCE code verifier.
func (g *Google) AuthURL(state, verifier string) string {
	return g.oauth.AuthCodeURL(state, oauth2.S256ChallengeOption(verifier), oauth2.SetAuthURLParam("prompt", "select_account"))
}

// Exchange completes the web code flow and verifies the returned ID token.
func (g *Google) Exchange(ctx context.Context, code, verifier string) (*GoogleIdentity, error) {
	tok, err := g.oauth.Exchange(ctx, code, oauth2.VerifierOption(verifier))
	if err != nil {
		return nil, err
	}
	raw, _ := tok.Extra("id_token").(string)
	if raw == "" {
		return nil, errors.New("google: no id_token in response")
	}
	return g.VerifyIDToken(ctx, raw)
}

// VerifyIDToken validates an ID token from any of our client ids (web, iOS, Android).
func (g *Google) VerifyIDToken(ctx context.Context, raw string) (*GoogleIdentity, error) {
	p, err := idtoken.Validate(ctx, raw, "") // audience checked below against all our client ids
	if err != nil {
		return nil, err
	}
	if !slices.Contains(g.audiences, p.Audience) {
		return nil, errors.New("google: token issued for another app")
	}
	if p.Issuer != "accounts.google.com" && p.Issuer != "https://accounts.google.com" {
		return nil, errors.New("google: bad issuer")
	}
	str := func(k string) string { s, _ := p.Claims[k].(string); return s }
	verified, _ := p.Claims["email_verified"].(bool)
	return &GoogleIdentity{Sub: p.Subject, Email: str("email"), EmailVerified: verified, Name: str("name"), Picture: str("picture")}, nil
}

func NewPKCEVerifier() string { return oauth2.GenerateVerifier() }
