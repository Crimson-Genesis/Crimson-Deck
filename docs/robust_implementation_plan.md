# Crimson Deck — Robustness Implementation Plan

> **Status**: In Progress  
> **Scope**: Server (Rust + Go) + Android (Kotlin/Compose)

---

## Audit Summary — Identified Fragility Points

### 🦀 Rust Capture Daemon (`server/rust/src/`)

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| R1 | `main.rs:659,765` | `openh264::Encoder::with_config(...).unwrap()` — panics on init failure | **CRITICAL** |
| R2 | `main.rs:690,796` | `frame_count % 300` periodic keyframe — no adaptive recovery if stream stalls | Medium |
| R3 | `main.rs:728,834` | Frame timing: `elapsed as u64` cast can wrap; integer division truncates | Low |
| R4 | `main.rs:556-615` | `command_task` panic propagates and kills entire session without recovery | **CRITICAL** |
| R5 | `main.rs:621-848` | Capture task: a single X11/Wayland error loops `error!()` at full speed — CPU spin | **HIGH** |
| R6 | `main.rs:545-550` | Go child spawn: `_go_child` result is ignored; crash/exit of Go process is undetected and never restarted | **HIGH** |
| R7 | `main.rs:481-521` | Go binary source check only compares `main.go` mod time — ignores `go.mod`/`go.sum` changes | Low |
| R8 | `ipc/uds.rs:127` | Command length guard `> 65536` — too permissive; malformed packets can allocate 64KB | Low |
| R9 | `main.rs:850-853` | Accept error: only sleeps 1s and retries — no backoff, no distinguish between transient/fatal | Low |

### 🐹 Go Gateway Server (`server/go/main.go`)

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| G1 | `main.go:189` | `xdotoolQueue` channel size 1000 — if worker stalls (xdotool hangs), queue fills and `<-` send blocks | **HIGH** |
| G2 | `main.go:287-294` | `startXdotoolWorker` — xdotool command has no timeout; a hung xdotool blocks all subsequent input | **CRITICAL** |
| G3 | `main.go:466-495` | `monitorAndProcessUDS`: no exponential backoff — hammers UDS path at 1s intervals during Rust startup | Medium |
| G4 | `main.go:503-509` | `startPipelineBroadcaster` — `pipeline` channel is unbuffered between goroutines; if broadcaster falls behind, the frame reader blocks | Medium |
| G5 | `main.go:543-545` | `width == 0 \|\| height == 0 \|\| width > 7680` sanity check returns error, killing UDS connection on one bad frame | Medium |
| G6 | `main.go:567` | `payload := make([]byte, payloadLen)` — no max size guard; a corrupted 4B length field could allocate GBs | **CRITICAL** |
| G7 | `main.go:1291` | Path traversal check `strings.HasPrefix(targetFile, dest)` — can fail on symlinks or case-sensitivity edge cases | Medium |
| G8 | `main.go:369` | `http.ListenAndServe` — no read/write timeouts on HTTP server; slow clients can hold connections forever | Low |
| G9 | `main.go:905-929` | `forwardCommandToUds` — no write deadline set; UDS write can block indefinitely if Rust stalls | **HIGH** |
| G10 | `main.go:716-720` | `/stream` WebSocket keep-alive reader — no read deadline; stale connections accumulate | Medium |

### 📱 Android Client (`android/app/`)

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| A1 | `AgentViewModel.kt:134` | `HttpURLConnection` for stream config — no retry; if server isn't ready yet, config is silently lost | Medium |
| A2 | `AgentViewModel.kt:372-411` | `WebSocketManager` — reconnect on `onConnectionStateChanged(false)` is triggered from `StreamScreen.kt:81` LaunchedEffect with no debounce; rapid reconnect storms possible | **HIGH** |
| A3 | `WebSocketManager.kt` | (needs review) — OkHttp WebSocket failure handling | TBD |
| A4 | `H264Decoder.kt` | (needs review) — MediaCodec error/timeout handling | TBD |
| A5 | `AgentViewModel.kt:780-840` | Subnet scan: 254 coroutines launched at once with 300ms TCP timeout each — can overwhelm Android's thread pool | Medium |
| A6 | `StreamScreen.kt:93-98` | `scale`/`offset` state reset: no animation on zoom reset (double-tap empty space) — jarring UX | Low |
| A7 | `AgentViewModel.kt:116-118` | `lastFrameTimes`/`lastFrameSizes` — `synchronized(frameLock)` on main thread from `trackFrame()` called on IO thread; potential deadlock if GC pauses | Low |

---

## Implementation Plan

### Phase 1 — Critical Server Fixes (Rust)

#### R1 — Replace encoder `.unwrap()` with graceful error handling
- Replace both `openh264::Encoder::with_config(...).unwrap()` calls with `?`/match — log and skip frames instead of panicking.

#### R4 — Panic-safe command task
- Wrap `command_task` in `tokio::spawn` with `catch_unwind` or use `JoinHandle::await` + pattern match on `Err(panic_payload)` to log and continue the accept loop.

