package pdf

import (
	"context"
	"path/filepath"

	"github.com/pdfcpu/pdfcpu/pkg/api"
)

func init() {
	register(&tool[compressOpts]{name: "compress", accepts: KindPDF, min: 1, max: 1, check: checkCompress, run: compress})
}

type compressOpts struct {
	// Level is "low" (best quality), "recommended" or "extreme" (smallest).
	Level string `json:"level"`
}

var gsPresets = map[string][]string{
	"low":         {"-dPDFSETTINGS=/printer"},
	"recommended": {"-dPDFSETTINGS=/ebook"},
	"extreme": {"-dPDFSETTINGS=/screen",
		"-dColorImageResolution=72", "-dGrayImageResolution=72", "-dMonoImageResolution=150"},
}

func checkCompress(o *compressOpts) error {
	if o.Level == "" {
		o.Level = "recommended"
	}
	if _, ok := gsPresets[o.Level]; !ok {
		return userErr("invalid_options", "level must be low, recommended or extreme")
	}
	return nil
}

func compress(ctx context.Context, in []Input, o *compressOpts, outDir string) (*Output, error) {
	src := in[0].Path
	out := pdfOut(outDir, stem(in[0].Name)+"_compressed.pdf")

	gsOut := filepath.Join(outDir, "gs.pdf")
	args := []string{"-sDEVICE=pdfwrite", "-dCompatibilityLevel=1.5", "-dSAFER", "-dNOPAUSE", "-dQUIET", "-dBATCH",
		"-dDetectDuplicateImages=true", "-dCompressFonts=true", "-dSubsetFonts=true"}
	args = append(args, gsPresets[o.Level]...)
	args = append(args, "-sOutputFile="+gsOut, src)
	if err := run(ctx, "gs", args...); err != nil {
		return nil, err
	}

	// Ghostscript can grow already-optimized files; fall back to a lossless
	// structural optimization when it does.
	best := gsOut
	if fileSize(gsOut) >= fileSize(src) {
		optOut := filepath.Join(outDir, "opt.pdf")
		if err := api.OptimizeFile(ctx, src, optOut, conf(), nil); err != nil {
			return nil, err
		}
		best = optOut
		if fileSize(optOut) >= fileSize(src) {
			best = src
		}
	}
	return out, copyFile(best, out.Path)
}
