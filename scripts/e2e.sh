#!/usr/bin/env bash
# End-to-end API check: uploads fixtures, runs every tool, downloads and
# validates each result. Requires a running API + worker, plus curl, jq, gs, qpdf.
#   API=http://localhost:8080 scripts/e2e.sh
set -euo pipefail
API=${API:-http://localhost:8080}
DEVICE="e2e-$(date +%s)-$RANDOM-device"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
H=(-H "X-Device-Id: $DEVICE")
pass=0; fail=0

ok()   { echo "  ✓ $*"; pass=$((pass+1)); }
bad()  { echo "  ✗ $*"; fail=$((fail+1)); }

# Fixtures: a 4-page PDF with text, a 1-page PDF, a JPEG.
gs -q -dNOPAUSE -dBATCH -sDEVICE=pdfwrite -o "$WORK/four.pdf" -c \
  "/Helvetica findfont 36 scalefont setfont 1 1 4 { 72 600 moveto (Page ) show 3 string cvs show showpage } for"
gs -q -dNOPAUSE -dBATCH -sDEVICE=pdfwrite -o "$WORK/one.pdf" -c \
  "/Helvetica findfont 36 scalefont setfont 72 600 moveto (Single) show showpage"
gs -q -dNOPAUSE -dBATCH -sDEVICE=jpeg -r50 -o "$WORK/photo.jpg" "$WORK/one.pdf"

upload() { # file... -> prints ids, one per line
  local args=(); for f in "$@"; do args+=(-F "file=@$f"); done
  curl -sf "${H[@]}" "${args[@]}" "$API/v1/uploads" | jq -r '.files[].id'
}

# run_task tool options file_ids... -> downloads output to $WORK/out_<tool>, echoes path
run_task() {
  local tool=$1 opts=$2; shift 2
  local ids; ids=$(printf '%s\n' "$@" | jq -R . | jq -sc .)
  local job; job=$(curl -sf "${H[@]}" -H 'Content-Type: application/json' \
    -d "{\"file_ids\":$ids,\"options\":$opts}" "$API/v1/tasks/$tool" | jq -r .id)
  local status=queued body
  for _ in $(seq 1 120); do
    body=$(curl -sf "${H[@]}" "$API/v1/jobs/$job")
    status=$(jq -r .status <<<"$body")
    [[ $status == done || $status == failed ]] && break
    sleep 0.5
  done
  if [[ $status != done ]]; then echo "job $tool: $status $(jq -c .error <<<"$body")" >&2; return 1; fi
  local out_id name url
  out_id=$(jq -r .output.id <<<"$body"); name=$(jq -r .output.name <<<"$body")
  url=$(curl -sf "${H[@]}" "$API/v1/files/$out_id/link" | jq -r .url)
  curl -sf -o "$WORK/$name" "$url"
  echo "$WORK/$name"
}

pages() { qpdf --show-npages "$1"; }
zipcount() { unzip -Z1 "$1" | wc -l | tr -d ' '; }

echo "Anonymous use"
ANON_FILE=$(upload "$WORK/one.pdf")
f=$(run_task rotate '{"angle":180}' "$ANON_FILE") && ok "anonymous task" || bad "anonymous task"
[[ $(curl -sf "${H[@]}" "$API/v1/me" | jq -r .plan) == anon ]] && ok "me → anon plan" || bad "me anon"

echo "Sign up (claims anonymous files) and use the account"
EMAIL="e2e+$DEVICE@example.com"
sess=$(curl -sf "${H[@]}" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"correct horse\",\"name\":\"E2E\"}" "$API/v1/auth/signup")
ACCESS=$(jq -r .access_token <<<"$sess"); REFRESH=$(jq -r .refresh_token <<<"$sess")
[[ $ACCESS != null && $REFRESH != null ]] && ok "signup returns tokens" || bad "signup"
H=(-H "X-Device-Id: $DEVICE" -H "Authorization: Bearer $ACCESS")
me=$(curl -sf "${H[@]}" "$API/v1/me")
[[ $(jq -r .plan <<<"$me") == free && $(jq -r .user.email <<<"$me") == "$EMAIL" ]] && ok "me → free plan" || bad "me user"
[[ $(jq -r .storage.used_bytes <<<"$me") -gt 0 ]] && ok "anonymous files claimed into account" || bad "claim"

echo "Uploading fixtures"
FOUR=$(upload "$WORK/four.pdf"); ONE=$(upload "$WORK/one.pdf"); JPG=$(upload "$WORK/photo.jpg")
[[ -n $FOUR && -n $ONE && -n $JPG ]] && ok "uploads" || bad "uploads"

echo "Tools"
f=$(run_task merge '{}' "$FOUR" "$ONE") && [[ $(pages "$f") == 5 ]] && ok "merge → 5 pages" || bad merge
f=$(run_task split '{"mode":"every","every":1}' "$FOUR") && [[ $(zipcount "$f") == 4 ]] && ok "split → 4 files" || bad split
f=$(run_task compress '{"level":"recommended"}' "$FOUR") && qpdf --check "$f" >/dev/null && ok "compress → valid pdf" || bad compress
f=$(run_task image_to_pdf '{"page_size":"A4"}' "$JPG") && [[ $(pages "$f") == 1 ]] && ok "image_to_pdf" || bad image_to_pdf
f=$(run_task pdf_to_image '{"dpi":72}' "$FOUR") && [[ $(zipcount "$f") == 4 ]] && ok "pdf_to_image → 4 jpgs" || bad pdf_to_image
f=$(run_task rotate '{"angle":90}' "$ONE") && qpdf --check "$f" >/dev/null && ok "rotate" || bad rotate
f=$(run_task organize '{"order":[4,1]}' "$FOUR") && [[ $(pages "$f") == 2 ]] && ok "organize → 2 pages" || bad organize
f=$(run_task remove_pages '{"pages":"1-2"}' "$FOUR") && [[ $(pages "$f") == 2 ]] && ok "remove_pages" || bad remove_pages
f=$(run_task watermark '{"text":"DRAFT"}' "$ONE") && gs -q -dNOPAUSE -dBATCH -sDEVICE=txtwrite -o - "$f" | grep -q DRAFT && ok "watermark" || bad watermark
f=$(run_task page_numbers '{"format":"n_of_total"}' "$FOUR") && gs -q -dNOPAUSE -dBATCH -sDEVICE=txtwrite -o - "$f" | grep -q "3 of 4" && ok "page_numbers" || bad page_numbers

echo "Protect → unlock round trip"
locked=$(run_task protect '{"password":"hunter22"}' "$ONE")
if qpdf --requires-password "$locked"; then ok "protect requires password"; else bad protect; fi
LOCKED=$(upload "$locked")
f=$(run_task unlock '{"password":"hunter22"}' "$LOCKED") && ! qpdf --is-encrypted "$f" && ok "unlock" || bad unlock
err=$(run_task unlock '{"password":"wrong"}' "$LOCKED" 2>&1 || true)
[[ $err == *wrong_password* ]] && ok "wrong password reported" || bad "wrong password: $err"

echo "Validation & isolation"
code=$(curl -s -o /dev/null -w '%{http_code}' "${H[@]}" -H 'Content-Type: application/json' -d "{\"file_ids\":[\"$ONE\"],\"options\":{\"level\":\"nope\"}}" "$API/v1/tasks/compress")
[[ $code == 422 ]] && ok "bad options → 422" || bad "bad options got $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Device-Id: someone-else-device-0001" "$API/v1/files/$ONE")
[[ $code == 404 ]] && ok "other client cannot see file" || bad "isolation got $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "${H[@]}" -F "file=@$0" "$API/v1/uploads")
[[ $code == 415 ]] && ok "non-PDF upload → 415" || bad "unsupported upload got $code"

