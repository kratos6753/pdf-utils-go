package pdf

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/types"
)

func init() {
	register(&tool[imgToPDFOpts]{name: "image_to_pdf", accepts: KindImage, min: 1, max: 200, check: checkImgToPDF, run: imgToPDF})
	register(&tool[pdfToImgOpts]{name: "pdf_to_image", accepts: KindPDF, min: 1, max: 1, check: checkPDFToImg, run: pdfToImg})
}

type imgToPDFOpts struct {
	PageSize    string `json:"page_size"`   // fit | A4 | Letter
	Orientation string `json:"orientation"` // portrait | landscape
	Margin      string `json:"margin"`      // none | small | big
}

var marginScale = map[string]float64{"none": 1, "small": 0.95, "big": 0.85}

func checkImgToPDF(o *imgToPDFOpts) error {
	if o.PageSize == "" {
		o.PageSize = "fit"
	}
	if o.Orientation == "" {
		o.Orientation = "portrait"
	}
	if o.Margin == "" {
		o.Margin = "none"
	}
	switch o.PageSize {
	case "fit", "A4", "Letter":
	default:
		return userErr("invalid_options", "page_size must be fit, A4 or Letter")
	}
	if o.Orientation != "portrait" && o.Orientation != "landscape" {
		return userErr("invalid_options", "orientation must be portrait or landscape")
	}
	if _, ok := marginScale[o.Margin]; !ok {
		return userErr("invalid_options", "margin must be none, small or big")
	}
	return nil
}

func imgToPDF(ctx context.Context, in []Input, o *imgToPDFOpts, outDir string) (*Output, error) {
	imp := pdfcpu.DefaultImportConfig() // pos:full => page matches the image
	if o.PageSize != "fit" {
		suffix := "P"
		if o.Orientation == "landscape" {
			suffix = "L"
		}
		var err error
		imp, err = pdfcpu.ParseImportDetails(
			fmt.Sprintf("f:%s%s, pos:c, sc:%.2f", o.PageSize, suffix, marginScale[o.Margin]), types.POINTS)
		if err != nil {
			return nil, err
		}
	}
	paths := make([]string, len(in))
	for i, f := range in {
		paths[i] = f.Path
	}
	name := "images.pdf"
	if len(in) == 1 {
		name = stem(in[0].Name) + ".pdf"
	}
	out := pdfOut(outDir, name)
	if err := api.ImportImagesFile(ctx, paths, out.Path, imp, conf()); err != nil {
		return nil, userErr("invalid_image", "could not read one of the images (supported: JPG, PNG, TIFF, WEBP)")
	}
	return out, nil
}

type pdfToImgOpts struct {
	Format string `json:"format"` // jpg | png
	DPI    int    `json:"dpi"`    // 72..300
	Pages  string `json:"pages,omitempty"`
}

func checkPDFToImg(o *pdfToImgOpts) error {
	if o.Format == "" {
		o.Format = "jpg"
	}
	if o.DPI == 0 {
		o.DPI = 150
	}
	if o.Format != "jpg" && o.Format != "png" {
		return userErr("invalid_options", "format must be jpg or png")
	}
	if o.DPI < 72 || o.DPI > 300 {
		return userErr("invalid_options", "dpi must be between 72 and 300")
	}
	_, err := parsePages(o.Pages)
	return err
}

func pdfToImg(ctx context.Context, in []Input, o *pdfToImgOpts, outDir string) (*Output, error) {
	src := in[0].Path
	name := stem(in[0].Name)
	sel, _ := parsePages(o.Pages)
	if sel != nil {
		// Rasterize only the selected pages by collecting them first.
		sub := filepath.Join(outDir, "selected.pdf")
		if err := api.CollectFile(ctx, src, sub, sel, conf()); err != nil {
			return nil, err
		}
		src = sub
	}
	imgDir := filepath.Join(outDir, "pages")
	if err := os.MkdirAll(imgDir, 0o755); err != nil {
		return nil, err
	}
	device, mime := "jpeg", "image/jpeg"
	if o.Format == "png" {
		device, mime = "png16m", "image/png"
	}
	err := run(ctx, "gs", "-sDEVICE="+device, "-dSAFER", "-dNOPAUSE", "-dQUIET", "-dBATCH",
		"-dJPEGQ=90", "-dTextAlphaBits=4", "-dGraphicsAlphaBits=4",
		fmt.Sprintf("-r%d", o.DPI),
		"-sOutputFile="+filepath.Join(imgDir, name+"_%03d."+o.Format), src)
	if err != nil {
		return nil, err
	}
	files, err := listDir(imgDir)
	if err != nil {
		return nil, err
	}
	return bundle(files, outDir, name+"_images.zip", mime)
}
