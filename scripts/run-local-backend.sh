#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
    '') build=true ;;
    --skip-build) build=false ;;
    *) echo 'Usage: scripts/run-local-backend.sh [--skip-build]' >&2; exit 2 ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/backend"
if "$build"; then
    mvn -DskipTests package
fi

# Maven replaces target/*.jar during packaging. A running JVM must keep its own copy.
mkdir -p "$repo_root/output/local-runtime"
runtime_dir="$(mktemp -d "$repo_root/output/local-runtime/backend.XXXXXXXX")"
cp target/office-product-import-0.1.0-SNAPSHOT.jar "$runtime_dir/office.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jar_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
"$jar_bin" tf "$runtime_dir/office.jar" >/dev/null
echo "Starting local backend from $runtime_dir/office.jar"
exec "$java_bin" -Djava.awt.headless=true -jar "$runtime_dir/office.jar"
