#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export REACHAI_SDK_VERSION_SOURCE="$tmp/source" REACHAI_SDK_VERSION_TARGET="$tmp/retained"
mkdir -p "$REACHAI_SDK_VERSION_SOURCE/old"
printf 'old' > "$REACHAI_SDK_VERSION_SOURCE/old/style.css"
sh "$root/40-publish-embed-versions.sh"
rm -rf "$REACHAI_SDK_VERSION_SOURCE/old"
mkdir -p "$REACHAI_SDK_VERSION_SOURCE/new"
printf 'new' > "$REACHAI_SDK_VERSION_SOURCE/new/style.css"
sh "$root/40-publish-embed-versions.sh"
test "$(cat "$REACHAI_SDK_VERSION_TARGET/old/style.css")" = old
test "$(cat "$REACHAI_SDK_VERSION_TARGET/new/style.css")" = new
sh "$root/40-publish-embed-versions.sh"
printf 'corrupt' > "$REACHAI_SDK_VERSION_TARGET/new/style.css"
if sh "$root/40-publish-embed-versions.sh" 2>/dev/null; then
    echo 'Expected a conflicting immutable version to fail' >&2
    exit 1
fi
echo 'Embed SDK version retention passed'
