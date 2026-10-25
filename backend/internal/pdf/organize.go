package pdf

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"github.com/pdfcpu/pdfcpu/pkg/api"
)

func init() {
	register(&tool[mergeOpts]{name: "merge", accepts: KindPDF, min: 2, max: 50, run: merge})
	register(&tool[splitOpts]{name: "split", accepts: KindPDF, min: 1, max: 1, check: checkSplit, run: split})
	register(&tool[rotateOpts]{name: "rotate", accepts: KindPDF, min: 1, max: 1, check: checkRotate, run: rotate})
	register(&tool[organizeOpts]{name: "organize", accepts: KindPDF, min: 1, max: 1, check: checkOrganize, run: organize})
	register(&tool[removePagesOpts]{name: "remove_pages", accepts: KindPDF, min: 1, max: 1, check: checkRemovePages, run: removePages})
}

type mergeOpts struct{}

func merge(ctx context.Context, in []Input, _ *mergeOpts, outDir string) (*Output, error) {
	paths := make([]string, len(in))
	for i, f := range in {
		paths[i] = f.Path
	}
	out := pdfOut(outDir, "merged.pdf")
	return out, api.MergeCreateFile(ctx, paths, out.Path, false, conf())
}

type splitOpts struct {
	// Mode is "ranges" (one PDF per range), "every" (chunks of Every pages)
	// or "extract" (selected pages; one file each, or one PDF if Merge).
	Mode   string   `json:"mode"`
	Ranges []string `json:"ranges,omitempty"`
	Every  int      `json:"every,omitempty"`
	Pages  string   `json:"pages,omitempty"`
	Merge  bool     `json:"merge,omitempty"`
}

func checkSplit(o *splitOpts) error {
	switch o.Mode {
	case "", "every":
		o.Mode = "every"
		if o.Every == 0 {
			o.Every = 1
		}
		if o.Every < 1 {
			return userErr("invalid_options", "every must be at least 1")
		}
	case "ranges":
		if len(o.Ranges) == 0 || len(o.Ranges) > 200 {
			return userErr("invalid_options", "provide between 1 and 200 ranges")
		}
		for _, r := range o.Ranges {
			if sel, err := parsePages(r); err != nil {
				return err
			} else if sel == nil {
				return userErr("invalid_options", "ranges must not be empty")
			}
		}
	case "extract":
		sel, err := parsePages(o.Pages)
		if err != nil {
			return err
		}
		if sel == nil {
			return userErr("invalid_options", "select the pages to extract")
		}
	default:
		return userErr("invalid_options", "mode must be ranges, every or extract")
	}
	return nil
}

func split(ctx context.Context, in []Input, o *splitOpts, outDir string) (*Output, error) {
	src := in[0]
	name := stem(src.Name)
	parts := filepath.Join(outDir, "parts")
	if err := os.MkdirAll(parts, 0o755); err != nil {
		return nil, err
	}
	switch o.Mode {
	case "every":
		if err := api.SplitFile(ctx, src.Path, parts, o.Every, conf()); err != nil {
			return nil, err
		}
	case "ranges":
		for i, r := range o.Ranges {
			sel, _ := parsePages(r)
			p := filepath.Join(parts, fmt.Sprintf("%s_%03d_%s.pdf", name, i+1, r))
			if err := api.CollectFile(ctx, src.Path, p, sel, conf()); err != nil {
				return nil, err
			}
		}
	case "extract":
		sel, _ := parsePages(o.Pages)
		if o.Merge {
			out := pdfOut(outDir, name+"_extracted.pdf")
			return out, api.CollectFile(ctx, src.Path, out.Path, sel, conf())
		}
		if err := api.ExtractPagesFile(ctx, src.Path, parts, sel, conf()); err != nil {
			return nil, err
		}
	}
	files, err := listDir(parts)
	if err != nil {
		return nil, err
	}
	return bundle(files, outDir, name+"_split.zip", "application/pdf")
}

type rotateOpts struct {
	Angle int    `json:"angle"`
	Pages string `json:"pages,omitempty"`
}

func checkRotate(o *rotateOpts) error {
	switch o.Angle {
	case 90, 180, 270, -90:
	default:
		return userErr("invalid_options", "angle must be 90, 180 or 270")
	}
	_, err := parsePages(o.Pages)
	return err
}

func rotate(ctx context.Context, in []Input, o *rotateOpts, outDir string) (*Output, error) {
	sel, _ := parsePages(o.Pages)
	out := pdfOut(outDir, stem(in[0].Name)+"_rotated.pdf")
	return out, api.RotateFile(ctx, in[0].Path, out.Path, o.Angle, sel, conf())
}

type organizeOpts struct {
	// Order is the new page sequence (1-based). Omitted pages are dropped;
	// repeated pages are duplicated.
	Order []int `json:"order"`
}

func checkOrganize(o *organizeOpts) error {
	if len(o.Order) == 0 || len(o.Order) > 5000 {
		return userErr("invalid_options", "order must list between 1 and 5000 pages")
	}
	for _, p := range o.Order {
		if p < 1 {
			return userErr("invalid_options", "page numbers start at 1")
		}
	}
	return nil
}

func organize(ctx context.Context, in []Input, o *organizeOpts, outDir string) (*Output, error) {
	n, err := api.PageCountFile(ctx, in[0].Path)
	if err != nil {
		return nil, err
	}
	sel := make([]string, len(o.Order))
	for i, p := range o.Order {
		if p > n {
			return nil, userErr("invalid_options", "page %d does not exist (document has %d pages)", p, n)
		}
		sel[i] = strconv.Itoa(p)
	}
	out := pdfOut(outDir, stem(in[0].Name)+"_organized.pdf")
	return out, api.CollectFile(ctx, in[0].Path, out.Path, sel, conf())
}

type removePagesOpts struct {
	Pages string `json:"pages"`
}

func checkRemovePages(o *removePagesOpts) error {
	sel, err := parsePages(o.Pages)
	if err != nil {
		return err
	}
	if sel == nil {
		return userErr("invalid_options", "select the pages to remove")
	}
	return nil
}

func removePages(ctx context.Context, in []Input, o *removePagesOpts, outDir string) (*Output, error) {
	sel, _ := parsePages(o.Pages)
	out := pdfOut(outDir, stem(in[0].Name)+"_edited.pdf")
	return out, api.RemovePagesFile(ctx, in[0].Path, out.Path, sel, conf())
}
