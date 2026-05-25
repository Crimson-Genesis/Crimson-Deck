# GitLab Wiki: Video Streaming Engine

Crimson Deck (crimson-deck) achieves a smooth, real-time 60 FPS remote desktop feed over local and Tailscale networks by orchestrating custom capture pipelines, high-performance encoders, and native Android GPU rendering.

---

## 1. High-Performance Screen Capture

The Rust systems engine contains dual capture modules selected dynamically based on your display server environment.

### A. X11 Capture (MIT-SHM)
When targeting standard X11 environments, the capturer utilizes the MIT Shared Memory Extension (MIT-SHM) through safe Rust `xcb` bindings:
* **Mechanism**: Rather than copying pixels across process boundaries via standard network sockets, the X11 server and Rust engine share a mapped segment of system RAM (SHM segment).
* **Speed**: Screen capture times are cut to less than 2 milliseconds for a full 1080p display, completely avoiding memory-copying overhead.

### B. Wayland Capture (PipeWire Portals)
When booted with the `--wayland` flag, the capturer links directly to the PipeWire multimedia pipeline:
* **Mechanism**: Leverages low-latency screen sharing portals to capture desktop composition streams natively from the Wayland compositor.
* **Format**: Captures high-frequency buffer segments directly into system memory, ensuring compatibility with modern Linux compositors.

---

## 2. Low-Latency H.264 Video Compression

Raw 1080p video frames require massive bandwidth (around 500 MB/s at 60 FPS), which would congest network links immediately. The system implements highly optimized video encoding pipelines to shrink payloads.

### A. SIMD-Accelerated YUV420p Converter
H.264 video encoders require raw input frames in the YUV420p pixel format rather than standard RGB/BGRA.
* **Problem**: Standard float-point conversion matrices require significant CPU overhead, creating processing bottlenecks.
* **Solution**: Implemented an optimized integer-based YUV conversion buffer (`FullRangeYUVBuffer`) using fast bit-shifting operations:
  * `Y = ((77 * R + 150 * G + 29 * B) >> 8)`
  * `U` and `V` values are subsampled from 2x2 blocks of RGB pixels.
  * This structure enables compilers to leverage SIMD (AVX2/AVX-512) auto-vectorization, dropping full conversion times from 18ms down to less than 3 milliseconds.

### B. Dual Compression Profiles

#### Software Mode (OpenH264)
* Uses the Cisco `openh264` library compiled natively into the Rust engine.
* Configured with low-complexity parameters, a high target bitrate of 5 Mbps, and disabled frame-skipping (`enable_skip_frame(false)`) to guarantee a smooth, continuous frame feed.

#### Hardware Mode (NVIDIA NVENC)
* Automatically checks for NVIDIA graphics cards and launches an optimized, low-latency FFmpeg process utilizing `-c:v h264_nvenc`.
* **Bitrate and Rate Control**: Employs constant bitrate low delay rate control (`-rc cbr_ld_hq`) to eliminate latency spikes during high-motion frames:
  * Target Bitrate: 3 Mbps (`-b:v 3M`)
  * Maximum Bitrate: 4 Mbps (`-maxrate 4M`)
  * Small Buffer Size: 256 KB (`-bufsize 256K`) to prevent buffer queues.
  * Extended Keyframe Interval: Scaled to 300 frames (5 seconds) to prevent heavy periodic network bursts from I-frames.

---

## 3. Drift-Compensated Pacing

To maintain an exact 60 FPS capture loop without drifts or accumulation lag, the Rust engine implements a drift-compensated pacing algorithm:
* Rather than sleeping for a static 16.6ms, the loop dynamically measures the exact processing time of the current frame (capture, YUV convert, compress, and send).
* Subtracts this processing time from the target frame budget (16,666 microseconds) and sleeps for the precise remaining duration.
* If a frame takes longer than 16.6ms, the pacing compensation reduces subsequent sleep cycles to catch up immediately, ensuring clock-accurate 60 FPS output.

---

## 4. GPU-Driven Surface Rendering (Android)

On the Android companion device, raw H.264 byte streams are parsed and decoded entirely inside the mobile device's graphics hardware.

```
Incoming WebSockets H.264 Stream
             │
             ▼
   Kotlin WebSocketManager
             │
             ▼
   H264Decoder (MediaCodec)
             │ (Direct Hardware Pipe)
             ▼
  Android GPU Graphics Memory
             │
             ▼
    TextureView Surface
  (Direct GPU Display Render)
```

### A. Zero-Copy MediaCodec Decoder
* Incoming H.264 byte segments are pushed straight into the native Android `MediaCodec` decoder queue inside a specialized background coroutine thread.
* **Surface Decoding**: The decoder is bound directly to an Android system `Surface`. The hardware decoder writes decoded pixel buffers directly to GPU texture memory.
* **Zero CPU Copy**: Decoded pixels never traverse back to JVM heap memory, dropping CPU consumption on the phone to nearly zero.

### B. Hardware-Accelerated Rendering View
* The streaming canvas is implemented inside Jetpack Compose using an Android `TextureView`.
* Hardware-decoded GPU texture buffers are drawn directly on the screen by the mobile device's graphics processor.
* This architecture ensures lag-free rendering at a solid, stable 60 FPS while keeping the phone cool and preserving battery life.
