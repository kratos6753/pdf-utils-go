package api

import (
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"pdfutils/internal/store"
)

type feedbackRequest struct {
	Name    string `json:"name"`
	Email   string `json:"email"`
	Topic   string `json:"topic"`
	Message string `json:"message"`
	Website string `json:"website"` // honeypot: hidden in the form, only bots fill it
}

var feedbackTopics = map[string]bool{"feedback": true, "bug": true, "question": true, "other": true}

// validateFeedback trims the request in place and returns an error code and message.
func validateFeedback(req *feedbackRequest) (code, msg string) {
	req.Name = strings.TrimSpace(req.Name)
	req.Email = strings.TrimSpace(req.Email)
	req.Message = strings.TrimSpace(req.Message)
	if req.Topic == "" {
		req.Topic = "feedback"
	}
	switch n := utf8.RuneCountInString(req.Message); {
	case utf8.RuneCountInString(req.Name) > 100:
		return "invalid_name", "name must be at most 100 characters"
	case !validEmail(req.Email):
		return "invalid_email", "enter a valid email address"
	case !feedbackTopics[req.Topic]:
		return "invalid_topic", "topic must be feedback, bug, question or other"
	case n < 10:
		return "message_too_short", "message must be at least 10 characters"
	case n > 5000:
		return "message_too_long", "message must be at most 5000 characters"
	}
	return "", ""
}

// createFeedback stores a Contact us message.
func (s *Server) createFeedback(w http.ResponseWriter, r *http.Request) {
	id := identityFrom(r.Context())
	if s.limited(w, r, "feedback:"+id.ip, 5, time.Hour) {
		return
	}
	var req feedbackRequest
	if !decode(w, r, &req) {
		return
	}
	if req.Website != "" {
		w.WriteHeader(201) // pretend success so bots don't adapt
		return
	}
	if code, msg := validateFeedback(&req); code != "" {
		writeErr(w, 422, code, msg)
		return
	}
	client := r.Header.Get("X-Client-Type")
	if len(client) > 20 {
		client = client[:20]
	}
	if _, err := s.svc.Store.CreateFeedback(r.Context(), &store.Feedback{
		UserID:  id.owner.UserID,
		Name:    req.Name,
		Email:   req.Email,
		Topic:   req.Topic,
		Message: req.Message,
		Client:  client,
	}); err != nil {
		internalErr(w, r, err)
		return
	}
	w.WriteHeader(201)
}
