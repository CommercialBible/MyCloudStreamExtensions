#!/usr/bin/env bash
#
# fetch-deps.sh — downloads every binary dependency the build needs.
#
# Nothing is committed to git: the toolchain is large (JDK-sized Kotlin compiler,
# android.jar, R8/D8). Run this once locally, or let the GitHub Actions workflow run
# it (it caches the result between runs).
#
# All downloads are pinned to explicit versions so a build is reproducible.
# Override the destination with DEPS_DIR=/path ./tools/fetch-deps.sh
#
set -euo pipefail

DEPS_DIR="${DEPS_DIR:-.build-deps}"

KOTLIN_VERSION="2.4.21"
R8_VERSION="9.5.32"
JSOUP_VERSION="1.21.1"
NICEHTTP_VERSION="0.4.18"
JSPECIFY_VERSION="1.0.0"
ANDROID_PLATFORM="platform-36_r02"
CLOUDSTREAM_RELEASE="pre-release"

mkdir -p "$DEPS_DIR"
cd "$DEPS_DIR"

say() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }

download() {
  local url="$1" out="$2"
  if [ -s "$out" ]; then
    say "already present: $out"
    return 0
  fi
  say "downloading $out"
  curl -fsSL --retry 3 --retry-delay 2 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

# --- Kotlin compiler -----------------------------------------------------------------
if [ ! -x "kotlinc/bin/kotlinc" ]; then
  download \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
    "kotlin-compiler.zip"
  say "extracting kotlinc"
  unzip -q -o kotlin-compiler.zip
  rm -f kotlin-compiler.zip
else
  say "already present: kotlinc"
fi

# --- Android platform (android.jar, used as compile + dex library) --------------------
if [ ! -f "android.jar" ]; then
  download "https://dl.google.com/android/repository/${ANDROID_PLATFORM}.zip" "platform.zip"
  say "extracting android.jar"
  unzip -q -o platform.zip "android-*/android.jar"
  find . -maxdepth 2 -name android.jar -path "./android-*/*" -exec mv {} android.jar \;
  rm -rf platform.zip android-*
else
  say "already present: android.jar"
fi

# --- R8 / D8 dexer -------------------------------------------------------------------
download "https://maven.google.com/com/android/tools/r8/${R8_VERSION}/r8-${R8_VERSION}.jar" "r8.jar"

# --- Compile-time libraries ----------------------------------------------------------
# CloudStream API classes: provided by the host app at runtime, needed to compile.
download \
  "https://github.com/recloudstream/cloudstream/releases/download/${CLOUDSTREAM_RELEASE}/classes.jar" \
  "cloudstream.jar"

download \
  "https://repo1.maven.org/maven2/org/jsoup/jsoup/${JSOUP_VERSION}/jsoup-${JSOUP_VERSION}.jar" \
  "jsoup.jar"

# NiceHttp is a host-provided dependency of the CloudStream API (Requests type).
download \
  "https://jitpack.io/com/github/Blatzar/NiceHttp/${NICEHTTP_VERSION}/NiceHttp-${NICEHTTP_VERSION}.jar" \
  "nicehttp.jar"

# JSpecify annotations appear in jsoup 1.21+ signatures; needed for clean compilation.
download \
  "https://repo1.maven.org/maven2/org/jspecify/jspecify/${JSPECIFY_VERSION}/jspecify-${JSPECIFY_VERSION}.jar" \
  "jspecify.jar"

say "all dependencies ready in $DEPS_DIR"
# NOTE: we are still inside $DEPS_DIR (the script cd's into it above), so this must
# list the current directory. Listing "$DEPS_DIR" by its relative path fails here and,
# under `set -e`, would abort the caller before the build ever ran.
ls -1 .
