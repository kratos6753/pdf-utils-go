package pdf

import (
	"context"
	"errors"
	"os/exec"
	"strings"

	"github.com/pdfcpu/pdfcpu/pkg/api"
	"github.com/pdfcpu/pdfcpu/pkg/pdfcpu/model"
)

func init() {
	register(&tool[protectOpts]{name: "protect", accepts: KindPDF, min: 1, max: 1, check: checkProtect, run: protect})
	register(&tool[unlockOpts]{name: "unlock", accepts: KindPDF, min: 1, max: 1, check: checkUnlock, run: unlock})
}

type protectOpts struct {
	Password      string `json:"password"`
	OwnerPassword string `json:"owner_password,omitempty"`
}

func checkProtect(o *protectOpts) error {
	if len(o.Password) < 1 || len(o.Password) > 128 || len(o.OwnerPassword) > 128 {
		return userErr("invalid_options", "password must be between 1 and 128 characters")
	}
	return nil
}

func protect(ctx context.Context, in []Input, o *protectOpts, outDir string) (*Output, error) {
	owner := o.OwnerPassword
	if owner == "" {
		owner = o.Password
	}
	c := model.NewAESConfiguration(o.Password, owner, 256)
	c.Permissions = model.PermissionsAll
	out := pdfOut(outDir, stem(in[0].Name)+"_protected.pdf")
	return out, api.EncryptFile(ctx, in[0].Path, out.Path, c)
}

type unlockOpts struct {
	Password string `json:"password,omitempty"` // may be empty for permission-only locks
}

func checkUnlock(o *unlockOpts) error {
	if len(o.Password) > 128 {
		return userErr("invalid_options", "password is too long")
	}
	return nil
}

func unlock(ctx context.Context, in []Input, o *unlockOpts, outDir string) (*Output, error) {
	src := in[0].Path
	// qpdf --is-encrypted: exit 0 = encrypted, 2 = not encrypted.
	if err := exec.CommandContext(ctx, "qpdf", "--is-encrypted", src).Run(); err != nil {
		var ee *exec.ExitError
		if errors.As(err, &ee) && ee.ExitCode() == 2 {
			return nil, userErr("not_encrypted", "this PDF is not password protected")
		}
		return nil, err
	}
	// Pass the password on stdin so it never shows up in the process list
	// or touches the disk.
	out := pdfOut(outDir, stem(in[0].Name)+"_unlocked.pdf")
	err := runStdin(ctx, strings.NewReader(o.Password), "qpdf", "--password-file=-", "--decrypt", src, out.Path)
	var ee *exec.ExitError
	switch {
	case err == nil:
	case errors.As(err, &ee) && ee.ExitCode() == 3: // succeeded with warnings
	case strings.Contains(strings.ToLower(err.Error()), "invalid password"):
		return nil, userErr("wrong_password", "the password is incorrect")
	default:
		return nil, err
	}
	return out, nil
}
