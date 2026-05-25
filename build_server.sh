#!/bin/bash
set -e

# Resolve script directory to allow running from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/server"

print_usage() {
    echo "Usage: $0 [debug | release | all]"
    echo "  debug   - Builds debug targets for both Rust and Go servers"
    echo "  release - Builds optimized release targets (default)"
    echo "  all     - Builds both debug and release targets"
}

MODE=${1:-release}

build_rust() {
    local mode=$1
    echo "=== Building Rust Engine ($mode) ==="
    cd "$SERVER_DIR/rust"
    if [ "$mode" = "release" ]; then
        cargo build --release
        
        # Copy release binary right beside this script
        cp "target/release/crimson-deck-server" "$SCRIPT_DIR/crimson-deck-server"
        echo "✓ Success! Backup release server binary saved beside script: crimson-deck-server"
    else
        cargo build
    fi
}

build_go() {
    local mode=$1
    echo "=== Building Go Network Gateway ($mode) ==="
    cd "$SERVER_DIR/go"
    if [ "$mode" = "release" ]; then
        go build -o server main.go
    else
        go build -gcflags="all=-N -l" -o server main.go
    fi
}

case "$MODE" in
    debug)
        build_rust "debug"
        build_go "debug"
        echo "✓ Server debug build completed successfully!"
        ;;
    release)
        build_rust "release"
        build_go "release"
        echo "✓ Server release build completed successfully!"
        ;;
    all)
        build_rust "debug"
        build_go "debug"
        build_rust "release"
        build_go "release"
        echo "✓ All server targets compiled successfully!"
        ;;
    *)
        print_usage
        exit 1
        ;;
esac
