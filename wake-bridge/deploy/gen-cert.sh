#!/usr/bin/env bash
#
# Generate a self-signed ECDSA P-256 certificate for the Wake Bridge.
# Clients pin the SHA-256 fingerprint, so a public CA is not required.
#
# Usage:
#   ./gen-cert.sh <hostname-or-IP> [output-directory] [validity-days]
#
# Examples:
#   ./gen-cert.sh 192.168.1.10 /etc/pcwake-bridge/certs
#   ./gen-cert.sh wake-bridge.tailnet.ts.net ./certs 825
#
# Outputs bridge-cert.pem (0644) and bridge-key.pem (0600).
set -euo pipefail

HOST="${1:-}"
OUT_DIR="${2:-./certs}"
DAYS="${3:-825}"

if [ -z "${HOST}" ]; then
  echo "usage: $0 <hostname-or-IP> [output-directory] [validity-days]" >&2
  exit 2
fi
if ! printf '%s' "${HOST}" | grep -Eq '^[A-Za-z0-9._:-]+$'; then
  echo "error: host must be an IP address or DNS name containing only A-Z a-z 0-9 . _ : -" >&2
  exit 2
fi
if ! printf '%s' "${DAYS}" | grep -Eq '^[0-9]+$' || [ "${DAYS}" -lt 1 ] || [ "${DAYS}" -gt 3650 ]; then
  echo "error: validity-days must be an integer between 1 and 3650" >&2
  exit 2
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "error: openssl is required" >&2
  exit 1
fi

case "${HOST}" in
  *:*)
    SAN_ENTRY="IP:${HOST}"      # IPv6 (OpenSSL accepts IP:::1 notation)
    ;;
  *)
    if printf '%s' "${HOST}" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
      SAN_ENTRY="IP:${HOST}"
    else
      SAN_ENTRY="DNS:${HOST}"
    fi
    ;;
esac

SAN="DNS:localhost,IP:127.0.0.1,IP:::1,${SAN_ENTRY}"
CERT="${OUT_DIR}/bridge-cert.pem"
KEY="${OUT_DIR}/bridge-key.pem"

if [ -e "${CERT}" ] || [ -e "${KEY}" ]; then
  echo "error: ${CERT} or ${KEY} already exists; move it aside first" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"
umask 077
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
  -sha256 -nodes -days "${DAYS}" \
  -keyout "${KEY}" -out "${CERT}" \
  -subj "/CN=${HOST}" \
  -addext "subjectAltName=${SAN}"

chmod 0600 "${KEY}"
chmod 0644 "${CERT}"

FINGERPRINT="$(openssl x509 -in "${CERT}" -outform DER | openssl dgst -sha256 -r | awk '{print $1}')"

cat <<EOF

Created:
  certificate: ${CERT} (0644)
  private key: ${KEY} (0600)
  SANs:        ${SAN}

Certificate SHA-256 fingerprint (lowercase hex):
  ${FINGERPRINT}

Point the bridge config at these files:
  "tlsCertPath": "${CERT}",
  "tlsKeyPath": "${KEY}"

If the service runs as user "pcwake", allow it to read the private key:
  chown root:pcwake "${KEY}"
  chmod 0640 "${KEY}"

Restart the bridge after changing the certificate. The Android app will pin
this fingerprint on pairing; regenerate and re-pair if the certificate changes.
EOF
