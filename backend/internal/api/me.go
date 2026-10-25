package api

import (
	"encoding/base64"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"pdfutils/internal/auth"
	"pdfutils/internal/store"
)

type storageJSON struct {
	UsedBytes  int64 `json:"used_bytes"`
	LimitBytes int64 `json:"limit_bytes"`
}

// me returns the caller's account (null when anonymous), limits and usage,
// so clients can show remaining allowance without hardcoding plan numbers.
func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := identityFrom(ctx)
	out := map[string]any{"user": nil, "plan": id.plan}
	if id.owner.IsUser() {
		u, err := s.svc.Store.UserByID(ctx, *id.owner.UserID)
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, 401, "unauthorized", "account no longer exists")
			return
		} else if err != nil {
			internalErr(w, r, err)
			return
		}
		out["user"], out["plan"], id.plan = toUserJSON(u), u.Plan, u.Plan
	}
	limits, err := s.svc.PlanLimits(ctx, id.plan)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	out["limits"] = limits
	if res, err := s.limiter.Peek(ctx, "tasks:"+id.owner.Subject(), limits.TasksPerHour, time.Hour); err == nil {
		out["tasks"] = map[string]any{"remaining": res.Remaining, "limit": res.Limit, "reset_at": res.Reset}
	}
	if id.owner.IsUser() {
		used, err := s.svc.Store.UserStorageBytes(ctx, *id.owner.UserID)
		if err != nil {
			internalErr(w, r, err)
			return
		}
		out["storage"] = storageJSON{UsedBytes: used, LimitBytes: int64(limits.StorageMB) << 20}
	}
	writeJSON(w, 200, out)
}

func (s *Server) updateMe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if !decode(w, r, &req) {
		return
	}
	uid := *identityFrom(r.Context()).owner.UserID
	name := strings.TrimSpace(req.Name)
	if len(name) > 100 {
		name = name[:100]
	}
	if err := s.svc.Store.UpdateProfile(r.Context(), uid, name); err != nil {
		internalErr(w, r, err)
		return
	}
	u, err := s.svc.Store.UserByID(r.Context(), uid)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	writeJSON(w, 200, toUserJSON(u))
}

func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if !decode(w, r, &req) || !validPassword(w, req.NewPassword) {
		return
	}
	ctx := r.Context()
	u, err := s.svc.Store.UserByID(ctx, *identityFrom(ctx).owner.UserID)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	// Google-only accounts may set a first password without a current one.
	if u.PasswordHash != nil && !auth.CheckPassword(*u.PasswordHash, req.CurrentPassword) {
		writeErr(w, 403, "invalid_credentials", "current password is incorrect")
		return
	}
	hash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	if err := s.svc.Store.SetPassword(ctx, u.ID, hash); err != nil {
		internalErr(w, r, err)
		return
	}
	w.WriteHeader(204)
}

// deleteMe permanently deletes the account and all stored files.
func (s *Server) deleteMe(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := *identityFrom(ctx).owner.UserID
	for {
		files, err := s.svc.Store.ListUserFiles(ctx, uid, "", time.Time{}, uuid.Nil, 200)
		if err != nil {
			internalErr(w, r, err)
			return
		}
		if len(files) == 0 {
			break
		}
		n, err := s.svc.DeleteFiles(ctx, files)
		if err != nil {
			internalErr(w, r, err)
			return
		}
		if n < len(files) {
			internalErr(w, r, errors.New("could not delete all stored files"))
			return
		}
	}
	if err := s.svc.Store.DeleteUser(ctx, uid); err != nil {
		internalErr(w, r, err)
		return
	}
	if isWeb(r) {
		s.setCookie(w, refreshCookie, "", "/v1/auth", -1, http.SameSiteStrictMode)
	}
	w.WriteHeader(204)
}

// listMyFiles pages through the signed-in user's file history.
// Query: kind=input|output, limit (1-100), cursor (opaque).
func (s *Server) listMyFiles(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := *identityFrom(ctx).owner.UserID
	q := r.URL.Query()
	kind := q.Get("kind")
	if kind != "" && kind != "input" && kind != "output" {
		writeErr(w, 400, "bad_request", "kind must be input or output")
		return
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit < 1 || limit > 100 {
		limit = 30
	}
	var beforeT time.Time
	beforeID := uuid.Nil
	if c := q.Get("cursor"); c != "" {
		var ok bool
		if beforeT, beforeID, ok = decodeCursor(c); !ok {
			writeErr(w, 400, "bad_request", "invalid cursor")
			return
		}
	}
	files, err := s.svc.Store.ListUserFiles(ctx, uid, kind, beforeT, beforeID, limit+1)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	var next *string
	if len(files) > limit {
		files = files[:limit]
		last := files[limit-1]
		c := encodeCursor(last.CreatedAt, last.ID)
		next = &c
	}
	items := make([]*fileJSON, len(files))
	for i, f := range files {
		items[i] = toFileJSON(f)
	}
	writeJSON(w, 200, map[string]any{"files": items, "next_cursor": next})
}

func encodeCursor(t time.Time, id uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.FormatInt(t.UnixMicro(), 10) + "|" + id.String()))
}

func decodeCursor(c string) (time.Time, uuid.UUID, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return time.Time{}, uuid.Nil, false
	}
	ts, idStr, ok := strings.Cut(string(raw), "|")
	us, err1 := strconv.ParseInt(ts, 10, 64)
	id, err2 := uuid.Parse(idStr)
	if !ok || err1 != nil || err2 != nil {
		return time.Time{}, uuid.Nil, false
	}
	return time.UnixMicro(us), id, true
}
