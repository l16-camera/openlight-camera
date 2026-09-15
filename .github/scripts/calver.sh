#!/usr/bin/env bash
# Calendar versioning for openlight-camera APK builds.
#
# versionName: YY.M.BUILD
#   - CI builds:     YY.M.<GITHUB_RUN_NUMBER>
#   - Tag builds:    vYY.M.BUILD  →  YY.M.BUILD
#   - Local builds:  YY.M.0
#
# versionCode: YYMM * 10000 + BUILD_NUMBER
#   Monotonic across the month and build count.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BUILD_NUMBER="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-0}}"
APKTOOL_YML="light_camera/apktool.yml"

if [[ ! -f "$APKTOOL_YML" ]]; then
  echo "Error: $APKTOOL_YML not found" >&2
  exit 1
fi

resolve_version_name() {
  local ref_name="${GITHUB_REF_NAME:-}"

  if [[ "$ref_name" =~ ^v([0-9]{2}\.[0-9]{1,2}\.[0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi

  if [[ -n "${CALVER_VERSION_NAME:-}" ]]; then
    echo "$CALVER_VERSION_NAME"
    return
  fi

  local year month
  year="$(date -u +%y)"
  month="$(date -u +%m)"
  month="${month#0}"
  echo "${year}.${month}.${BUILD_NUMBER}"
}

VERSION_NAME="$(resolve_version_name)"
if [[ "$VERSION_NAME" =~ ^([0-9]{2})\.([0-9]{1,2})\.([0-9]+)$ ]]; then
  YY="${BASH_REMATCH[1]}"
  MM="${BASH_REMATCH[2]}"
  BUILD="${BASH_REMATCH[3]}"
  DATE_COMPACT="${YY}$(printf '%02d' "$((10#$MM))")"
else
  echo "Error: version name must match YY.M.BUILD, got: ${VERSION_NAME}" >&2
  exit 1
fi

VERSION_CODE=$(( 10#${DATE_COMPACT} * 10000 + BUILD ))

export VERSION_NAME VERSION_CODE

inplace_sed() {
  local file="$1"
  shift
  local tmp
  tmp="$(mktemp)"
  sed "$@" "$file" > "$tmp"
  mv "$tmp" "$file"
}

inplace_sed "$APKTOOL_YML" \
  -e "s/^  versionCode: .*/  versionCode: ${VERSION_CODE}/" \
  -e "s/^  versionName: .*/  versionName: ${VERSION_NAME}/"

MANIFEST="light_camera/AndroidManifest.xml"
if [[ -f "$MANIFEST" ]]; then
  inplace_sed "$MANIFEST" \
    -e "s/platformBuildVersionCode=\"[0-9]*\"/platformBuildVersionCode=\"${VERSION_CODE}\"/" \
    -e "s/platformBuildVersionName=\"[^\"]*\"/platformBuildVersionName=\"${VERSION_NAME}\"/"
fi

echo "CalVer: versionName=${VERSION_NAME} versionCode=${VERSION_CODE}"