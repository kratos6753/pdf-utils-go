package api

import (
	"os"
	"regexp"
	"slices"
	"strings"
	"testing"

	"pdfutils/internal/pdf"
)

// The OpenAPI spec is the contract for the mobile apps; keep its tool list in
// sync with the registered tools.
func TestOpenAPIToolEnum(t *testing.T) {
	raw, err := os.ReadFile("../../api/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	m := regexp.MustCompile(`(?s)ToolName:\s*\n\s*type: string\s*\n\s*enum: \[([^\]]+)\]`).FindSubmatch(raw)
	if m == nil {
		t.Fatal("ToolName enum not found in openapi.yaml")
	}
	var spec []string
	for _, s := range strings.Split(string(m[1]), ",") {
		spec = append(spec, strings.TrimSpace(s))
	}
	slices.Sort(spec)
	if got := pdf.Names(); !slices.Equal(spec, got) {
		t.Fatalf("openapi ToolName enum %v != registered tools %v", spec, got)
	}
}
