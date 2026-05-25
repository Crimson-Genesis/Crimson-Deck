# Crimson Deck (crimson-deck) Wiki Home

Welcome to the Crimson Deck (crimson-deck) GitLab Wiki. This project is a high-performance, low-latency remote control and developer companion system. It enables developers and power users to control Arch Linux workstations (supporting both X11 and Wayland) directly from a physical Android device at 60 FPS with sub-frame latency.

---

## Key Capabilities

* **Lag-Free 60 FPS Video Stream**: Leverages highly optimized X11 MIT-SHM and Wayland PipeWire capture engines, combined with H.264 video compression (utilizing SIMD-accelerated software encoding or hardware-accelerated NVIDIA NVENC).
* **Direct Surface Hardware Decoding**: The Android client decodes incoming video packets directly inside GPU hardware via Android MediaCodec Surface buffers, minimizing latency and maintaining high battery efficiency.
* **Pure User-Space Input Emulation**: Mimics keyboard and mouse inputs inside X11/Wayland display servers using xdotool and xclip, bypassing kernel-level /dev/uinput privileges for standard interactions.
* **i3wm Unix IPC Socket Integration**: Switch active workspaces (1-12) from the phone. The system locates and commands the active i3wm instance directly via its local Unix IPC socket.
* **Cyberpunk Command Center UI**: Built using Jetpack Compose with responsive neon custom themes, custom hex colors, real-time connection state metrics, monospaced stats containers, and ergonomic developer layout overlays.
* **Ergonomic Pointer Controls**: Features absolute touch coordinates, Relative Trackpad navigation, zoom-to-centroid viewport panning with edge-locking, and long-press drag-selection emulations.
* **Automated Macro Sequences**: Sequence sequential strokes (e.g. `l, s, enter`) or concurrent key combos (e.g. `ctrl+alt+t`) with custom execution delays, alphanumeric scan code resolution, and shift operator parsers.
* **Multi-Format Macro Backup & Server Sync**: Import and export custom macro sheets in JSON, TOML, and YAML formats, and sync them bidirectionally over dedicated REST APIs.

---

## Modular System Flow

```mermaid
flowchart TD
    subgraph Workstation Host (Arch Linux)
        capture_engine[Rust Engine: X11 SHM / Wayland PipeWire]
        uds_srv[Rust Engine: UDS Server - /tmp/crimson-deck.sock]
        go_uds[Go Gateway: UDS Client]
        go_srv[Go Gateway: HTTP / WS Server]
        go_emul[Go Gateway: xdotool / xclip / i3 IPC]
    end
    
    subgraph Android Mobile Client (Kotlin)
        ws_client[Kotlin: WebSocketManager]
        surface_decoder[Kotlin: H264Decoder Surface]
        viewmodel[Kotlin: AgentViewModel]
        compose_ui[Kotlin: Compose Cyberpunk View]
    end

    %% Video data flow
    capture_engine -->|Compresses H.264| uds_srv
    uds_srv ===>|UDS Frame Socket| go_uds
    go_uds -->|Pipe WS Binary Frame| go_srv
    go_srv -->|WebSocket Binary Channel| ws_client
    ws_client -->|Direct Native Feed| surface_decoder
    surface_decoder -->|Direct GPU Render| compose_ui
    
    %% Input control flow
    compose_ui -->|Gestures / Keypresses / Macros| viewmodel
    viewmodel -->|JSON Websocket Message| go_srv
    go_srv -->|Executes Emulator Commands| go_emul
    go_emul -->|X11 Protocol / IPC Socket| x11_display[Active Linux Desktop Session]
```

---

## Wiki Navigation Pages

Select a page below to explore the documentation:

1. **[Getting Started](Getting-Started.md)**: Compile and deploy the host server and Android client in seconds.
2. **[System Architecture](System-Architecture.md)**: Explore the privilege-dropping Rust system engine, UDS messaging layer, and Go network gateway.
3. **[Video Streaming Engine](Video-Streaming-Engine.md)**: Learn how we achieve 60 FPS sub-frame video streaming using H.264 compression and hardware Surface decoders.
4. **[Input Emulation Details](Input-Emulation.md)**: Deep dive into xdotool coordinate mapping, key scan codes, clipboard sharing, and i3wm integrations.
5. **[Macros and Automation](Macros-and-Automation.md)**: Detailed specifications for sequential coroutine sequences, custom serializers (YAML/TOML/JSON), and REST sync portals.
6. **[System Robustness and Troubleshooting](System-Robustness-and-Troubleshooting.md)**: Detailed analysis of environmental failure vectors, dynamic display hotplugging, privilege-dropping, and network shifting.
