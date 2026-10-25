package pdf

import (
	"archive/zip"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu"
)

// writeImage writes a noisy image (so it compresses poorly) and returns its path.
func writeImage(t *testing.T, dir, name string, w, h int) string {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	r := rand.New(rand.NewSource(int64(w*h + len(name))))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x), uint8(y), uint8(r.Intn(256)), 255})
		}
	}
	p := filepath.Join(dir, name)
	f, err := os.Create(p)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if strings.HasSuffix(name, ".png") {
		err = png.Encode(f, img)
	} else {
		err = jpeg.Encode(f, img, &jpeg.Options{Quality: 100})
	}
	if err != nil {
		t.Fatal(err)
	}
	return p
}

// makePDF creates a PDF with n pages (one large image per page).
func makePDF(t *testing.T, n int) Input {
	t.Helper()
	dir := t.TempDir()
	var imgs []string
	for i := 0; i < n; i++ {
		imgs = append(imgs, writeImage(t, dir, "p"+string(rune('a'+i))+".jpg", 1200, 1600))
	}
	p := filepath.Join(dir, "doc.pdf")
	if err := api.ImportImagesFile(context.Background(), imgs, p, pdfcpu.DefaultImportConfig(), conf()); err != nil {
		t.Fatal(err)
	}
	return Input{Path: p, Name: "My Report.pdf"}
}

func runTool(t *testing.T, name string, in []Input, opts string) *Output {
	t.Helper()
	tl, ok := Lookup(name)
	if !ok {
		t.Fatalf("tool %s not registered", name)
	}
	out, err := tl.Run(context.Background(), in, json.RawMessage(opts), t.TempDir())
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	if fileSize(out.Path) == 0 {
		t.Fatalf("%s: empty output", name)
	}
	return out
}

