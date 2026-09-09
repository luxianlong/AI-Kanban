#!/usr/bin/env bash
# Pull a TLS certificate out of Azure Key Vault using this VM's managed identity
# and write it as PEM files for the Vite dev server to serve HTTPS from.
#
#   ./scripts/fetch-kv-cert.sh <vault-name> <secret-name> [out-dir] [out-basename]
#
# A Key Vault *certificate* stores its full chain + private key in the SECRET of
# the same name (as a base64 PKCS#12), so this reads the secret endpoint — the
# managed identity therefore needs secrets/get, not just certificates/get.
#
# Output (0600, out-dir defaults to ~/certs; out-basename defaults to the secret
# name, but Key Vault secret names are unwieldy so pass a stable one):
#   <out-dir>/<basename>.key   private key, PEM
#   <out-dir>/<basename>.crt   leaf + intermediates, PEM, leaf FIRST
#
# Re-run it to renew; nothing here is destructive beyond overwriting those two
# files, and the previous pair is kept as *.bak.
set -euo pipefail

USAGE="usage: fetch-kv-cert.sh <vault-name> <secret-name> [out-dir] [out-basename]"
VAULT="${1:?$USAGE}"
CERT="${2:?$USAGE}"
OUT="${3:-$HOME/certs}"
NAME="${4:-$CERT}"
API=7.4

# Strip a full URL down to the bare vault name, so either form works.
VAULT="${VAULT#https://}"; VAULT="${VAULT%%.vault.azure.net*}"

echo "→ requesting managed-identity token for vault.azure.net"
TOKEN=$(curl -sS -m 15 -H 'Metadata:true' \
  "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fvault.azure.net" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

echo "→ fetching secret '$CERT' from $VAULT.vault.azure.net"
RESP=$(curl -sS -m 30 -H "Authorization: Bearer $TOKEN" \
  "https://$VAULT.vault.azure.net/secrets/$CERT?api-version=$API")

# Surface Key Vault's own error verbatim rather than writing a broken PEM.
if ! VALUE=$(printf '%s' "$RESP" | python3 -c '
import sys, json
d = json.load(sys.stdin)
if "error" in d:
    e = d["error"]
    sys.stderr.write("Key Vault error: %s: %s\n" % (e.get("code"), e.get("message")))
    raise SystemExit(1)
print(d["value"])
print(d.get("contentType", ""), file=sys.stderr)
'); then
  echo "  (if this is a 403, grant the VM identity 585f00f8-44df-4e92-b2f8-8e343abf338a"
  echo "   the 'Key Vault Secrets User' role — or a Get-secret access policy — on $VAULT)"
  exit 1
fi

mkdir -p "$OUT"; chmod 700 "$OUT"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# A PKCS#12 secret is base64; a PEM-contentType secret is already PEM text.
if printf '%s' "$VALUE" | grep -q -- '-----BEGIN'; then
  printf '%s' "$VALUE" > "$TMP/bundle.pem"
else
  printf '%s' "$VALUE" | base64 -d > "$TMP/bundle.pfx"
  # KV exports the PKCS#12 with no password.
  openssl pkcs12 -in "$TMP/bundle.pfx" -passin pass: -nodes -out "$TMP/bundle.pem"
fi

openssl pkey -in "$TMP/bundle.pem" -out "$TMP/tls.key"

# Key Vault returns the PKCS#12 chain root-first; a TLS server must present the
# leaf first. order-chain.py reorders it and drops the self-signed root.
openssl crl2pkcs7 -nocrl -certfile "$TMP/bundle.pem" \
  | openssl pkcs7 -print_certs -out "$TMP/unordered.crt"
python3 "$(dirname "$0")/lib/order-chain.py" \
  "$TMP/unordered.crt" "$TMP/tls.key" "$TMP/tls.crt"

for ext in key crt; do
  [ -f "$OUT/$NAME.$ext" ] && cp -f "$OUT/$NAME.$ext" "$OUT/$NAME.$ext.bak"
  install -m 600 "$TMP/tls.$ext" "$OUT/$NAME.$ext"
done

echo "✔ wrote $OUT/$NAME.key and $OUT/$NAME.crt"
openssl x509 -in "$OUT/$NAME.crt" -noout -subject -issuer -dates \
  -ext subjectAltName 2>/dev/null || true
