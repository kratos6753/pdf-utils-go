// Package pdf implements the individual PDF tools. Each tool takes local
// input files plus typed options and produces exactly one output file
// (multi-file results are zipped).
package pdf

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
)

func init() {
	// Never read or write ~/.config/pdfcpu; behave the same on every host.
	api.DisableConfigDir()
}

// Kind is the type of input file a tool accepts.
type Kind string

const (
	KindPDF   Kind = "pdf"
	KindImage Kind = "image"
)

// Input is a file on local disk handed to a tool.
type Input struct {
	Path string
	Name string // original filename, used to derive output names
}

// Output is the single file a tool produces.
type Output struct {
	Path string
	Name string
	Mime string
}

// Tool is one PDF operation.
type Tool interface {
	Name() string
	Accepts() Kind
	FileLimits() (min, max int)
	// Validate checks raw JSON options without running anything, so the API
	// can reject bad requests before enqueueing.
	Validate(raw json.RawMessage) error
	Run(ctx context.Context, in []Input, raw json.RawMessage, outDir string) (*Output, error)
}

// UserError is a failure caused by the input (wrong password, corrupt file,
// bad options) that is safe to show to end users.
type UserError struct {
	Code    string
	Message string
}

func (e *UserError) Error() string { return e.Message }

func userErr(code, format string, args ...any) error {
	return &UserError{Code: code, Message: fmt.Sprintf(format, args...)}
}

// AsUserError returns the UserError wrapped in err, if any.
func AsUserError(err error) (*UserError, bool) {
	var ue *UserError
	ok := errors.As(err, &ue)
	return ue, ok
}

type tool[O any] struct {
	name     string
	accepts  Kind
	min, max int
	check    func(*O) error
	run      func(ctx context.Context, in []Input, o *O, outDir string) (*Output, error)
}

func (t *tool[O]) Name() string                       { return t.name }
func (t *tool[O]) Accepts() Kind                      { return t.accepts }
func (t *tool[O]) FileLimits() (int, int)             { return t.min, t.max }
func (t *tool[O]) Validate(raw json.RawMessage) error { _, err := t.decode(raw); return err }

func (t *tool[O]) decode(raw json.RawMessage) (*O, error) {
	o := new(O)
	if len(bytes.TrimSpace(raw)) > 0 && string(raw) != "null" {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.DisallowUnknownFields()
		if err := dec.Decode(o); err != nil {
			return nil, userErr("invalid_options", "invalid options: %v", err)
		}
	}
	if t.check != nil {
		if err := t.check(o); err != nil {
			return nil, err
		}
	}
	return o, nil
}

func (t *tool[O]) Run(ctx context.Context, in []Input, raw json.RawMessage, outDir string) (*Output, error) {
	if len(in) < t.min || len(in) > t.max {
		return nil, userErr("invalid_file_count", "%s needs between %d and %d files, got %d", t.name, t.min, t.max, len(in))
	}
	o, err := t.decode(raw)
	if err != nil {
		return nil, err
	}
	out, err := t.run(ctx, in, o, outDir)
	if err != nil {
		return nil, classify(err)
	}
	return out, nil
}

var registry = map[string]Tool{}

func register(t Tool) { registry[t.Name()] = t }

// Lookup returns the tool with the given name.
func Lookup(name string) (Tool, bool) {
	t, ok := registry[name]
	return t, ok
}

// Names lists all registered tools, sorted.
func Names() []string {
	names := make([]string, 0, len(registry))
	for n := range registry {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

func conf() *model.Configuration { return model.NewDefaultConfiguration() }

var mimeExt = map[string]string{"application/pdf": ".pdf", "image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/tiff": ".tif"}

// ExtForMime returns the file extension for a supported input type.
func ExtForMime(mime string) string { return mimeExt[mime] }

// AcceptsMime reports whether a tool of kind k can take a file of this type.
func (k Kind) AcceptsMime(mime string) bool {
	if k == KindPDF {
		return mime == "application/pdf"
	}
	return strings.HasPrefix(mime, "image/")
}
