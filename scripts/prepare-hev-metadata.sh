#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEV_REV="75dbc1e9ace6c70d3d1b94df7c283efe1de7effc"
DEST="${1:-${ROOT}/.work/hev-socks5-tunnel}"

if [[ -e "${DEST}/.git" ]]; then
  git -C "${DEST}" fetch --depth 1 origin "${HEV_REV}"
else
  mkdir -p "$(dirname "${DEST}")"
  git clone --filter=blob:none https://github.com/heiher/hev-socks5-tunnel.git "${DEST}"
fi

git -C "${DEST}" checkout --detach "${HEV_REV}"
git -C "${DEST}" reset --hard "${HEV_REV}"
git -C "${DEST}" clean -fdx
mkdir -p "${DEST}/src/nova-metadata"
cp "${ROOT}"/native/metadata/packet_metadata.[ch] "${DEST}/src/nova-metadata/"
cp "${ROOT}"/native/metadata/pbuf_reader_adapter.[ch] "${DEST}/src/nova-metadata/"
cp "${ROOT}"/native/metadata/metadata_tap.[ch] "${DEST}/src/nova-metadata/"
cp "${ROOT}/native/hev/nova_metadata_embedded.c" "${DEST}/src/nova-metadata/"

git -C "${DEST}" apply --check "${ROOT}/native/hev/nova-ping-hev-hooks.patch"
git -C "${DEST}" apply "${ROOT}/native/hev/nova-ping-hev-hooks.patch"

grep -q 'nova_metadata_observe (buf)' "${DEST}/src/hev-socks5-tunnel.c"
grep -q 'nova_metadata_observe (p)' "${DEST}/src/hev-socks5-tunnel.c"
grep -q 'hev_socks5_tunnel_metadata_poll' "${DEST}/src/hev-socks5-tunnel.c"

printf 'HEV metadata patch applied at %s\n' "${HEV_REV}"