echo "History & sessions"
hist=$(curl -sf "${H[@]}" "$API/v1/me/files?kind=output&limit=5")
[[ $(jq '.files | length' <<<"$hist") == 5 && $(jq -r .next_cursor <<<"$hist") != null ]] && ok "history paginates" || bad "history"
cur=$(jq -r .next_cursor <<<"$hist")
[[ $(curl -sf "${H[@]}" "$API/v1/me/files?kind=output&limit=5&cursor=$cur" | jq '.files | length') -gt 0 ]] && ok "history page 2" || bad "history page 2"
new=$(curl -sf -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$REFRESH\"}" "$API/v1/auth/refresh" | jq -r .refresh_token)
[[ -n $new && $new != null && $new != "$REFRESH" ]] && ok "refresh rotates token" || bad "refresh"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$REFRESH\"}" "$API/v1/auth/refresh")
[[ $code == 401 ]] && ok "old refresh token rejected" || bad "reuse got $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"wrong pass\"}" "$API/v1/auth/login")
[[ $code == 401 ]] && ok "bad login → 401" || bad "bad login got $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"correct horse\"}" "$API/v1/auth/login")
[[ $code == 200 ]] && ok "login" || bad "login got $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${H[@]}" "$API/v1/me")
[[ $code == 204 ]] && ok "delete account" || bad "delete account got $code"

echo
echo "passed: $pass  failed: $fail"
[[ $fail == 0 ]]
