package pdf

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/pdfcpu/pdfcpu/pkg/api"
)

func init() {
	register(&tool[watermarkOpts]{name: "watermark", accepts: KindPDF, min: 1, max: 1, check: checkWatermark, run: watermark})
	register(&tool[pageNumbersOpts]{name: "page_numbers", accepts: KindPDF, min: 1, max: 1, check: checkPageNumbers, run: pageNumbers})
}

var (
	colorRe  = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)
	anchors  = map[string]bool{"tl": true, "tc": true, "tr": true, "l": true, "c": true, "r": true, "bl": true, "bc": true, "br": true}
	stampEsc = strings.NewReplacer("%", "%%")
)

type watermarkOpts struct {
	Text     string  `json:"text"`
	FontSize int     `json:"font_size"`
	Opacity  float64 `json:"opacity"`
	Rotation int     `json:"rotation"`
	Color    string  `json:"color"`
	Position string  `json:"position"`
	Pages    string  `json:"pages,omitempty"`
}

func checkWatermark(o *watermarkOpts) error {
	o.Text = strings.TrimSpace(o.Text)
	if o.Text == "" || len(o.Text) > 200 {
		return userErr("invalid_options", "watermark text must be 1 to 200 characters")
	}
	if o.FontSize == 0 {
		o.FontSize = 48
	}
	if o.Opacity == 0 {
		o.Opacity = 0.3
	}
	if o.Color == "" {
		o.Color = "#808080"
	}
	if o.Position == "" {
		o.Position = "c"
	}
	if o.FontSize < 6 || o.FontSize > 200 {
		return userErr("invalid_options", "font_size must be between 6 and 200")
	}
	if o.Opacity < 0 || o.Opacity > 1 {
		return userErr("invalid_options", "opacity must be between 0 and 1")
	}
	if o.Rotation < -180 || o.Rotation > 180 {
		return userErr("invalid_options", "rotation must be between -180 and 180")
	}
	if !colorRe.MatchString(o.Color) {
		return userErr("invalid_options", "color must look like #RRGGBB")
	}
	if !anchors[o.Position] {
		return userErr("invalid_options", "invalid position")
	}
	_, err := parsePages(o.Pages)
	return err
}

func watermark(ctx context.Context, in []Input, o *watermarkOpts, outDir string) (*Output, error) {
	sel, _ := parsePages(o.Pages)
	desc := fmt.Sprintf("font:Helvetica, points:%d, scale:1 abs, rot:%d, op:%.2f, fillc:%s, pos:%s",
		o.FontSize, o.Rotation, o.Opacity, o.Color, o.Position)
	out := pdfOut(outDir, stem(in[0].Name)+"_watermarked.pdf")
	return out, api.AddTextWatermarksFile(ctx, in[0].Path, out.Path, sel, true, stampEsc.Replace(o.Text), desc, conf())
}

type pageNumbersOpts struct {
	Position string `json:"position"` // bl | bc | br | tl | tc | tr
	Format   string `json:"format"`   // "n" => "3", "n_of_total" => "3 of 10", "page_n" => "Page 3"
	FontSize int    `json:"font_size"`
	Pages    string `json:"pages,omitempty"`
}

var pageNumberFormats = map[string]string{"n": "%p", "n_of_total": "%p of %P", "page_n": "Page %p", "page_n_of_total": "Page %p of %P"}

func checkPageNumbers(o *pageNumbersOpts) error {
	if o.Position == "" {
		o.Position = "bc"
	}
	if o.Format == "" {
		o.Format = "n"
	}
	if o.FontSize == 0 {
		o.FontSize = 11
	}
	switch o.Position {
	case "bl", "bc", "br", "tl", "tc", "tr":
	default:
		return userErr("invalid_options", "position must be one of bl, bc, br, tl, tc, tr")
	}
	if _, ok := pageNumberFormats[o.Format]; !ok {
		return userErr("invalid_options", "unknown format")
	}
	if o.FontSize < 6 || o.FontSize > 48 {
		return userErr("invalid_options", "font_size must be between 6 and 48")
	}
	_, err := parsePages(o.Pages)
	return err
}

func pageNumbers(ctx context.Context, in []Input, o *pageNumbersOpts, outDir string) (*Output, error) {
	sel, _ := parsePages(o.Pages)
	dy := 20
	if o.Position[0] == 't' {
		dy = -20
	}
	dx := map[byte]int{'l': 30, 'c': 0, 'r': -30}[o.Position[1]]
	desc := fmt.Sprintf("font:Helvetica, points:%d, scale:1 abs, rot:0, op:1, fillc:#000000, pos:%s, off:%d %d",
		o.FontSize, o.Position, dx, dy)
	out := pdfOut(outDir, stem(in[0].Name)+"_numbered.pdf")
	return out, api.AddTextWatermarksFile(ctx, in[0].Path, out.Path, sel, true, pageNumberFormats[o.Format], desc, conf())
}
