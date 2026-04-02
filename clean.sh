#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRY_RUN=0

usage() {
    cat <<'EOF'
Usage:
  ./clean.sh
  ./clean.sh --dry-run

Purpose:
  Remove local caches, build outputs, logs, bundled SDK state, and historical
  snapshot directories from a copied workspace before open-sourcing it.
EOF
}

case "${1-}" in
    "")
        ;;
    --dry-run|-n)
        DRY_RUN=1
        ;;
    --help|-h)
        usage
        exit 0
        ;;
    *)
        echo "[clean] Unknown argument: $1" >&2
        usage >&2
        exit 1
        ;;
esac

if [[ ! -f "$ROOT_DIR/settings.gradle.kts" || ! -d "$ROOT_DIR/app" ]]; then
    echo "[clean] This script must live in the project root." >&2
    exit 1
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[clean] Dry run mode. Nothing will be deleted."
fi

remove_path() {
    local target="$1"
    if [[ ! -e "$target" && ! -L "$target" ]]; then
        return 0
    fi
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "[clean] would remove: ${target#$ROOT_DIR/}"
    else
        rm -rf -- "$target"
        echo "[clean] removed: ${target#$ROOT_DIR/}"
    fi
}

remove_root_glob() {
    local pattern="$1"
    local target
    while IFS= read -r target; do
        remove_path "$target"
    done < <(compgen -G "$ROOT_DIR/$pattern" || true)
}

remove_find_matches() {
    local type="$1"
    local name="$2"
    while IFS= read -r -d '' path; do
        remove_path "$path"
    done < <(find "$ROOT_DIR" -type "$type" -name "$name" -print0)
}

remove_fixed_targets() {
    local targets=(
        "$ROOT_DIR/AGENTS.md"
        "$ROOT_DIR/.idea"
        "$ROOT_DIR/.gradle"
        "$ROOT_DIR/.gradle-local"
        "$ROOT_DIR/.gradle-cache"
        "$ROOT_DIR/.gradle-temp"
        "$ROOT_DIR/.android-user"
        "$ROOT_DIR/android-sdk"
        "$ROOT_DIR/android-home"
        "$ROOT_DIR/local.properties"
        "$ROOT_DIR/build"
        "$ROOT_DIR/app/build"
        "$ROOT_DIR/app/debug.keystore"
        "$ROOT_DIR/captures"
        "$ROOT_DIR/outputs"
        "$ROOT_DIR/sensors_overview.html"
    )
    local target
    for target in "${targets[@]}"; do
        remove_path "$target"
    done
}

echo "[clean] Root: $ROOT_DIR"

remove_fixed_targets
remove_root_glob "app_副本*"
remove_root_glob "app-不能用"
remove_root_glob "*.apk"
remove_root_glob "*.aab"
remove_root_glob "*.log"

remove_find_matches f ".DS_Store"
remove_find_matches f "*.iml"
remove_find_matches f "*.apk"
remove_find_matches f "*.aab"
remove_find_matches f "*.log"
remove_find_matches f "*.keystore"
remove_find_matches f "*.jks"
remove_find_matches f "*.p12"

echo "[clean] Done."
