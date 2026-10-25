package pdf

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

// classify turns low-level library errors into user-facing ones where the
// cause is clearly the input file.
func classify(err error) error {
	if _, ok := AsUserError(err); ok {
		return err
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return userErr("timeout", "processing took too long; try a smaller file")
	}
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "password"), strings.Contains(msg, "encrypted"):
		return userErr("encrypted_pdf", "this PDF is password protected; unlock it first")
	case strings.Contains(msg, "no pdf"), strings.Contains(msg, "header"), strings.Contains(msg, "xref"),
		strings.Contains(msg, "corrupt"), strings.Contains(msg, "eof"):
		return userErr("invalid_pdf", "the file is not a valid PDF or is damaged")
	}
	return err
}

var pageSelRe = regexp.MustCompile(`^\d+(-\d+)?$`)

// parsePages validates a selection like "1-3, 5,8-10" and returns pdfcpu
// page selection terms. An empty string means all pages (nil).
func parsePages(s string) ([]string, error) {
	s = strings.ReplaceAll(s, " ", "")
	if s == "" {
		return nil, nil
	}
	parts := strings.Split(s, ",")
	for _, p := range parts {
		if !pageSelRe.MatchString(p) {
			return nil, userErr("invalid_options", "invalid page selection %q (use e.g. 1-3,5)", p)
		}
	}
	return parts, nil
}

// stem returns the filename without its extension, sanitized for use in an output name.
func stem(name string) string {
	base := strings.TrimSuffix(filepath.Base(name), filepath.Ext(name))
	base = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`/\:*?"<>|`, r) || r < 32 {
			return '_'
		}
		return r
	}, base)
	if base == "" || base == "." {
		return "document"
	}
	return base
}

func pdfOut(outDir, name string) *Output {
	return &Output{Path: filepath.Join(outDir, name), Name: name, Mime: "application/pdf"}
}

// bundle returns the single file as-is, or zips several files together.
func bundle(files []string, outDir, zipName, mime string) (*Output, error) {
	switch len(files) {
	case 0:
		return nil, errors.New("tool produced no output")
	case 1:
		return &Output{Path: files[0], Name: filepath.Base(files[0]), Mime: mime}, nil
	}
	zp := filepath.Join(outDir, zipName)
	f, err := os.Create(zp)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	zw := zip.NewWriter(f)
	for _, p := range files {
		w, err := zw.Create(filepath.Base(p))
		if err != nil {
			return nil, err
		}
		src, err := os.Open(p)
		if err != nil {
			return nil, err
		}
		_, err = io.Copy(w, src)
		src.Close()
		if err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return &Output{Path: zp, Name: zipName, Mime: "application/zip"}, nil
}

// listDir returns the files in dir sorted by name.
func listDir(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var out []string
	for _, e := range entries {
		if !e.IsDir() {
			out = append(out, filepath.Join(dir, e.Name()))
		}
	}
	return out, nil
}

// run executes an external binary, returning stderr in the error on failure.
func run(ctx context.Context, bin string, args ...string) error {
	return runStdin(ctx, nil, bin, args...)
}

func runStdin(ctx context.Context, stdin io.Reader, bin string, args ...string) error {
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Stdin = stdin
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return fmt.Errorf("%s: %w: %s", bin, err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func fileSize(p string) int64 {
	fi, err := os.Stat(p)
	if err != nil {
		return 0
	}
	return fi.Size()
}

// copyFile streams src to dst.
func copyFile(src, dst string) error {
	if src == dst {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
