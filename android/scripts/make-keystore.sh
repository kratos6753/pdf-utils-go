#!/usr/bin/env bash
# Creates the release (upload) keystore once and prints what to paste into GitHub secrets
# and assetlinks.json. Keep the .jks and passwords safe: Play updates must be signed with it.
#
#   android/scripts/make-keystore.sh [path/to/release.jks]
set -euo pipefail

out="${1:-$HOME/.android-keys/pdf-utils-release.jks}"
alias="${KEY_ALIAS:-pdfutils}"
if [ -e "$out" ]; then echo "$out already exists; refusing to overwrite it." >&2; exit 1; fi
mkdir -p "$(dirname "$out")"

read -rsp "Keystore password (min 6 chars): " pass; echo
read -rsp "Repeat password: " pass2; echo
[ "$pass" = "$pass2" ] || { echo "Passwords do not match" >&2; exit 1; }

keytool -genkeypair -v -keystore "$out" -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$pass" -keypass "$pass" -dname "CN=PDF Utils, O=chirudev.site"

sha256=$(keytool -list -v -keystore "$out" -alias "$alias" -storepass "$pass" | awk '/SHA256:/ {print $2}')
sha1=$(keytool -list -v -keystore "$out" -alias "$alias" -storepass "$pass" | awk '/SHA1:/ {print $2}')

cat <<MSG

Keystore written to $out

Add these GitHub repository secrets (Settings → Secrets and variables → Actions),
or run the gh commands below from the repo root:

  gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 < "$out" | tr -d '\n')
  gh secret set ANDROID_KEYSTORE_PASSWORD   # paste the password
  gh secret set ANDROID_KEY_PASSWORD        # same password
  gh secret set ANDROID_KEY_ALIAS --body "$alias"

Certificate fingerprints:
  SHA-256: $sha256   (assetlinks.json, Play App Signing upload key)
  SHA-1:   $sha1   (Google Cloud OAuth "Android" client)
MSG
