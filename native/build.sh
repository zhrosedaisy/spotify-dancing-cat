#!/bin/sh
# AudioTap yardımcısını derler. Command Line Tools'ta derleyici ile en yeni SDK bazen
# uyuşmuyor; bu yüzden önce varsayılan SDK, olmazsa eski SDK'lar denenir.
# Kullanım: native/build.sh <çıktı-yolu>
set -u
OUT="$1"
SRC="$(dirname "$0")/AudioTap.swift"
mkdir -p "$(dirname "$OUT")"
FLAGS="-O -parse-as-library -swift-version 5 -target arm64-apple-macos14.0"
[ "$(uname -m)" = "x86_64" ] && FLAGS="-O -parse-as-library -swift-version 5 -target x86_64-apple-macos14.0"

if swiftc $FLAGS "$SRC" -o "$OUT" 2>/dev/null; then exit 0; fi
for SDK in $(ls -d /Library/Developer/CommandLineTools/SDKs/MacOSX*.*.sdk /Library/Developer/CommandLineTools/SDKs/MacOSX[0-9]*.sdk 2>/dev/null | sort -r); do
  if swiftc $FLAGS -sdk "$SDK" "$SRC" -o "$OUT" 2>/dev/null; then
    echo "audiotap derlendi ($SDK)"
    exit 0
  fi
done
echo "UYARI: audiotap derlenemedi; kedi sabit tempoda dans edecek." >&2
exit 0
