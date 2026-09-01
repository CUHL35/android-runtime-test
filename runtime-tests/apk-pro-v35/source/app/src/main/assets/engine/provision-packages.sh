#!/system/bin/sh
set -eu

FILES_ROOT="$1"
shift
[ "$#" -gt 0 ] || { echo "ERROR: no Termux packages requested"; exit 2; }

PREFIX="$FILES_ROOT/usr"
HOME="$FILES_ROOT/build-home"
TMPDIR="$HOME/tmp"
CACHE="$FILES_ROOT/download-cache"
APT_CACHE="$CACHE/apt"
ARCHIVES="$APT_CACHE/archives"
STAGE="$CACHE/pkg-stage"
APT="$PREFIX/bin/apt-get"
DPKG_DEB="$PREFIX/bin/dpkg-deb"

export PREFIX HOME TMPDIR
export PATH="$PREFIX/bin:/system/bin:/system/xbin"
# Termux packages on Android >= 7 are built to run without LD_LIBRARY_PATH.
unset LD_LIBRARY_PATH 2>/dev/null || true
export DEBIAN_FRONTEND=noninteractive

mkdir -p "$HOME" "$TMPDIR" "$CACHE" "$ARCHIVES/partial"
[ -x "$APT" ] || { echo "ERROR: bootstrap missing apt-get"; exit 3; }
[ -x "$DPKG_DEB" ] || { echo "ERROR: bootstrap missing dpkg-deb"; exit 3; }

echo "TERMUX_APT_UPDATE=1"
echo "TERMUX_APT_ARCHIVES=$ARCHIVES"
# Termux APT is compiled with /data/data/com.termux/cache/apt as CACHE_DIR.
# Override it explicitly so APK PRO never touches another package's private cache path.
"$APT" -o "Dir::Cache=$APT_CACHE" -o "Dir::Cache::archives=$ARCHIVES" update

echo "APKPRO_PACKAGE_CACHE=$ARCHIVES"
echo "TERMUX_PACKAGES=$*"
# Download only: dpkg must never install into /data/data/com.termux. Signed APT metadata
# validates package hashes; APK PRO keeps downloaded .deb files as cache, extracts them only
# into staging, relocates to its equal-length private prefix, validates, then atomically swaps.
"$APT" -o "Dir::Cache=$APT_CACHE" -o "Dir::Cache::archives=$ARCHIVES" \
  -y --download-only --no-install-recommends install "$@"

rm -rf "$STAGE"
mkdir -p "$STAGE"
found=0
for deb in "$ARCHIVES"/*.deb; do
  [ -f "$deb" ] || continue
  found=1
  echo "EXTRACT_DEB=$(basename "$deb")"
  "$DPKG_DEB" -x "$deb" "$STAGE"
done
[ "$found" -eq 1 ] || { echo "ERROR: apt downloaded no .deb files"; exit 4; }

echo "PKG_STAGE=$STAGE"
