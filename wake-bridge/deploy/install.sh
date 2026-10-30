#!/usr/bin/env bash
#
# Install the Wake Bridge on a Linux host (systemd).
# Run as root from anywhere:  sudo ./deploy/install.sh
#
# This script copies files only. It does not run npm, build anything, or
# download dependencies. The service is enabled but NOT started: edit the
# config and install a TLS certificate first.
set -euo pipefail

INSTALL_DIR="/opt/pcwake-bridge"
CONFIG_DIR="/etc/pcwake-bridge"
CERT_DIR="${CONFIG_DIR}/certs"
STATE_DIR="/var/lib/pcwake-bridge"
SERVICE_USER="pcwake"
UNIT_NAME="pcwake-bridge.service"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "error: run this installer as root (sudo ./deploy/install.sh)" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "error: Node.js 20 or newer is required (node not found in PATH)" >&2
  exit 1
fi
NODE_MAJOR="$(node -p "process.versions.node.split('.')[0]")"
if [ "${NODE_MAJOR}" -lt 20 ]; then
  echo "error: Node.js 20 or newer is required (found $(node --version))" >&2
  exit 1
fi

if ! getent group "${SERVICE_USER}" >/dev/null 2>&1; then
  echo "Creating system group ${SERVICE_USER}"
  groupadd --system "${SERVICE_USER}"
fi
if ! id -u "${SERVICE_USER}" >/dev/null 2>&1; then
  echo "Creating system user ${SERVICE_USER}"
  useradd --system --gid "${SERVICE_USER}" --home-dir "${STATE_DIR}" --no-create-home \
    --shell /usr/sbin/nologin --comment "PC Wake Controller Wake Bridge" "${SERVICE_USER}"
fi

echo "Installing application files to ${INSTALL_DIR}"
install -d -o root -g root -m 0755 "${INSTALL_DIR}"
install -d -o root -g root -m 0755 "${INSTALL_DIR}/src" "${INSTALL_DIR}/tools" "${INSTALL_DIR}/deploy"
cp -a "${REPO_DIR}/src/." "${INSTALL_DIR}/src/"
install -m 0644 "${REPO_DIR}/package.json" "${INSTALL_DIR}/package.json"
install -m 0644 "${REPO_DIR}/README.md" "${INSTALL_DIR}/README.md"
install -m 0644 "${REPO_DIR}/config.example.json" "${INSTALL_DIR}/config.example.json"
install -m 0755 "${REPO_DIR}/tools/gen-vectors.js" "${INSTALL_DIR}/tools/gen-vectors.js"
install -m 0755 "${REPO_DIR}/deploy/gen-cert.sh" "${INSTALL_DIR}/deploy/gen-cert.sh"

echo "Preparing configuration directory ${CONFIG_DIR}"
install -d -o root -g "${SERVICE_USER}" -m 0750 "${CONFIG_DIR}"
install -d -o root -g "${SERVICE_USER}" -m 0750 "${CERT_DIR}"
install -m 0755 "${REPO_DIR}/deploy/gen-cert.sh" "${CONFIG_DIR}/gen-cert.sh"
if [ ! -f "${CONFIG_DIR}/config.json" ]; then
  install -o root -g "${SERVICE_USER}" -m 0640 \
    "${REPO_DIR}/config.example.json" "${CONFIG_DIR}/config.json"
  # The unit only grants write access to /var/lib/pcwake-bridge. The example's
  # other relative paths (certs) correctly resolve against /etc/pcwake-bridge.
  sed -i 's#"stateDir": "./state"#"stateDir": "/var/lib/pcwake-bridge"#' \
    "${CONFIG_DIR}/config.json"
  echo "Created ${CONFIG_DIR}/config.json from the example (edit it before starting)."
else
  echo "Keeping existing ${CONFIG_DIR}/config.json"
fi

echo "Preparing state directory ${STATE_DIR} (mode 0700, owner ${SERVICE_USER})"
install -d -o "${SERVICE_USER}" -g "${SERVICE_USER}" -m 0700 "${STATE_DIR}"

echo "Installing systemd unit"
install -m 0644 "${REPO_DIR}/deploy/${UNIT_NAME}" "/etc/systemd/system/${UNIT_NAME}"
systemctl daemon-reload
systemctl enable "${UNIT_NAME}"

cat <<EOF

Installation complete. The service is enabled but NOT started yet.

1. Edit ${CONFIG_DIR}/config.json:
   - "name", "listenHost", "listenPort", "publicHost"
   - "targets" (MAC allow-list; broadcast/port define where Magic Packets go)
   - "stateDir": "${STATE_DIR}"
   - "tlsCertPath": "${CERT_DIR}/bridge-cert.pem"
   - "tlsKeyPath": "${CERT_DIR}/bridge-key.pem"

2. Create a TLS certificate (required; the bridge refuses plaintext):
     ${CONFIG_DIR}/gen-cert.sh <LAN-IP-or-DNS-name> ${CERT_DIR}
   or, on Tailscale:
     tailscale cert <machine>.<tailnet>.ts.net
     (copy the .crt/.key to ${CERT_DIR}, or point config at them)

   The service runs as ${SERVICE_USER}; the private key must be readable by it:
     chown root:${SERVICE_USER} ${CERT_DIR}/bridge-key.pem
     chmod 0640 ${CERT_DIR}/bridge-key.pem
     chmod 0644 ${CERT_DIR}/bridge-cert.pem

3. Start and inspect:
     systemctl start ${UNIT_NAME}
     systemctl status ${UNIT_NAME}
     journalctl -u ${UNIT_NAME} -f

4. Pair the Android app:
     cd ${INSTALL_DIR} && node src/cli.js pair --config ${CONFIG_DIR}/config.json

Firewall: allow TCP 8443 only from your LAN/Tailscale. Never expose the WoL
UDP port or the loopback admin port 47111. The bridge sends WoL to its own
configured broadcast addresses; it does not listen for WoL.
EOF
