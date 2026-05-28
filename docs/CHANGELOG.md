# Crimson Deck — Changelog

All notable changes to this project are documented here.  
Format: `[vX.Y] YYYY-MM-DD — Summary`

---

## [v2.1] 2026-05-28

### Android
- **Settings: About section** — Added About panel at the bottom of Settings with app name, description, and version number centered at the very bottom.
- **Version bump** — `versionCode 3`, `versionName 2.1`.
- **Input clip fix** — Touches in the control panel zone (below 70% safeHeight) are now fully blocked from routing to stream controls, regardless of where the video is panned. This fixes clicks/drags firing on the remote machine when interacting with the control panel while the stream is zoomed behind it.
- **Log deduplication (server)** — Repeated identical command log lines now collapse into a single `×N` count. High-frequency mouse move events are silenced entirely.

### Server (Go)
- **Stream freeze fix** — Installed a `SetPingHandler` on the stream WebSocket so OkHttp pings (every 30 s) reset the 90-second read deadline. Previously the deadline was set once at connect and never renewed, causing the stream to freeze and disconnect after exactly 90 seconds of no data frames from Android.
- **Port eviction** — Go server now runs `fuser -k PORT/tcp` at startup before binding, evicting any stale process (previous instance, zombie, etc.) that still holds the port. Eliminates the watchdog death-spiral (`address already in use` → exit 1 → respawn → repeat).
- **Dedup logger** — New `dedupLogger` collapses identical consecutive command log lines into one `×N` entry. Mouse absolute/relative move logs removed entirely (too high-frequency).

### Server (Rust)
- **Watchdog warning fixed** — Removed the `backoff_secs = 1` reset inside the watchdog respawn success branch. The doubled value was immediately overwritten, causing a Rust unused-assignment compiler warning. Backoff now correctly accumulates across crash cycles (1 s → 2 s → 4 s → … → 30 s).

---

## [v2.0] 2026-05-28

### System-wide robustness hardening (17 tasks: R1–R8, G1–G10, A1–A5)

### Android
- **A1 — Debounced reconnect** — `AgentViewModel` now uses a 2-second debounce before reconnecting to prevent rapid reconnect storms on transient network drops.
- **A2 — Config fetch retries** — Stream config fetched with 3 retries and 1-second back-off before stream connection is attempted.
- **A3 — OkHttp ping interval** — WebSocket clients set a 30-second OkHttp ping interval to keep connections alive through NAT and idle timeouts.
- **A4 — H264 decoder recovery** — `H264Decoder` catches `MediaCodec` errors and triggers an `onDecoderRecovered` callback that requests a keyframe from the server to reset the decode pipeline.
- **A5 — Subnet scan semaphore** — Network discovery capped at 16 concurrent scan coroutines via `Semaphore(16)` to prevent thread-pool exhaustion and OOM on large subnets.
- **Viewport clip fix** — `AndroidView` (TextureView) wrapped in a `Box` with `clipToBounds = true` and explicit `safeHeight` constraint so the stream cannot visually bleed behind the control panel when zoomed in and dragged down.

### Server (Go)
- **G1 — Non-blocking xdotool queue** — Commands are enqueued non-blocking; full queue drops the command with a warning instead of deadlocking the handler goroutine.
- **G2 — UDS reconnect loop** — `monitorAndProcessUDS` retries the Unix Domain Socket connection indefinitely with exponential back-off instead of exiting on first failure.
- **G3 — Payload size guard** — Incoming UDS frames rejected if `>` 10 MB to prevent memory spikes from malformed or oversized packets.
- **G4 — Dimension sanity check** — Stream width/height validated (8–7680 px range) before being applied to prevent divide-by-zero or nonsensical layout math.
- **G5 — HTTP read deadline** — `SetReadDeadline` on HTTP upgrade connections to prevent slow-loris-style resource exhaustion.
- **G6 — xdotool argument length cap** — Individual xdotool arguments capped at 4 096 characters to prevent shell injection or runaway subprocesses.
- **G7 — Sequential xdotool worker** — Single-goroutine worker drains the xdotool queue sequentially, guaranteeing mousedown/mouseup and keydown/keyup ordering.
- **G8 — HTTP server timeouts** — `ReadTimeout 30 s`, `WriteTimeout 120 s`, `IdleTimeout 60 s` on the `http.Server` to prevent resource leaks.
- **G9 — Graceful WebSocket cleanup** — All WebSocket handlers use `defer` to remove clients from the registry and close the connection, preventing ghost entries in `wsClients`.
- **G10 — Stream WebSocket deadline** — 90-second read deadline on stream WebSocket (later fixed in v2.1 to use `SetPingHandler`).

### Server (Rust)
- **R1 — Encoder error handling** — OpenH264/NVENC encode errors are logged and the encoder is reset rather than crashing the capture loop.
- **R2 — Task teardown** — Capture and command tasks are cancelled cleanly on UDS disconnect, releasing X11 MIT-SHM shared memory before exit.
- **R3 — Capture throttling** — Frame capture rate is capped and the loop yields on idle to prevent 100% CPU usage when no clients are connected.
- **R4 — IPC security guard** — UDS socket permissions set to `0o600`; connections from unexpected UIDs are rejected.
- **R5 — Go watchdog** — Rust spawns and monitors the Go gateway process; automatically respawns it with exponential back-off if it exits unexpectedly.
- **R6 — Graceful encoder shutdown** — Encoder flush and drain called before dropping resources on session teardown.
- **R7 — Config hot-reload** — Server config can be updated at runtime via IPC without restarting the capture loop.
- **R8 — MIT-SHM error handling** — X11 SHM attach/detach errors are caught and reported; the server falls back gracefully rather than segfaulting.

---

## [v1.0] — Initial Release

- Core screen capture via X11 MIT-SHM
- H.264 encoding with OpenH264
- WebSocket binary frame stream to Android
- Touch-to-mouse coordinate mapping
- i3 workspace switching
- Keyboard and mouse input injection via xdotool
- Cyberpunk themed Android UI with customisable colour presets
- Custom macro system with TOML/YAML/JSON import-export
- File manager (browse, upload, download, mkdir)
- Tailscale VPN auto-detection for server IP
