package api

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"pdfutils/internal/pdf"
	"pdfutils/internal/storage"
	"pdfutils/internal/store"
)

type fileJSON struct {
	ID        uuid.UUID  `json:"id"`
	Name      string     `json:"name"`
	Size      int64      `json:"size"`
	Mime      string     `json:"mime"`
	Kind      string     `json:"kind"`
	JobID     *uuid.UUID `json:"job_id,omitempty"`
	CreatedAt time.Time  `json:"created_at"`
	ExpiresAt *time.Time `json:"expires_at"`
}

func toFileJSON(f *store.File) *fileJSON {
	return &fileJSON{ID: f.ID, Name: f.Name, Size: f.Size, Mime: f.Mime, Kind: f.Kind, JobID: f.JobID, CreatedAt: f.CreatedAt, ExpiresAt: f.ExpiresAt}
}

// sniff identifies supported file types from their leading bytes; the
// client-supplied content type is never trusted.
func sniff(head []byte) string {
	switch {
	case bytes.Contains(head[:min(len(head), 1024)], []byte("%PDF-")):
		return "application/pdf"
	case bytes.HasPrefix(head, []byte{0xFF, 0xD8, 0xFF}):
		return "image/jpeg"
	case bytes.HasPrefix(head, []byte("\x89PNG\r\n\x1a\n")):
		return "image/png"
	case len(head) >= 12 && bytes.Equal(head[:4], []byte("RIFF")) && bytes.Equal(head[8:12], []byte("WEBP")):
		return "image/webp"
	case bytes.HasPrefix(head, []byte("II*\x00")), bytes.HasPrefix(head, []byte("MM\x00*")):
		return "image/tiff"
	}
	return ""
}

func cleanFilename(name, mime string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 || strings.ContainsRune(`/\:*?"<>|`, r) {
			return '_'
		}
		return r
	}, name)
	if len(name) > 200 {
		name = name[:200]
	}
	name = strings.ToValidUTF8(name, "")
	if name == "" || name == "." {
		name = "file"
	}
	if strings.ToLower(filepath.Ext(name)) == "" {
		name += pdf.ExtForMime(mime)
	}
	return name
}

// upload accepts multipart/form-data with one or more "file" parts.
func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := identityFrom(ctx)
	limits, err := s.svc.PlanLimits(ctx, id.plan)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	if s.limited(w, r, "upload:"+id.owner.Subject(), limits.TasksPerHour*limits.MaxFilesPerTask, time.Hour) {
		return
	}
	maxFile := int64(limits.MaxFileMB) << 20
	r.Body = http.MaxBytesReader(w, r.Body, maxFile*int64(limits.MaxFilesPerTask)+(1<<20))
	mr, err := r.MultipartReader()
	if err != nil {
		writeErr(w, 400, "bad_request", "expected multipart/form-data with file fields")
		return
	}

	var quota, used int64
	if id.owner.IsUser() {
		quota = int64(limits.StorageMB) << 20
		if used, err = s.svc.Store.UserStorageBytes(ctx, *id.owner.UserID); err != nil {
			internalErr(w, r, err)
			return
		}
	}

	tmpDir, err := os.MkdirTemp("", "upload-")
	if err != nil {
		internalErr(w, r, err)
		return
	}
	defer os.RemoveAll(tmpDir)

	var saved []*fileJSON
	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			writeErr(w, 400, "bad_request", "could not read upload: "+err.Error())
			return
		}
		if part.FormName() != "file" || part.FileName() == "" {
			part.Close()
			continue
		}
		if len(saved) >= limits.MaxFilesPerTask {
			writeErrDetails(w, 400, "too_many_files", fmt.Sprintf("upload at most %d files at once", limits.MaxFilesPerTask),
				map[string]int{"max_files": limits.MaxFilesPerTask})
			return
		}

		tmp := filepath.Join(tmpDir, strconv.Itoa(len(saved)))
		fh, err := os.Create(tmp)
		if err != nil {
			internalErr(w, r, err)
			return
		}
		n, err := io.Copy(fh, io.LimitReader(part, maxFile+1))
		fh.Close()
		var mbe *http.MaxBytesError
		switch {
		case errors.As(err, &mbe) || n > maxFile:
			writeErrDetails(w, 413, "file_too_large", fmt.Sprintf("%s is larger than %d MB", part.FileName(), limits.MaxFileMB),
				map[string]any{"max_file_mb": limits.MaxFileMB, "can_upgrade": true})
			return
		case err != nil:
			writeErr(w, 400, "bad_request", "upload interrupted")
			return
		case n == 0:
			writeErr(w, 400, "empty_file", part.FileName()+" is empty")
			return
		}

		head := make([]byte, 1024)
		if f, err := os.Open(tmp); err == nil {
			k, _ := io.ReadFull(f, head)
			head = head[:k]
			f.Close()
		}
		mime := sniff(head)
		if mime == "" {
			writeErr(w, 415, "unsupported_file", part.FileName()+" is not a PDF or a supported image (JPG, PNG, WEBP, TIFF)")
			return
		}
		if quota > 0 && n > quota {
			writeErr(w, 413, "quota_exceeded", "file is larger than your storage allowance")
			return
		}
		used += n

		f, err := s.svc.SaveFile(ctx, id.owner, "input", cleanFilename(part.FileName(), mime), mime, tmp, nil)
		if err != nil {
			internalErr(w, r, err)
			return
		}
		saved = append(saved, toFileJSON(f))
	}
	if len(saved) == 0 {
		writeErr(w, 400, "bad_request", "no files in upload (use the form field name \"file\")")
		return
	}
	if id.owner.IsUser() && used > quota {
		if err := s.svc.EnforceQuota(ctx, *id.owner.UserID); err != nil {
			internalErr(w, r, err)
			return
		}
	}
	writeJSON(w, 201, map[string]any{"files": saved})
}

