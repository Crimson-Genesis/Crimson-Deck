# System Robustness and Troubleshooting

This document outlines the operational boundaries, failure vectors, edge cases, and mitigation strategies for the Crimson Deck (`crimson-deck`) remote control environment.

---

## 1. Host-Side Display Environment Configuration

The input emulation, command injection, and keyboard macro runners rely directly on access to the active user's X11 or Wayland display server sessions.

### Failure Vectors
* **Display Server Index Shifts**: The Go signaling gateway assumes a default display target (`DISPLAY=:0`). If the host system dynamically shifts the active graphical session index (e.g., launching another graphical server or spawning a nesting display shifts the target session to `:1`), input emulation and keystrokes will not be injected into your active desktop.
* **X11 Authority Token Expiration**: Input injection and screen capturing under X11 require a valid `.Xauthority` session token matching the standard active user. If the user session logs out or authorization tokens are regenerated, connection commands will fail with a `Protocol Error` or `Connection Refused`.
* **Window Manager Mismatches**: The workspace switches and fullscreen macros utilize the active window manager command tool (`i3-msg` or `swaymsg`). If the `window_manager_cmd` setting inside `server/config.json` is set to `i3-msg` but you are running a Wayland session (Sway), or vice versa, workspace navigation commands will fail to execute.

### Mitigations
* **Config Verification**: Ensure the `window_manager_cmd` setting inside `server/config.json` matches your active session compositor (`i3-msg` for i3 on X11; `swaymsg` for Sway on Wayland).
* **Verify DISPLAY Variables**: If executing from custom terminals or SSH shells, verify that the `DISPLAY` environment variable is explicitly exported before starting the server (e.g., `export DISPLAY=:0`).

---

## 2. Dynamic Resolution and Monitor Hotplugging

The H.264 stream is generated based on the physical screen boundaries captured on the host. The Android companion app leverages hardware-accelerated MediaCodec surfaces to decode and render the stream.

### Failure Vectors
* **Dynamic Monitor Adjustments**: Connecting or disconnecting external monitors (such as plugging in an HDMI display or changing active workspace display bounds via `xrandr` or `wlr-randr`) dynamically changes the capture resolution on the host (e.g., swapping from 1080p to a 4K viewport bounds).
* **Android Decoder State Exceptions**: Android's native `MediaCodec` hardware decoders are initialized with a fixed resolution width and height bound to the active video format. Sudden resolution changes in the middle of a continuous H.264 stream will cause the mobile hardware decoder to throw state exceptions, leading to screen freezes at 0 FPS.

### Mitigations
* **Session Lifecycle Reset**: We release the `MediaCodec` resources on disconnect. If a monitor hotplug occurs during a session, simply exit the stream viewport to the home screen (ConnectScreen) and re-enter. This completely tears down the previous decoder state and initializes a fresh decoder matching the new resolution bounds instantly.

---

## 3. Daemon Execution Context & Privilege Dropping

To access X11 shared memory segment buffers (`MIT-SHM`) and inject virtual system commands securely without broad root permissions, the Rust daemon drops privileges after startup.

### Failure Vectors
* **Missing SUDO Environment Variables**: The Rust binary (`crimson-deck-server`) must be launched with root privileges (`sudo`) to bind system capture and display components. Once initiated, the Rust engine reads `SUDO_UID` and `SUDO_GID` to drop its privileges and execute the Go signaling server as the standard non-root user. If the Rust daemon is executed directly from a root terminal session (where `SUDO_UID` is missing) or from an automated cron task lacking environment bindings, the privilege dropping logic will fail.
* **Unix Domain Socket Locks**: Starting the server without dropping privileges or with invalid user mappings can create the UDS socket (`/tmp/crimson-deck.sock`) with restricted permissions (e.g., locked strictly to root). When a standard user subsequently attempts to launch or connect the Go server, it will fail with a `Permission Denied` socket write exception.

### Mitigations
* **Standard Sudo Startup**: Always start the backend using standard sudo wrappers: `sudo ./crimson-deck-server`. This ensures the correct user ID environment variables are populated.
* **Socket Cleanup**: If the server fails to start due to socket locks, clean up historical locks manually: `sudo rm -f /tmp/crimson-deck.sock`.

---

## 4. Local Subnet and DHCP Routing Changes

The companion app maintains real-time WebSocket connectivity directly over your local area network (LAN) or virtual private network (VPN).

### Failure Vectors
* **DHCP IP Reassignments**: Routers frequently re-assign local IP addresses to host workstations on Wi-Fi connection refreshes. If the host workstation's local IP changes (e.g., re-assigned from `192.168.1.10` to `192.168.1.15`), active connections will drop and history entries will become obsolete.
* **Subnet Mismatches**: If the phone exits Wi-Fi coverage or transitions to mobile carrier data networks, the local LAN subnets will be unreachable, dropping active streams.

### Mitigations
* **Tailscale Integration**: We highly recommend running the connection over a Tailscale overlay VPN. Our companion app is equipped with robust MagicDNS lookups that dynamically resolve `genesis` or `genesis.tailscale.net` even if local DHCP subnets shift, maintaining seamless routing across any network boundary.
* **Workstation Subnet Scan**: Leverage the built-in home screen workstation scanner to dynamically search and locate active servers on your current Wi-Fi subnets.

---

## 5. Dependency Availability

The system relies on lightweight native CLI executables to perform heavy graphical and automation interactions instead of bloated binary libraries.

### Failure Vectors
* **Missing Utilities**: If native workstation tools (such as `xdotool`, `xclip`, or window manager IPC messaging tools) are uninstalled, commands and sequences will immediately drop without execution.

### Mitigations
* **Verify System Packages**: Ensure all host-side dependencies are fully installed on Arch Linux:
  ```bash
  sudo pacman -S xdotool xclip i3-wm sway
  ```
