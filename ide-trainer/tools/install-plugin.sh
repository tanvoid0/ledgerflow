#!/usr/bin/env bash
# Bump the plugin version, build it, and install it into the local IDE
# (idePath in gradle.properties, override with IDE_PATH env var).
# Usage: install-plugin.sh [major|minor|patch]   (default: minor)
set -euo pipefail
cd "$(dirname "$0")/.."

bump="${1:-minor}"
current=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
IFS=. read -r major minor patch <<< "$current"
case "$bump" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) echo "unknown bump type: $bump (want major|minor|patch)" >&2; exit 1 ;;
esac
next="$major.$minor.$patch"
sed -i "s/^version = \"$current\"/version = \"$next\"/" build.gradle.kts
echo "Version $current -> $next"

./gradlew buildPlugin

zip=$(readlink -f "$(ls -t build/distributions/*.zip | head -1)")
plugins_dir="$APPDATA/JetBrains/IntelliJIdea2026.2/plugins"

# `idea.bat installPlugins` is broken on this box (Windows unix-domain-socket
# bug in the IDE's own activation IPC — fails whether or not the IDE is
# running, and silently no-ops instead of erroring the script). Install by
# unzipping straight into the plugins dir instead, same as "Install from Disk".
taskkill //IM idea64.exe //F >/dev/null 2>&1 || true
sleep 2

rm -rf "$plugins_dir/ide-trainer"
unzip -q "$zip" -d "$plugins_dir"
echo "Installed $zip ($next) into $plugins_dir — restart the IDE to load it."
