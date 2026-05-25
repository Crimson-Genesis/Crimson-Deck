#!/bin/bash
set -e

# Resolve script directory to allow running from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/android"

print_usage() {
    echo "Usage: $0 [debug | release | all]"
    echo "  debug   - Builds the Android debug APK (installs/runs out-of-the-box)"
    echo "  release - Builds the Android release APK (default)"
    echo "  all     - Builds both debug and release APKs"
}

MODE=${1:-release}

# Map common typos/variations for absolute CLI user-friendliness
if [ "$MODE" = "relese" ] || [ "$MODE" = "rel" ]; then
    MODE="release"
elif [ "$MODE" = "deb" ]; then
    MODE="debug"
fi

build_android() {
    local mode=$1
    echo "=== Building Android Companion App ($mode) ==="
    cd "$ANDROID_DIR"
    
    # Gradle Compatibility Check: Force JDK 17 if available on system to bypass Java 26 errors
    if [ -d "$HOME/jdk17" ]; then
        export JAVA_HOME="$HOME/jdk17"
    elif [ -n "$SUDO_USER" ] && [ -d "/home/$SUDO_USER/jdk17" ]; then
        export JAVA_HOME="/home/$SUDO_USER/jdk17"
    elif [ -d "/usr/lib/jvm/java-17-openjdk" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
    elif [ -d "/usr/lib/jvm/java-17" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-17"
    fi
    
    if [ -n "$JAVA_HOME" ]; then
        echo "Using Java JDK at: $JAVA_HOME"
    fi

    # Dynamically generate local.properties to avoid hardcoded absolute home paths
    if [ -d "$HOME/Android/Sdk" ]; then
        echo "sdk.dir=$HOME/Android/Sdk" > local.properties
    elif [ -n "$SUDO_USER" ] && [ -d "/home/$SUDO_USER/Android/Sdk" ]; then
        echo "sdk.dir=/home/$SUDO_USER/Android/Sdk" > local.properties
    fi

    # Make sure gradlew is executable
    chmod +x gradlew
    
    if [ "$mode" = "release" ]; then
        ./gradlew assembleRelease
        
        # Copy release APK to root
        local src_apk="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
        if [ ! -f "$src_apk" ]; then
            # Check other possible paths
            src_apk=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
        fi
        
        if [ -n "$src_apk" ] && [ -f "$src_apk" ]; then
            cp "$src_apk" "$SCRIPT_DIR/crimson-deck-2.0-release.apk"
            echo "✓ Release APK saved to: crimson-deck-2.0-release.apk"
        else
            echo "✖ Warning: Could not find generated release APK."
        fi
    else
        ./gradlew assembleDebug
        
        # Copy debug APK to root
        local src_apk="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
        if [ ! -f "$src_apk" ]; then
            src_apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
        fi
        
        if [ -n "$src_apk" ] && [ -f "$src_apk" ]; then
            cp "$src_apk" "$SCRIPT_DIR/crimson-deck-2.0-debug.apk"
            echo "✓ Debug APK saved to: crimson-deck-2.0-debug.apk"
        else
            echo "✖ Warning: Could not find generated debug APK."
        fi
    fi
}

case "$MODE" in
    debug)
        build_android "debug"
        echo "✓ Android debug build completed successfully!"
        ;;
    release)
        build_android "release"
        echo "✓ Android release build completed successfully!"
        ;;
    all)
        build_android "debug"
        build_android "release"
        echo "✓ All Android targets built successfully!"
        ;;
    *)
        print_usage
        exit 1
        ;;
esac
