#!/usr/bin/env sh
set -eu

BUILD_DIR="${TMPDIR:-/tmp}/nonprofit-csv-export-example"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" $(find src/main/java -name '*.java' -print)
java -cp "$BUILD_DIR" education.nonprofit.export.NonprofitExportApplication
