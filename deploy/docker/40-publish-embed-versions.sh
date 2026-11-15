#!/bin/sh
# Keep immutable browser SDK bundles referenced by previously generated snippets.
set -eu
source_root=${REACHAI_SDK_VERSION_SOURCE:-/opt/reachai-sdk-versions}
target_root=${REACHAI_SDK_VERSION_TARGET:-/var/lib/reachai/embed-versions}
mkdir -p "$target_root"
for source in "$source_root"/*; do
    [ -d "$source" ] || continue
    version=${source##*/}
    target="$target_root/$version"
    if [ -e "$target" ]; then
        if ! diff -rq "$source" "$target" >/dev/null; then
            echo "Embed SDK version $version conflicts with the retained version" >&2
            exit 1
        fi
        continue
    fi
    staging=$(mktemp -d "$target_root/.staging.XXXXXX")
    cp -R "$source/." "$staging/"
    # Multiple replicas publishing the same version may start concurrently.
    if ! mv -T "$staging" "$target" 2>/dev/null; then
        rm -rf "$staging"
        diff -rq "$source" "$target" >/dev/null || exit 1
    fi
done
