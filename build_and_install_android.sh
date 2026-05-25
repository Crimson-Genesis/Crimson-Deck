#!/bin/bash
set -e

# Resolve script directory to allow running from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/android"

MODE=${1:-release}

# Map common typos/variations for absolute CLI user-friendliness
if [ "$MODE" = "relese" ] || [ "$MODE" = "rel" ]; then
    MODE="release"
elif [ "$MODE" = "deb" ]; then
    MODE="debug"
fi

echo "=== Android Companion App Build & Install Console ($MODE) ==="

# 1. Verify ADB is installed and a device is connected
if ! command -v adb &> /dev/null; then
    echo "✖ Error: 'adb' tool is not installed or not in PATH."
    echo "  Please install Android SDK Platform Tools to continue."
    exit 1
fi

echo "Checking for connected Android devices..."
ADB_DEVICES=$(adb devices | tail -n +2 | grep -v "^$")

if [ -z "$ADB_DEVICES" ]; then
    echo "✖ Error: No connected Android devices detected."
    echo "  Please connect your phone, enable USB Debugging in Developer Options, and try again."
    exit 1
fi

echo "Connected device(s) found:"
echo "$ADB_DEVICES"
echo ""

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

# 2. Build and Install using Gradle wrapper
cd "$ANDROID_DIR"

# Dynamically generate local.properties to avoid hardcoded absolute home paths
if [ -d "$HOME/Android/Sdk" ]; then
    echo "sdk.dir=$HOME/Android/Sdk" > local.properties
elif [ -n "$SUDO_USER" ] && [ -d "/home/$SUDO_USER/Android/Sdk" ]; then
    echo "sdk.dir=/home/$SUDO_USER/Android/Sdk" > local.properties
fi

# Ensure gradlew is executable
chmod +x gradlew

if [ "$MODE" = "release" ]; then
    echo "=== Initiating Gradle Build & Direct USB Installation (RELEASE) ==="
    ./gradlew installRelease
    
    # Export a copy of the release APK to the root folder
    local_apk="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ ! -f "$local_apk" ]; then
        local_apk=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
    fi

    if [ -n "$local_apk" ] && [ -f "$local_apk" ]; then
        cp "$local_apk" "$SCRIPT_DIR/crimson-deck-2.0-release.apk"
        echo "✓ Success! Backup release APK saved to root as: crimson-deck-2.0-release.apk"
    fi
else
    echo "=== Initiating Gradle Build & Direct USB Installation (DEBUG) ==="
    ./gradlew installDebug
    
    # Export a copy of the debug APK to the root folder
    local_apk="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$local_apk" ]; then
        local_apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
    fi

    if [ -n "$local_apk" ] && [ -f "$local_apk" ]; then
        cp "$local_apk" "$SCRIPT_DIR/crimson-deck-2.0-debug.apk"
        echo "✓ Success! Backup debug APK saved to root as: crimson-deck-2.0-debug.apk"
    fi
fi

echo ""
echo "✓ Success! Companion app built and successfully installed on your phone."
echo "You can now launch the 'Crimson Deck' app on your device!"