func (s *Server) ownedFile(w http.ResponseWriter, r *http.Request) (*store.File, bool) {
	fid, ok := parseID(w, chi.URLParam(r, "id"))
	if !ok {
		return nil, false
	}
	f, err := s.svc.Store.FileForOwner(r.Context(), fid, identityFrom(r.Context()).owner)
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 404, "not_found", "file not found or expired")
		return nil, false
	} else if err != nil {
		internalErr(w, r, err)
		return nil, false
	}
	return f, true
}

func (s *Server) getFile(w http.ResponseWriter, r *http.Request) {
	if f, ok := s.ownedFile(w, r); ok {
		writeJSON(w, 200, toFileJSON(f))
	}
}

// fileLink returns a short-lived URL that downloads the file without any
// auth headers, so browsers and mobile download managers can use it directly.
func (s *Server) fileLink(w http.ResponseWriter, r *http.Request) {
	f, ok := s.ownedFile(w, r)
	if !ok {
		return
	}
	const ttl = 15 * time.Minute
	exp := time.Now().Add(ttl)
	var url string
	if s.svc.Storage.CanPresign() {
		var err error
		if url, err = s.svc.Storage.PresignGet(r.Context(), f.S3Key, f.Name, ttl); err != nil {
			internalErr(w, r, err)
			return
		}
	} else {
		url = s.cfg.PublicURL + "/v1/dl/" + s.tokens.SignLink(f.ID, exp)
	}
	writeJSON(w, 200, map[string]any{"url": url, "expires_at": exp, "name": f.Name, "size": f.Size})
}

func (s *Server) downloadByLink(w http.ResponseWriter, r *http.Request) {
	fid, err := s.tokens.VerifyLink(chi.URLParam(r, "token"))
	if err != nil {
		writeErr(w, 403, "link_expired", "this download link is invalid or has expired")
		return
	}
	files, err := s.svc.Store.FilesByIDs(r.Context(), []uuid.UUID{fid})
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, 404, "not_found", "file not found or expired")
		return
	} else if err != nil {
		internalErr(w, r, err)
		return
	}
	f := files[0]
	body, err := s.svc.Storage.Get(r.Context(), f.S3Key)
	if err != nil {
		internalErr(w, r, err)
		return
	}
	defer body.Close()
	w.Header().Set("Content-Type", f.Mime)
	w.Header().Set("Content-Length", strconv.FormatInt(f.Size, 10))
	w.Header().Set("Content-Disposition", storage.ContentDisposition(f.Name))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = io.Copy(w, body)
}

func (s *Server) deleteFile(w http.ResponseWriter, r *http.Request) {
	f, ok := s.ownedFile(w, r)
	if !ok {
		return
	}
	if _, err := s.svc.DeleteFiles(r.Context(), []*store.File{f}); err != nil {
		internalErr(w, r, err)
		return
	}
	w.WriteHeader(204)
}