func pages(t *testing.T, p string) int {
	t.Helper()
	n, err := api.PageCountFile(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func zipEntries(t *testing.T, p string) []string {
	t.Helper()
	zr, err := zip.OpenReader(p)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	return names
}

func pdfText(t *testing.T, p string) string {
	t.Helper()
	out, err := exec.Command("gs", "-q", "-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=txtwrite", "-sOutputFile=-", p).Output()
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

func TestMerge(t *testing.T) {
	out := runTool(t, "merge", []Input{makePDF(t, 2), makePDF(t, 3)}, `{}`)
	if got := pages(t, out.Path); got != 5 {
		t.Fatalf("merged pages = %d, want 5", got)
	}
}

func TestSplit(t *testing.T) {
	doc := makePDF(t, 5)
	out := runTool(t, "split", []Input{doc}, `{"mode":"every","every":2}`)
	if n := len(zipEntries(t, out.Path)); n != 3 {
		t.Fatalf("split every 2 => %d files, want 3", n)
	}
	out = runTool(t, "split", []Input{doc}, `{"mode":"ranges","ranges":["1-2","3-5"]}`)
	if n := len(zipEntries(t, out.Path)); n != 2 {
		t.Fatalf("ranges => %d files, want 2", n)
	}
	out = runTool(t, "split", []Input{doc}, `{"mode":"extract","pages":"1,4-5","merge":true}`)
	if out.Mime != "application/pdf" || pages(t, out.Path) != 3 {
		t.Fatalf("extract merged => %s with %d pages", out.Mime, pages(t, out.Path))
	}
}

func TestCompress(t *testing.T) {
	doc := makePDF(t, 2)
	out := runTool(t, "compress", []Input{doc}, `{"level":"extreme"}`)
	if fileSize(out.Path) >= fileSize(doc.Path) {
		t.Fatalf("compressed %d >= original %d", fileSize(out.Path), fileSize(doc.Path))
	}
	if out.Name != "My Report_compressed.pdf" {
		t.Fatalf("name = %q", out.Name)
	}
}

func TestImageToPDF(t *testing.T) {
	dir := t.TempDir()
	in := []Input{
		{Path: writeImage(t, dir, "a.jpg", 300, 200), Name: "a.jpg"},
		{Path: writeImage(t, dir, "b.png", 200, 300), Name: "b.png"},
	}
	for _, opts := range []string{`{}`, `{"page_size":"A4","orientation":"landscape","margin":"small"}`} {
		out := runTool(t, "image_to_pdf", in, opts)
		if got := pages(t, out.Path); got != 2 {
			t.Fatalf("%s: pages = %d, want 2", opts, got)
		}
	}
}

func TestPDFToImage(t *testing.T) {
	doc := makePDF(t, 3)
	out := runTool(t, "pdf_to_image", []Input{doc}, `{"dpi":72}`)
	if n := len(zipEntries(t, out.Path)); n != 3 {
		t.Fatalf("images = %d, want 3", n)
	}
	out = runTool(t, "pdf_to_image", []Input{doc}, `{"format":"png","pages":"2"}`)
	if out.Mime != "image/png" {
		t.Fatalf("single page mime = %s", out.Mime)
	}
}

func TestProtectUnlock(t *testing.T) {
	doc := makePDF(t, 1)
	locked := runTool(t, "protect", []Input{doc}, `{"password":"s3cret"}`)
	if err := exec.Command("qpdf", "--requires-password", locked.Path).Run(); err != nil {
		t.Fatalf("protected file does not require a password: %v", err)
	}

	tl, _ := Lookup("unlock")
	_, err := tl.Run(context.Background(), []Input{{Path: locked.Path, Name: locked.Name}}, json.RawMessage(`{"password":"nope"}`), t.TempDir())
	if ue, ok := AsUserError(err); !ok || ue.Code != "wrong_password" {
		t.Fatalf("wrong password err = %v", err)
	}
	_, err = tl.Run(context.Background(), []Input{doc}, json.RawMessage(`{"password":"x"}`), t.TempDir())
	if ue, ok := AsUserError(err); !ok || ue.Code != "not_encrypted" {
		t.Fatalf("not encrypted err = %v", err)
	}

	unlocked := runTool(t, "unlock", []Input{{Path: locked.Path, Name: locked.Name}}, `{"password":"s3cret"}`)
	if err := exec.Command("qpdf", "--is-encrypted", unlocked.Path).Run(); err == nil {
		t.Fatal("unlocked file is still encrypted")
	}

	// Other tools report encrypted input clearly.
	tl, _ = Lookup("rotate")
	_, err = tl.Run(context.Background(), []Input{{Path: locked.Path, Name: locked.Name}}, json.RawMessage(`{"angle":90}`), t.TempDir())
	if ue, ok := AsUserError(err); !ok || ue.Code != "encrypted_pdf" {
		t.Fatalf("rotate on locked err = %v", err)
	}
}

func TestOrganizeRotateRemove(t *testing.T) {
	doc := makePDF(t, 4)
	if got := pages(t, runTool(t, "organize", []Input{doc}, `{"order":[4,3,1,1]}`).Path); got != 4 {
		t.Fatalf("organize pages = %d", got)
	}
	if got := pages(t, runTool(t, "remove_pages", []Input{doc}, `{"pages":"2-3"}`).Path); got != 2 {
		t.Fatalf("remove pages => %d", got)
	}
	runTool(t, "rotate", []Input{doc}, `{"angle":90,"pages":"1"}`)
}

func TestStamps(t *testing.T) {
	doc := makePDF(t, 3)
	wm := runTool(t, "watermark", []Input{doc}, `{"text":"CONFIDENTIAL 100%"}`)
	if txt := pdfText(t, wm.Path); !strings.Contains(txt, "CONFIDENTIAL 100%") {
		t.Fatalf("watermark text missing: %q", txt)
	}
	pn := runTool(t, "page_numbers", []Input{doc}, `{"format":"page_n_of_total"}`)
	if txt := pdfText(t, pn.Path); !strings.Contains(txt, "Page 2 of 3") {
		t.Fatalf("page numbers missing: %q", txt)
	}
}

func TestValidation(t *testing.T) {
	cases := map[string]string{
		"compress":     `{"level":"max"}`,
		"split":        `{"mode":"ranges","ranges":["1-x"]}`,
		"rotate":       `{"angle":45}`,
		"protect":      `{}`,
		"watermark":    `{"text":"x","color":"red"}`,
		"merge":        `{"unknown":1}`,
		"pdf_to_image": `{"dpi":1000}`,
	}
	for name, opts := range cases {
		tl, _ := Lookup(name)
		if _, ok := AsUserError(tl.Validate(json.RawMessage(opts))); !ok {
			t.Errorf("%s %s: expected validation error", name, opts)
		}
	}
}
