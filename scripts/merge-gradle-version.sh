#!/bin/sh
# Git merge driver for gradle.properties: merges normally, but the mod_version
# line never conflicts; the higher of the two versions wins.
# Usage: merge-gradle-version.sh %O %A %B   (base, ours, theirs; result goes in ours)
base="$1"; ours="$2"; theirs="$3"
ver() { sed -n 's/^mod_version=//p' "$1" | head -n1; }
vo=$(ver "$ours"); vt=$(ver "$theirs")
best=$(printf '%s\n%s\n' "$vo" "$vt" | sort -V | tail -n1)
for f in "$base" "$ours" "$theirs"; do
  sed 's/^mod_version=.*/mod_version=@@VERSION@@/' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done
git merge-file "$ours" "$base" "$theirs"
status=$?
sed "s/^mod_version=@@VERSION@@/mod_version=$best/" "$ours" > "$ours.tmp" && mv "$ours.tmp" "$ours"
exit $status
