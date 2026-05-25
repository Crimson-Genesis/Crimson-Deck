# GitLab Wiki: Getting Started

This guide details how to build, install, and execute the Crimson Deck (`crimson-deck`) remote control environment on your Linux host workstation and Android companion device.

---

## 1. Host Workstation Installation

The workstation server is divided into a high-performance Rust systems engine (`crimson-deck-server`) and a concurrent Go network signaling gateway. It supports both traditional **X11** and modern **Wayland** display compositors.

### A. Install System Dependencies
Install the required packages using your system package manager. On Arch Linux, execute:

```bash
# 1. Install developer toolchains and compiler runtimes
sudo pacman -S base-devel go rust adb

# 2. Install command utilities and clipboard sharing
sudo pacman -S xdotool xclip

# 3. Add your active standard user to the adbusers group for USB debugging permissions
sudo usermod -aG adbusers $USER

# 4. Enable and start the ADB system daemon
sudo systemctl enable --now adb
```

### B. Configure Host Compositor Settings
Open the server configuration file at [server/config.json](server/config.json) to set your active window manager commands:

* **For X11 (i3wm)**:
  Configure `window_manager_cmd` to target `i3-msg`:
  ```json
  {
    "network_port": "9090",
    "uds_socket_path": "/tmp/crimson-deck.sock",
    "window_manager_cmd": "i3-msg"
  }
  ```
* **For Wayland (Sway)**:
  Configure `window_manager_cmd` to target `swaymsg` to route commands over Wayland PipeWire portals natively:
  ```json
  {
    "network_port": "9090",
    "uds_socket_path": "/tmp/crimson-deck.sock",
    "window_manager_cmd": "swaymsg"
  }
  ```

---

## 2. Building & Running the Host Server

### A. Compile the Server Binaries
Use the built-in workspace compilation script to compile both the Rust engine and the Go gateway:

```bash
# Compile and package optimized release binaries
./build_server.sh release
```
* **Output Path**: The script compiles the binaries and automatically copies the optimized systems engine to the root directory as **`crimson-deck-server`**.
* **Hot Rebuilds**: The Rust engine dynamically monitors the Go gateway code (`server/go/main.go`) and automatically recompiles it at startup if any newer changes are detected on disk.

### B. Launching in X11 Mode
X11 capture leverages the ultra-low latency **MIT-SHM (Shared Memory)** extension to grab full screen frame buffers in under 3ms.

1. **Verify Session Environment**:
   Ensure you are logged into your active X11 user session. Verify your active display pointer:
   ```bash
   echo $DISPLAY # Should output :0
   ```
2. **Execute the Daemon**:
   Launch the compiled binary with standard root privileges (required for virtual system keyboard/mouse emulations under `/dev/uinput`):
   ```bash
   sudo ./crimson-deck-server
   ```
   * *Note: The Rust engine automatically captures your active user context (`SUDO_UID` and `SUDO_GID`) and drops privileges to run the Go gateway as your standard user, ensuring secure X11 authority (`.Xauthority`) authentication.*

### C. Launching in Wayland Mode
Wayland capture utilizes modern **PipeWire** portal APIs to grab display frame buffers securely under compositors like Sway.

1. **Execute the Daemon**:
   Launch the compiled binary with the Wayland flag:
   ```bash
   sudo ./crimson-deck-server --wayland
   ```
2. **Monitor Server Log Outputs**:
   All log streams are written relative to the execution binary at `logs/YYYY-MM-DD_HH-MM-SS.log` containing consolidated events from both the Rust capturer and Go network server.

---

## 3. Building & Deploying the Android Client

The Android companion application is a Kotlin-based Jetpack Compose client that handles high-speed video rendering directly inside GPU hardware using native Android `MediaCodec` surface decoders.

### A. Prepare Your Android Device
1. Connect your Android phone to your workstation via a USB cable.
2. Navigate to **Settings > About Phone** and tap **Build Number** seven times to enable **Developer Options**.
3. Go to **Developer Options** and enable **USB Debugging**.
4. Keep the device screen unlocked and approve the **USB Debugging Authorization Dialog** when prompted.

### B. Compile and Install over USB
Deploy the application directly to your phone with a single workspace script:

```bash
# Compile and install the optimized release app directly on the connected phone
./build_and_install_android.sh release
```
* **Java 26 Compatibility**: The build script automatically overrides `JAVA_HOME` to target JDK 17 (scanning `~/jdk17` and local OpenJDK paths), bypassing compiler failures under newer system-default Java 26 runtimes.
* **Release Signing Key Parity**: The Gradle script signs the release build using local debug signatures. This generates the `installRelease` task and allows ADB to push the optimized release package straight to the USB-connected phone without signing conflicts.
* **APK Backups**: A standalone copy of the compiled installer package is backed up to the project root directory as **`crimson-deck-2.0-release.apk`**.

---

## 4. Launching the App & Establishing a Connection

1. Open the **Crimson Deck** application from your phone's app drawer (identified by the custom deep-obsidian and neon-crimson hexagonal logo).
2. Ensure both the phone and the workstation are on the same local Wi-Fi network (or linked via a active Tailscale VPN tunnel).
3. **Auto-Discovery**:
   The home screen will automatically scan your local `/24` Wi-Fi subnet, MagicDNS namespaces (`genesis`, `genesis.tailscale.net`), and persistent history lists to discover the active workstation.
4. **Establish the Link**:
   Select your discovered workstation from the carousel or manually input its IP/Hostname, and tap **CONNECT**.
5. **Silky-Smooth 60 FPS Viewport**:
   * The app will transition to the high-performance viewing canvas, instantly pulling an H.264 I-frame the moment the TextureView surface becomes active.
   * Pressing the system back gesture will cleanly tear down the video stream socket, stopping server capture encoding and providing **silky-smooth, lag-free back navigation transitions**.

