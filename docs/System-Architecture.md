# GitLab Wiki: System Architecture

Crimson Deck (crimson-deck) implements a robust, secure, multi-process architecture to decouple high-privilege systems interactions from low-privilege network inputs.

---

## 1. Multi-Process Division of Labor

The host architecture consists of two cooperative servers:

```
+--------------------------------------------------------------+
|                     Workstation Host                         |
|                                                              |
|   +--------------------------+    +----------------------+   |
|   |       Rust Engine        |    |      Go Gateway      |   |
|   |  (Privileged/Root Daemon)|    |   (Standard User)    |   |
|   +-------------+------------+    +-----------+----------+   |
|                 |                             |              |
|                 |     Unix Domain Socket      |              |
|                 +=====> /tmp/crimson-deck.sock <====+              |
|                               |                              |
|                               v                              |
|                      WebSocket Interface                     |
|                      (JSON & Binary data)                    |
|                               |                              |
+-------------------------------|------------------------------+
                                |
                                v
                      +-------------------+
                      |   Android Phone   |
                      |   (Kotlin Client) |
                      +-------------------+
```

### A. The Rust Systems Engine (`crimson-deck-server`)
* **Role**: Primary orchestrator, low-level screen capture driver (MIT-SHM or PipeWire), and virtual input emulator.
* **Privileges**: Booted with root privileges (required to interact directly with `/dev/uinput` and PipeWire system session portals).
* **Process Security**:
  * **Privilege Dropping**: Once started, the Rust daemon drops its process execution credentials to spawn the Go gateway server under the standard user's UID and GID (parsed from `SUDO_UID` and `SUDO_GID`).
  * **Environment Isolation**: Configures standard user environments (`HOME`, `USER`, `DISPLAY=:0`, and `XAUTHORITY`) before spawning the gateway, ensuring the Go process can interact with X11 displays and user config directories.

### B. The Go Network Gateway (`server/go/server`)
* **Role**: High-speed network gateway, REST API platform, and secure WebSocket server.
* **Privileges**: Runs under standard user privileges.
* **Responsibilities**:
  * Exposes port `9090` to listen for network handshakes, settings, and file uploads.
  * Translates incoming WebSocket JSON events into high-performance commands mapped to user-space tools like `xdotool` and `xclip`.
  * Manages high-performance CGO standard image encodings and relays video streams directly down binary WebSocket channels.

---

## 2. Unix Domain Socket Layer (`/tmp/crimson-deck.sock`)

The Rust engine and Go gateway communicate locally via a high-speed Unix Domain Socket (`/tmp/crimson-deck.sock`) configured with `0666` permission bits to allow cross-privilege communication.

### A. Thread-Safe Socket Splitting
To prevent deadlock conditions under high throughput, the accepted socket connection is split into owned halves:
```rust
let (mut read_half, mut write_half) = stream.into_split();
```
* **Command Task (`read_half`)**: Blocks asynchronously on incoming commands sent from Go, processing input emulation events immediately.
* **Capture Task (`write_half`)**: Streams compressed H.264 video frame buffers to the Go client at a continuous 60 FPS without ever locking the read task.
* **Deadlock Prevention**: Decoupling read/write operations into lock-free stream halves guarantees that high-motion screen updates never starve keyboard/mouse commands.

### B. Frame Stream Byte Protocol (Rust to Go)
Video frames are serialized into binary packets containing a 20-byte big-endian header:
```
+------------------+-------------------+----------------------+--------------------+--------------------+
| Width [0-3 Bytes]| Height [4-7 Bytes]| Timestamp [8-15 Bytes]| Payload [16-19 B]  | Raw Frame Bytes... |
+------------------+-------------------+----------------------+--------------------+--------------------+
```
* **Width**: 4 Bytes (u32)
* **Height**: 4 Bytes (u32)
* **Timestamp**: 8 Bytes (u64)
* **Payload Length**: 4 Bytes (u32)

### C. Command Packet Protocol (Go to Rust)
Command events are serialized as `[Length: u32][JSON Payload]` packets. The length prefix prevents network framing buffer merges:
```json
{
  "type": "key",
  "key_code": 30,
  "pressed": true
}
```

---

## 3. Execution Control & Command Queues
To resolve race conditions and key-repeat bugs from concurrent terminal command execution, the Go gateway leverages a FIFO command pipeline:
* All incoming remote interaction packets are pushed into a thread-safe Go channel (`xdotoolQueue`).
* A single background worker goroutine (`startXdotoolWorker()`) reads from the channel and executes `xdotool` commands sequentially.
* This guarantees that keystroke presses and releases (e.g. `keydown` and `keyup`) are processed in their exact sequential order, eliminating stuck modifier keys.