#### R5 — Capture error throttling (X11/Wayland spin guard)
- After 5 consecutive capture errors, sleep 500ms before retrying. Reset error counter on successful frame.

#### R6 — Go child process watchdog
- Store `Child` handle, spawn a background `tokio::task` that awaits `child.wait()`, logs exit code/signal, and respawns the Go process with exponential backoff (1s, 2s, 4s, max 30s).

### Phase 2 — Critical Server Fixes (Go)

#### G2 — xdotool command timeout
- Wrap each `exec.Command` in `CommandContext` with a 5-second timeout. If it exceeds, kill and log.

#### G6 — Payload size guard
- Add `if payloadLen > 32*1024*1024 { return fmt.Errorf("oversized payload: %d", payloadLen) }` before allocating.

#### G9 — UDS write deadline
- Set `state.udsConn.SetWriteDeadline(time.Now().Add(2 * time.Second))` before each write in `forwardCommandToUds`.

#### G1 — Non-blocking xdotool queue send
- Change queue send from blocking `<-` to `select { case xdotoolQueue <- args: default: log.Println("xdotool queue full, dropping command") }`.

### Phase 3 — Medium Server Fixes

#### G3 — UDS reconnect backoff
- Implement exponential backoff: start at 250ms, double each failure, cap at 8s, reset on success.

#### G5 — Bad frame skip (not kill)
- On invalid frame dimensions, skip that frame (`io.CopyN(io.Discard, ...)`) instead of returning an error that tears down the connection.

#### G8 — HTTP server timeouts
- Wrap `http.ListenAndServe` with a `http.Server{ReadTimeout: 30s, WriteTimeout: 120s, IdleTimeout: 60s}`.

#### G10 — Stream WS read deadline
- Set `conn.SetReadDeadline(time.Now().Add(90 * time.Second))` and reset it on each message.

### Phase 4 — Android Client Fixes

#### A2 — Reconnect debounce
- Add a `reconnectDebounceJob` in `AgentViewModel` with a 1.5s delay before re-triggering `connectToWorkstation`, cancelling any pending reconnect if a new one comes in.

#### A3 — WebSocketManager review and hardening
- Add configurable `pingInterval` (30s) on OkHttp WebSocket to detect stale connections.
- Add exponential backoff on reconnect: 1s → 2s → 4s → 8s → 16s (max).

#### A4 — H264Decoder error recovery
- Catch `MediaCodec.CodecException`, log it, and trigger a decoder reset + keyframe request instead of silently failing.

#### A1 — Stream config retry
- Retry `syncStreamingConfig()` up to 3 times with 500ms delay if the server returns non-200 or throws.

#### A5 — Subnet scan parallelism limit
- Use a `Semaphore(16)` to cap concurrent TCP probe coroutines at 16, preventing thread pool exhaustion.

---

## Files to Modify

```
server/rust/src/main.rs         — R1, R4, R5, R6, R2
server/rust/src/ipc/uds.rs      — R8
server/go/main.go               — G1, G2, G3, G5, G6, G8, G9, G10
android/.../AgentViewModel.kt   — A1, A2, A5
android/.../WebSocketManager.kt — A3
android/.../H264Decoder.kt      — A4
```

---

## Completion Checklist

- [x] Viewport clip fix (TextureView/safeHeight) — done before this plan
- [x] R1 — Encoder unwrap → graceful error (`match Encoder::with_config` in both Wayland + X11 branches)
- [x] R4 — Command task panic-safe (`match command_task.await` + `capture_task.abort()`)
- [x] R5 — Capture error throttle (100ms sleep after each capture error)
- [x] R6 — Go child watchdog + respawn (exponential backoff 1→2→4→…→30s)
- [x] R8 — UDS command length guard tightened (65536 → 16384)
- [x] G1 — Non-blocking xdotool queue (select/default drop with log)
- [x] G2 — xdotool timeout (CommandContext 5s per command)
- [x] G3 — UDS reconnect backoff (250ms → 8s exponential, resets on connect)
- [x] G5 — Bad frame skip (io.CopyN Discard instead of returning error)
- [x] G6 — Payload size guard (32MB cap before allocation)
- [x] G8 — HTTP server timeouts (ReadTimeout 30s, WriteTimeout 120s, IdleTimeout 60s)
- [x] G9 — UDS write deadline (2s SetWriteDeadline before every write)
- [x] G10 — Stream WS read deadline (90s, renewed on each message)
- [x] A1 — syncStreamingConfig retry (3 attempts, 500ms delay)
- [x] A2 — Reconnect debounce (1.5s debouncedReconnect in StreamScreen + ViewModel)
- [x] A3 — WebSocketManager ping + exponential backoff reconnect (1s→2s→4s→8s→16s)
- [x] A4 — H264Decoder error recovery (CodecException handling, cooldown guard, onDecoderRecovered → keyframe)
- [x] A5 — Subnet scan parallelism cap (Semaphore(16), timeout 150ms)
