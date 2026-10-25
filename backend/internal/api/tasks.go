package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"pdfutils/internal/pdf"
	"pdfutils/internal/ratelimit"
	"pdfutils/internal/store"
)

type jobJSON struct {
	ID         uuid.UUID   `json:"id"`
	Tool       string      `json:"tool"`
	Status     string      `json:"status"`
	Progress   int         `json:"progress"`
	Error      *apiError   `json:"error,omitempty"`
	InputIDs   []uuid.UUID `json:"input_ids"`
	Output     *fileJSON   `json:"output,omitempty"`
	CreatedAt  time.Time   `json:"created_at"`
	FinishedAt *time.Time  `json:"finished_at"`
}

func toJobJSON(j *store.Job, out *store.File) *jobJSON {
	v := &jobJSON{ID: j.ID, Tool: j.Tool, Status: j.Status, Progress: j.Progress, InputIDs: j.InputIDs,
		CreatedAt: j.CreatedAt, FinishedAt: j.FinishedAt}
	if j.ErrorCode != nil {
		v.Error = &apiError{Code: *j.ErrorCode, Message: deref(j.ErrorMessage)}
	}
	if out != nil {
		v.Output = toFileJSON(out)
	}
	return v
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

type createTaskReq struct {
	FileIDs []uuid.UUID     `json:"file_ids"`
	Options json.RawMessage `json:"options"`
}

// createTask validates a request and enqueues a job: POST /v1/tasks/{tool}.
func (s *Server) createTask(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := identityFrom(ctx)
	tool, ok := pdf.Lookup(chi.URLParam(r, "tool"))
	if !ok {
		writeErr(w, 404, "unknown_tool", "unknown tool")
		return
	}
	var req createTaskReq
	if !decode(w, r, &req) {
		return
	}
	if len(req.Options) == 0 || string(req.Options) == "null" {
		req.Options = json.RawMessage(`{}`)
	}
	limits, err := s.svc.PlanLimits(ctx, id.plan)
	if err != nil {
		internalErr(w, r, err)
		return
	}

	lo, hi := tool.FileLimits()
	hi = min(hi, limits.MaxFilesPerTask)
	if n := len(req.FileIDs); n < lo || n > hi {
		writeErrDetails(w, 400, "invalid_file_count", fmt.Sprintf("%s needs between %d and %d files", tool.Name(), lo, hi),
			map[string]int{"min_files": lo, "max_files": hi})
		return
	}
	if err := tool.Validate(req.Options); err != nil {
		if ue, ok := pdf.AsUserError(err); ok {
			writeErr(w, 422, ue.Code, ue.Message)
			return
		}
		internalErr(w, r, err)
		return
	}
	files, err := s.svc.Store.FilesForOwner(ctx, req.FileIDs, id.owner)
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 404, "file_not_found", "one or more files were not found or have expired")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	for _, f := range files {
		if !tool.Accepts().AcceptsMime(f.Mime) {
			writeErr(w, 415, "unsupported_file", fmt.Sprintf("%s cannot be used with %s", f.Name, tool.Name()))
			return
		}
	}

	if !s.allowTask(w, r, id, limits.TasksPerHour) {
		return
	}

	job, created, err := s.svc.Store.CreateJob(ctx, id.owner, tool.Name(), req.Options, req.FileIDs, r.Header.Get("Idempotency-Key"))
	if err != nil {
		internalErr(w, r, err)
		return
	}
	if created {
		if err := s.queue.EnqueueJob(ctx, job.ID); err != nil {
			_ = s.svc.Store.FailJob(ctx, job.ID, "unavailable", "processing is temporarily unavailable")
			internalErr(w, r, fmt.Errorf("enqueue: %w", err))
			return
		}
	}
	writeJSON(w, 202, toJobJSON(job, nil))
}

// allowTask applies the hourly task allowance. Anonymous clients are counted
// per device and per IP, so rotating device ids does not bypass the limit.
func (s *Server) allowTask(w http.ResponseWriter, r *http.Request, id *identity, perHour int) bool {
	ctx := r.Context()
	checks := []struct {
		key   string
		limit int
	}{{"tasks:" + id.owner.Subject(), perHour}}
	if !id.owner.IsUser() {
		checks = append(checks, struct {
			key   string
			limit int
		}{"tasks:ip:" + id.ip, perHour * 3})
	}
	for i, c := range checks {
		res, err := s.limiter.Allow(ctx, c.key, c.limit, time.Hour)
		if err != nil {
			return true // fail open if Redis is unavailable
		}
		if i == 0 {
			setRateHeaders(w, res)
		}
		if !res.Allowed {
			w.Header().Set("Retry-After", strconv.Itoa(int(time.Until(res.Reset).Seconds())+1))
			msg := "you've reached the hourly limit for free use"
			if !id.owner.IsUser() {
				msg += "; sign up for a free account to get higher limits"
			}
			writeErrDetails(w, 429, "limit_reached", msg, map[string]any{
				"limit": res.Limit, "reset_at": res.Reset, "signed_in": id.owner.IsUser(), "can_upgrade": true,
			})
			return false
		}
	}
	return true
}

func setRateHeaders(w http.ResponseWriter, res ratelimit.Result) {
	w.Header().Set("X-RateLimit-Limit", strconv.Itoa(res.Limit))
	w.Header().Set("X-RateLimit-Remaining", strconv.Itoa(res.Remaining))
	w.Header().Set("X-RateLimit-Reset", strconv.FormatInt(res.Reset.Unix(), 10))
}

func (s *Server) getJob(w http.ResponseWriter, r *http.Request) {
	jid, ok := parseID(w, chi.URLParam(r, "id"))
	if !ok {
		return
	}
	job, err := s.svc.Store.JobForOwner(r.Context(), jid, identityFrom(r.Context()).owner)
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 404, "not_found", "job not found")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	var out *store.File
	if job.OutputID != nil {
		files, err := s.svc.Store.FilesByIDs(r.Context(), []uuid.UUID{*job.OutputID})
		if err == nil {
			out = files[0]
		} else if !errors.Is(err, store.ErrNotFound) {
			internalErr(w, r, err)
			return
		}
	}
	writeJSON(w, 200, toJobJSON(job, out))
}
