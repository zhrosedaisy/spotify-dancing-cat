#!/bin/sh
# Builds the AudioTap helper. With Command Line Tools the compiler and the newest SDK
# sometimes don't match, so the default SDK is tried first, then older SDKs.
# Usage: native/build.sh <output-path>
set -u
OUT="$1"
SRC="$(dirname "$0")/AudioTap.swift"
mkdir -p "$(dirname "$OUT")"
FLAGS="-O -parse-as-library -swift-version 5 -target arm64-apple-macos14.0"
[ "$(uname -m)" = "x86_64" ] && FLAGS="-O -parse-as-library -swift-version 5 -target x86_64-apple-macos14.0"

if swiftc $FLAGS "$SRC" -o "$OUT" 2>/dev/null; then exit 0; fi
for SDK in $(ls -d /Library/Developer/CommandLineTools/SDKs/MacOSX*.*.sdk /Library/Developer/CommandLineTools/SDKs/MacOSX[0-9]*.sdk 2>/dev/null | sort -r); do
  if swiftc $FLAGS -sdk "$SDK" "$SRC" -o "$OUT" 2>/dev/null; then
    echo "audiotap built ($SDK)"
    exit 0
  fi
done
echo "WARNING: could not build audiotap; the cat will dance at a fixed tempo." >&2
exit 0
