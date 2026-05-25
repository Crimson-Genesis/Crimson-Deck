// agy-pl-v2/rust/src/main.rs
pub mod input;
pub mod capture;
pub mod ipc;

use std::env;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use std::os::unix::process::CommandExt;
use tokio::sync::Mutex;
use log::{info, error, LevelFilter};
use env_logger::Builder;

use input::uinput::UInputDevice;
use capture::x11::X11Capturer;
use capture::wayland::WaylandCapturer;
use ipc::uds::{UdsServer, Command, send_frame, read_command};


// ─────────────────────────────────────────────────────────────────────────────
// Custom Full-Range PC/JPEG RGB-to-YUV420p Conversion Buffer
// ─────────────────────────────────────────────────────────────────────────────
pub struct FullRangeYUVBuffer {
    width: usize,
    height: usize,
    y_plane: Vec<u8>,
    u_plane: Vec<u8>,
    v_plane: Vec<u8>,
}

impl FullRangeYUVBuffer {
    pub fn new(width: usize, height: usize) -> Self {
        Self {
            width,
            height,
            y_plane: vec![0u8; width * height],
            u_plane: vec![0u8; (width / 2) * (height / 2)],
            v_plane: vec![0u8; (width / 2) * (height / 2)],
        }
    }

    pub fn read_rgb(&mut self, rgb: &[u8]) {
        let width = self.width;
        let height = self.height;
        let half_width = width / 2;

        // Populate Y plane using optimized integer full-range coefficients:
        // Y = ((77 * R + 150 * G + 29 * B) >> 8)
        let src_chunks = rgb.chunks_exact(3);
        for (idx, rgb_pixel) in src_chunks.enumerate() {
            let r = rgb_pixel[0] as i32;
            let g = rgb_pixel[1] as i32;
            let b = rgb_pixel[2] as i32;
            self.y_plane[idx] = ((77 * r + 150 * g + 29 * b) >> 8).clamp(0, 255) as u8;
        }

        // Populate U and V planes by subsampling 2x2 blocks with full-range coefficients
        for j in 0..height / 2 {
            let y0 = j * 2;
            let y1 = y0 + 1;
            for i in 0..width / 2 {
                let x0 = i * 2;
                let x1 = x0 + 1;

                let p00_idx = (x0 + y0 * width) * 3;
                let p01_idx = (x1 + y0 * width) * 3;
                let p10_idx = (x0 + y1 * width) * 3;
                let p11_idx = (x1 + y1 * width) * 3;

                let r_avg = (rgb[p00_idx] as i32 + rgb[p01_idx] as i32 + rgb[p10_idx] as i32 + rgb[p11_idx] as i32) / 4;
                let g_avg = (rgb[p00_idx + 1] as i32 + rgb[p01_idx + 1] as i32 + rgb[p10_idx + 1] as i32 + rgb[p11_idx + 1] as i32) / 4;
                let b_avg = (rgb[p00_idx + 2] as i32 + rgb[p01_idx + 2] as i32 + rgb[p10_idx + 2] as i32 + rgb[p11_idx + 2] as i32) / 4;

                let u_val = (((-43 * r_avg - 85 * g_avg + 128 * b_avg) >> 8) + 128).clamp(0, 255) as u8;
                let v_val = (((128 * r_avg - 107 * g_avg - 21 * b_avg) >> 8) + 128).clamp(0, 255) as u8;

                let dst_idx = i + j * half_width;
                self.u_plane[dst_idx] = u_val;
                self.v_plane[dst_idx] = v_val;
            }
        }
    }
}

impl openh264::formats::YUVSource for FullRangeYUVBuffer {
    fn width(&self) -> i32 {
        self.width as i32
    }

    fn height(&self) -> i32 {
        self.height as i32
    }

    fn y(&self) -> &[u8] {
        &self.y_plane
    }

    fn u(&self) -> &[u8] {
        &self.u_plane
    }

    fn v(&self) -> &[u8] {
        &self.v_plane
    }

    fn y_stride(&self) -> i32 {
        self.width as i32
    }

    fn u_stride(&self) -> i32 {
        (self.width / 2) as i32
    }

    fn v_stride(&self) -> i32 {
        (self.width / 2) as i32
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Automated ADB Workstation IP Auto-Discovery Broadcaster
// ─────────────────────────────────────────────────────────────────────────────
fn broadcast_workstation_ips_to_adb() {
    tokio::spawn(async move {
        // Wait 1.5 seconds for the Android app activity to fully load in the foreground
        tokio::time::sleep(Duration::from_millis(1500)).await;
        
        info!("Scanning for connected Android devices via ADB for auto-discovery...");
        let adb_devices = std::process::Command::new("adb")
            .arg("devices")
            .output();
            
        match adb_devices {
            Ok(output) => {
                let stdout = String::from_utf8_lossy(&output.stdout);
                let lines: Vec<&str> = stdout.lines()
                    .filter(|line| !line.is_empty() && !line.starts_with("List of devices") && line.contains("device"))
                    .collect();
                if lines.is_empty() {
                    info!("No Android devices connected via USB debugging. Auto-discovery broadcast skipped.");
                    return;
                }
                
                info!("Connected Android device detected! Resolving workstation network interfaces...");
                
                // Get hostname
                let hostname = std::process::Command::new("uname")
                    .arg("-n")
                    .output()
                    .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
                    .unwrap_or_else(|_| "genesis".to_string());
                    
                // Get LAN IP (Route to internet interface primary IP)
                let lan_ip_output = std::process::Command::new("sh")
                    .args(&["-c", "ip route get 1.1.1.1 | grep -oP 'src \\K\\S+'"])
                    .output();
                let lan_ip = match lan_ip_output {
                    Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
                    Err(_) => "".to_string(),
                };
                
                // Get Tailscale IP
                let ts_ip_output = std::process::Command::new("tailscale")
                    .args(&["ip", "-4"])
                    .output();
                let ts_ip = match ts_ip_output {
                    Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
                    Err(_) => "".to_string(),
                };
                
                info!("✓ Workstation identified: {} (Wi-Fi LAN: {}, Tailscale: {})", hostname, lan_ip, ts_ip);
                
                // Fire ADB broadcast!
                let adb_broadcast = std::process::Command::new("adb")
                    .args(&[
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "com.crimson.deck.DISCOVER_IP",
                        "--es",
                        "host_name",
                        &hostname,
                        "--es",
                        "lan_ip",
                        &lan_ip,
                        "--es",
                        "tailscale_ip",
                        &ts_ip,
                    ])
                    .status();
                    
                match adb_broadcast {
                    Ok(status) if status.success() => {
                        info!("✓ Auto-discovery workstation details successfully broadcasted to phone!");
                    }
                    _ => {
                        error!("✖ Failed to broadcast workstation details over ADB.");
                    }
                }
            }
            Err(e) => {
                error!("ADB command not available: {}", e);
            }
        }
    });
}

fn spawn_tee_logging(log_file: std::fs::File) -> Result<(), std::io::Error> {
    use std::os::unix::io::FromRawFd;
    use std::io::{Read, Write};

    // 1. Duplicate original stdout/stderr descriptors so we can write to the terminal
    let orig_stdout_fd = unsafe { libc::dup(libc::STDOUT_FILENO) };
    let orig_stderr_fd = unsafe { libc::dup(libc::STDERR_FILENO) };
    if orig_stdout_fd < 0 || orig_stderr_fd < 0 {
        return Err(std::io::Error::last_os_error());
    }

    // Convert raw FDs to safe File instances for writing to the terminal
    let mut terminal_out = unsafe { std::fs::File::from_raw_fd(orig_stdout_fd) };

    // 2. Create the pipe
    let mut pipe_fds = [0; 2];
    if unsafe { libc::pipe(pipe_fds.as_mut_ptr()) } < 0 {
        return Err(std::io::Error::last_os_error());
    }

    let read_fd = pipe_fds[0];
    let write_fd = pipe_fds[1];

    // 3. Redirect STDOUT and STDERR of this process (and future children) to the write-end of the pipe
    unsafe {
        if libc::dup2(write_fd, libc::STDOUT_FILENO) < 0 {
            return Err(std::io::Error::last_os_error());
        }
        if libc::dup2(write_fd, libc::STDERR_FILENO) < 0 {
            return Err(std::io::Error::last_os_error());
        }
        // Close the write-end of the pipe now that it is duplicated to 1 and 2
        libc::close(write_fd);
    }

    // Convert the read-end of the pipe to a safe File instance for reading
    let mut pipe_read = unsafe { std::fs::File::from_raw_fd(read_fd) };
    let mut log_file_clone = log_file.try_clone()?;

    // 4. Spawn a background thread to read from the pipe and write to both the terminal and the log file
    std::thread::spawn(move || {
        let mut buffer = [0u8; 4096];
        loop {
            match pipe_read.read(&mut buffer) {
                Ok(0) => break, // EOF reached (all write descriptors closed)
                Ok(n) => {
                    // Write to the original terminal stdout
                    let _ = terminal_out.write_all(&buffer[..n]);
                    let _ = terminal_out.flush();
                    
                    // Write to the datetime log file
                    let _ = log_file_clone.write_all(&buffer[..n]);
                    let _ = log_file_clone.flush();
                }
                Err(_) => break,
            }
        }
    });

    Ok(())
}

use serde::Deserialize;

#[derive(Deserialize, Debug, Clone)]
pub struct OpenH264Config {
    pub target_bitrate_bps: u32,
    pub enable_skip_frame: bool,
}

#[derive(Deserialize, Debug, Clone)]
pub struct NvencConfig {
    pub target_bitrate_bps: u32,
    pub max_bitrate_bps: u32,
    pub buffer_size_kb: u32,
    pub keyframe_interval: u32,
}

#[derive(Deserialize, Debug, Clone)]
pub struct ServerSideConfig {
    pub network_port: String,
    pub uds_socket_path: String,
    pub window_manager_cmd: String,
    pub default_discovery_hostname: String,
    pub default_capture_protocol: String,
    pub openh264_settings: OpenH264Config,
    pub nvenc_settings: NvencConfig,
    pub log_dir: Option<String>,
}

fn load_server_config(exe_dir: &std::path::Path) -> ServerSideConfig {
    let paths = vec![
        exe_dir.join("server/config.json"),
        exe_dir.join("../../../server/config.json"),
        exe_dir.join("../../../config.json"),
        std::path::PathBuf::from("server/config.json"),
        std::path::PathBuf::from("config.json"),
    ];

    for p in paths {
        if p.exists() {
            if let Ok(file_content) = std::fs::read_to_string(&p) {
                if let Ok(conf) = serde_json::from_str::<ServerSideConfig>(&file_content) {
                    info!("[INFO] Config JSON loaded successfully from: {:?}", p);
                    return conf;
                }
            }
        }
    }

    info!("[INFO] config.json not found or failed to parse. Using hardcoded system defaults.");
    ServerSideConfig {
        network_port: "9090".to_string(),
        uds_socket_path: "/tmp/crimson-deck.sock".to_string(),
        window_manager_cmd: "i3-msg".to_string(),
        default_discovery_hostname: "genesis".to_string(),
        default_capture_protocol: "x11".to_string(),
        openh264_settings: OpenH264Config {
            target_bitrate_bps: 5_000_000,
            enable_skip_frame: false,
        },
        nvenc_settings: NvencConfig {
            target_bitrate_bps: 3_000_000,
            max_bitrate_bps: 4_000_000,
            buffer_size_kb: 256,
            keyframe_interval: 300,
        },
        log_dir: None,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Primary Rust Daemon Entry Point
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let sudo_uid = env::var("SUDO_UID").ok().and_then(|s| s.parse::<u32>().ok());
    let sudo_gid = env::var("SUDO_GID").ok().and_then(|s| s.parse::<u32>().ok());
    let sudo_user = env::var("SUDO_USER").ok();

    // 0. Resolve unified logging path and redirect stdout/stderr to logs/<datetime>.log
    let exe_path = env::current_exe()?;
    let exe_dir = exe_path.parent().ok_or("Failed to get parent directory")?;
    
    // Load dynamic server configuration
    let config = Arc::new(load_server_config(exe_dir));

    // Resolve target log directory
    let logs_dir = if let Some(ref custom_dir) = config.log_dir {
        std::path::PathBuf::from(custom_dir)
    } else {
        let user_name = sudo_user.clone()
            .or_else(|| env::var("USER").ok())
            .unwrap_or_else(|| "default".to_string());

        if user_name != "root" && user_name != "default" {
            std::path::PathBuf::from(format!("/home/{}/.local/share/crimson-deck/logs", user_name))
        } else if user_name == "root" {
            std::path::PathBuf::from("/root/.local/share/crimson-deck/logs")
        } else {
            exe_dir.join("logs")
        }
    };

    std::fs::create_dir_all(&logs_dir)?;

    // Set permissions to 777 for directory
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&logs_dir, std::fs::Permissions::from_mode(0o777));
    }

    let date_output = std::process::Command::new("date")
        .arg("+%Y-%m-%d_%H-%M-%S")
        .output();
    let time_str = match date_output {
        Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Err(_) => "log".to_string(),
    };
    let log_filename = format!("{}.log", time_str);
    let log_path = logs_dir.join(&log_filename);

    println!("[INFO] Server unified logs are being saved directly in: {}/{}", logs_dir.to_string_lossy(), log_filename);

    let log_file = std::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&log_path)?;

    // Set permissions to 666 for log file
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&log_path, std::fs::Permissions::from_mode(0o666));
        
        // Also chown to standard user if running under sudo
        if let (Some(uid), Some(gid)) = (sudo_uid, sudo_gid) {
            let logs_c_str = std::ffi::CString::new(logs_dir.to_string_lossy().to_string()).unwrap();
            let log_path_c_str = std::ffi::CString::new(log_path.to_string_lossy().to_string()).unwrap();
            unsafe {
                libc::chown(logs_c_str.as_ptr(), uid, gid);
                libc::chown(log_path_c_str.as_ptr(), uid, gid);
            }
        }
    }

    spawn_tee_logging(log_file)?;

    // 1. Initialize systems logger
    Builder::new()
        .filter(None, LevelFilter::Info)
        .init();

    info!("====================================================");
    info!("       CRIMSON DECK VIRTUAL SYSTEMS DAEMON      ");
    info!("====================================================");

    // 2. Parse active capture mode flags
    let args: Vec<String> = env::args().collect();
    let mut use_wayland = false;
    
    for arg in &args {
        if arg == "--wayland" {
            use_wayland = true;
        }
    }

    if use_wayland {
        info!("Targeting Display Protocol: WAYLAND (PipeWire)");
    } else {
        info!("Targeting Display Protocol: X11 (MIT-SHM)");
    }

    // 3. Initialize kernel-level uinput device client
    let device = match UInputDevice::new() {
        Ok(dev) => Arc::new(Mutex::new(dev)),
        Err(e) => {
            error!("✖ CRITICAL ERROR: Could not create uinput virtual device.");
            error!("  Ensure you are running as root or have verified '/dev/uinput' permission mappings.");
            error!("  Error details: {}", e);
            std::process::exit(1);
        }
    };

    // 4. Initialize Unix Domain Socket Server
    let uds_server = match UdsServer::new(&config.uds_socket_path) {
        Ok(srv) => srv,
        Err(e) => {
            error!("✖ CRITICAL ERROR: Could not bind Unix Domain Socket Server: {}", e);
            std::process::exit(1);
        }
    };

    // 4.5. Automatically compile and spawn Go Gateway Server as a background child process
    // Check multiple candidate locations relative to exe_dir
    let mut go_dir_path = exe_dir.join("server/go");
    if !go_dir_path.exists() {
        if let Ok(path) = exe_dir.join("../../../go").canonicalize() {
            go_dir_path = path;
        } else if let Ok(path) = exe_dir.join("../../../server/go").canonicalize() {
            go_dir_path = path;
        }
    }
    
    let go_dir = go_dir_path.to_string_lossy().to_string();
    let go_bin_path = go_dir_path.join("server");
    let go_bin = go_bin_path.to_string_lossy().to_string();
    
    let sudo_uid = env::var("SUDO_UID").ok().and_then(|s| s.parse::<u32>().ok());
    let sudo_gid = env::var("SUDO_GID").ok().and_then(|s| s.parse::<u32>().ok());
    let sudo_user = env::var("SUDO_USER").ok();

    let mut build_needed = !std::path::Path::new(&go_bin).exists();
    if !build_needed {
        if let (Ok(bin_meta), Ok(src_meta)) = (std::fs::metadata(&go_bin), std::fs::metadata(format!("{}/main.go", go_dir))) {
            if let (Ok(bin_mod), Ok(src_mod)) = (bin_meta.modified(), src_meta.modified()) {
                if src_mod > bin_mod {
                    info!("Go source main.go is newer than binary. Recompiling...");
                    build_needed = true;
                }
            }
        }
    }

    if build_needed {
        if !std::path::Path::new(&go_bin).exists() {
            info!("Go gateway server binary not found. Compiling automatically...");
        }
        let mut compile_cmd = std::process::Command::new("go");
        compile_cmd.args(&["build", "-o", "server", "main.go"])
            .current_dir(&go_dir);
        
        if let Some(uid) = sudo_uid {
            compile_cmd.uid(uid);
        }
        if let Some(gid) = sudo_gid {
            compile_cmd.gid(gid);
        }
        
        let compile_status = compile_cmd.status();
        match compile_status {
            Ok(status) if status.success() => {
                info!("✓ Go gateway server successfully compiled!");
            }
            _ => {
                error!("✖ Compilation failed. Make sure Go is installed and build manually.");
            }
        }
    }

    info!("Launching Go Network Gateway server dynamically as a child process...");
    let mut go_cmd = std::process::Command::new(&go_bin);
    go_cmd.current_dir(&go_dir);
    
    if let Some(uid) = sudo_uid {
        go_cmd.uid(uid);
    }
    if let Some(gid) = sudo_gid {
        go_cmd.gid(gid);
    }
    
    if let Some(user) = &sudo_user {
        let xauth_path = format!("/home/{}/.Xauthority", user);
        if std::path::Path::new(&xauth_path).exists() {
            go_cmd.env("XAUTHORITY", xauth_path);
        }
        go_cmd.env("HOME", format!("/home/{}", user));
        go_cmd.env("USER", user);
    }
    go_cmd.env("DISPLAY", ":0");
    go_cmd.env("LOG_FILE_PATH", &log_path);

    let _go_child = go_cmd.spawn()
        .map_err(|e| {
            error!("✖ Warning: Could not execute Go gateway server binary automatically.");
            error!("  Error details: {}", e);
            e
        });

    // Run the automated ADB workstation IP discovery broadcast in a background thread
    broadcast_workstation_ips_to_adb();

    // 5. Main connection orchestrator loop
    loop {
        info!("Waiting for Go Signaling Client to connect over UDS...");
        
        match uds_server.accept().await {
            Ok(stream) => {
                let (mut read_half, mut write_half) = stream.into_split();
                let dev_clone = Arc::clone(&device);
                let config_for_capture = Arc::clone(&config);
                
                let session_active = Arc::new(AtomicBool::new(true));
                let session_active_clone1 = Arc::clone(&session_active);
                let session_active_clone2 = Arc::clone(&session_active);

                // Shared streaming settings
                let target_fps = Arc::new(std::sync::atomic::AtomicU32::new(60));
                let target_fps_clone1 = Arc::clone(&target_fps);
                let target_fps_clone2 = Arc::clone(&target_fps);

                let active_codec = Arc::new(Mutex::new("h264".to_string()));
                let active_codec_clone1 = Arc::clone(&active_codec);
                let active_codec_clone2 = Arc::clone(&active_codec);

                let force_keyframe = Arc::new(std::sync::atomic::AtomicBool::new(true)); // Start with true!
                let force_keyframe_clone1 = Arc::clone(&force_keyframe);
                let force_keyframe_clone2 = Arc::clone(&force_keyframe);

                // Spawn independent async UDS command listener task
                let command_task = tokio::spawn(async move {
                    info!("UDS Command Listener spawned.");
                    loop {
                        match read_command(&mut read_half).await {
                            Ok(Some(cmd)) => {
                                match cmd {
                                    Command::StreamConfig { backpressure: _, codec, target_fps } => {
                                        info!("Received StreamConfig update: codec={}, target_fps={}", codec, target_fps);
                                        target_fps_clone1.store(target_fps, Ordering::SeqCst);
                                        let mut c = active_codec_clone1.lock().await;
                                        *c = codec;
                                        force_keyframe_clone1.store(true, Ordering::SeqCst);
                                    }
                                    _ => {
                                        let mut dev = dev_clone.lock().await;
                                        if let Err(e) = execute_command(&mut *dev, cmd) {
                                            error!("Failed to inject virtual command event: {}", e);
                                        }
                                    }
                                }
                            }
                            Ok(None) => {
                                info!("Go client disconnected (EOF). Tearing down listener task.");
                                break;
                            }
                            Err(e) => {
                                error!("UDS read error: {}", e);
                                break;
                            }
                        }
                    }
                    session_active_clone1.store(false, Ordering::SeqCst);
                });

                let force_keyframe_wayland = Arc::clone(&force_keyframe_clone2);
                let force_keyframe_x11 = Arc::clone(&force_keyframe_clone2);

                // Spawn independent async screen capture and transmission loop task
                let capture_task = tokio::spawn(async move {
                    info!("UDS Screen Capture and Video Stream task spawned.");
                    
                    let mut h264_encoder: Option<openh264::encoder::Encoder> = None;
                    let mut yuv_buf: Option<FullRangeYUVBuffer> = None;

                    if use_wayland {
                        let mut capturer = match WaylandCapturer::new() {
                            Ok(cap) => cap,
                            Err(e) => {
                                error!("Failed to initialize Wayland capturer: {}", e);
                                return;
                            }
                        };
                        
                        let width = capturer.width() as u32;
                        let height = capturer.height() as u32;
                        let mut rgb_buf = vec![0u8; (width * height * 3) as usize];
                        let mut frame_count = 0u64;

                        while session_active_clone2.load(Ordering::SeqCst) {
                            let frame_start = tokio::time::Instant::now();
                            match capturer.capture_frame() {
                                Ok(pixels) => {
                                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64;
                                    let codec = {
                                        let c = active_codec_clone2.lock().await;
                                        c.clone()
                                    };

                                    if codec == "h264" {
                                        // Lazily initialize OpenH264 encoder
                                        let encoder = match &mut h264_encoder {
                                            Some(enc) => enc,
                                            None => {
                                                let config = openh264::encoder::EncoderConfig::new(width, height)
                                                    .set_bitrate_bps(config_for_capture.openh264_settings.target_bitrate_bps)
                                                    .enable_skip_frame(config_for_capture.openh264_settings.enable_skip_frame);
                                                let enc = openh264::encoder::Encoder::with_config(config).unwrap();
                                                h264_encoder = Some(enc);
                                                h264_encoder.as_mut().unwrap()
                                            }
                                        };

                                        // Convert BGRA to RGB
                                        let src_chunks = pixels.chunks_exact(4);
                                        let dst_chunks = rgb_buf.chunks_exact_mut(3);
                                        for (src, dst) in src_chunks.zip(dst_chunks) {
                                            dst[0] = src[2]; // R
                                            dst[1] = src[1]; // G
                                            dst[2] = src[0]; // B
                                        }

                                        // Lazily initialize or reuse YUV buffer
                                        let yuv = match &mut yuv_buf {
                                            Some(buf) => {
                                                buf.read_rgb(&rgb_buf);
                                                buf
                                            }
                                            None => {
                                                let mut buf = FullRangeYUVBuffer::new(width as usize, height as usize);
                                                buf.read_rgb(&rgb_buf);
                                                yuv_buf = Some(buf);
                                                yuv_buf.as_mut().unwrap()
                                            }
                                        };

                                        // Determine keyframe necessity (either requested by client or periodic for error-resilience)
                                        frame_count += 1;
                                        let force_iframe = force_keyframe_wayland.swap(false, Ordering::SeqCst) || (frame_count % 300 == 0);
                                        if force_iframe {
                                            unsafe {
                                                let _ = encoder.raw_api().force_intra_frame(true);
                                            }
                                        }

                                        let bytes = {
                                            match encoder.encode(yuv) {
                                                Ok(bitstream) => Some(bitstream.to_vec()),
                                                Err(e) => {
                                                    error!("OpenH264 encoding error (Wayland): {:?}", e);
                                                    None
                                                }
                                            }
                                        };

                                        if let Some(bytes) = bytes {
                                            if let Err(e) = send_frame(&mut write_half, width, height, ts, bytes.len() as u32, &bytes).await {
                                                error!("Failed to stream Wayland H.264 frame over UDS: {}", e);
                                                break;
                                            }
                                        }
                                    } else {
                                        // MJPEG mode: Send raw BGRA frame
                                        let payload_len = (width * height * 4) as u32;
                                        if let Err(e) = send_frame(&mut write_half, width, height, ts, payload_len, pixels).await {
                                            error!("Failed to stream Wayland frame over UDS: {}", e);
                                            break;
                                        }
                                    }
                                }
                                Err(e) => {
                                    error!("Wayland capture error: {}", e);
                                }
                            }
                            let fps = target_fps_clone2.load(Ordering::SeqCst);
                            let budget_ms = if fps > 0 { 1000 / fps } else { 16 };
                            let elapsed = frame_start.elapsed().as_millis() as u64;
                            if elapsed < budget_ms as u64 {
                                tokio::time::sleep(Duration::from_millis(budget_ms as u64 - elapsed)).await;
                            }
                        }
                    } else {
                        let mut capturer = match X11Capturer::new() {
                            Ok(cap) => cap,
                            Err(e) => {
                                error!("Failed to initialize X11 capturer: {}", e);
                                return;
                            }
                        };
                        
                        let width = capturer.width() as u32;
                        let height = capturer.height() as u32;
                        let mut rgb_buf = vec![0u8; (width * height * 3) as usize];
                        let mut frame_count = 0u64;

                        while session_active_clone2.load(Ordering::SeqCst) {
                            let frame_start = tokio::time::Instant::now();
                            match capturer.capture_frame() {
                                Ok(pixels) => {
                                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64;
                                    let codec = {
                                        let c = active_codec_clone2.lock().await;
                                        c.clone()
                                    };

                                    if codec == "h264" {
                                        // Lazily initialize OpenH264 encoder
                                        let encoder = match &mut h264_encoder {
                                            Some(enc) => enc,
                                            None => {
                                                let config = openh264::encoder::EncoderConfig::new(width, height)
                                                    .set_bitrate_bps(config_for_capture.openh264_settings.target_bitrate_bps)
                                                    .enable_skip_frame(config_for_capture.openh264_settings.enable_skip_frame);
                                                let enc = openh264::encoder::Encoder::with_config(config).unwrap();
                                                h264_encoder = Some(enc);
                                                h264_encoder.as_mut().unwrap()
                                            }
                                        };

                                        // Convert BGRA to RGB
                                        let src_chunks = pixels.chunks_exact(4);
                                        let dst_chunks = rgb_buf.chunks_exact_mut(3);
                                        for (src, dst) in src_chunks.zip(dst_chunks) {
                                            dst[0] = src[2]; // R
                                            dst[1] = src[1]; // G
                                            dst[2] = src[0]; // B
                                        }

                                        // Lazily initialize or reuse YUV buffer
                                        let yuv = match &mut yuv_buf {
                                            Some(buf) => {
                                                buf.read_rgb(&rgb_buf);
                                                buf
                                            }
                                            None => {
                                                let mut buf = FullRangeYUVBuffer::new(width as usize, height as usize);
                                                buf.read_rgb(&rgb_buf);
                                                yuv_buf = Some(buf);
                                                yuv_buf.as_mut().unwrap()
                                            }
                                        };

                                        // Determine keyframe necessity (either requested by client or periodic for error-resilience)
                                        frame_count += 1;
                                        let force_iframe = force_keyframe_x11.swap(false, Ordering::SeqCst) || (frame_count % 300 == 0);
                                        if force_iframe {
                                            unsafe {
                                                let _ = encoder.raw_api().force_intra_frame(true);
                                            }
                                        }

                                        let bytes = {
                                            match encoder.encode(yuv) {
                                                Ok(bitstream) => Some(bitstream.to_vec()),
                                                Err(e) => {
                                                    error!("OpenH264 encoding error (X11): {:?}", e);
                                                    None
                                                }
                                            }
                                        };

                                        if let Some(bytes) = bytes {
                                            if let Err(e) = send_frame(&mut write_half, width, height, ts, bytes.len() as u32, &bytes).await {
                                                error!("Failed to stream X11 H.264 frame over UDS: {}", e);
                                                break;
                                            }
                                        }
                                    } else {
                                        // MJPEG mode: Send raw BGRA frame
                                        let payload_len = (width * height * 4) as u32;
                                        if let Err(e) = send_frame(&mut write_half, width, height, ts, payload_len, pixels).await {
                                            error!("Failed to stream X11 frame over UDS: {}", e);
                                            break;
                                        }
                                    }
                                }
                                Err(e) => {
                                    error!("X11 capture error: {}", e);
                                }
                            }
                            let fps = target_fps_clone2.load(Ordering::SeqCst);
                            let budget_ms = if fps > 0 { 1000 / fps } else { 16 };
                            let elapsed = frame_start.elapsed().as_millis() as u64;
                            if elapsed < budget_ms as u64 {
                                tokio::time::sleep(Duration::from_millis(budget_ms as u64 - elapsed)).await;
                            }
                        }
                    }
                    
                    info!("Capture loop stopped.");
                });

                // Wait for command task to finish (indicates client disconnection)
                let _ = command_task.await;
                // Safely cancel capture task if it's still running
                capture_task.abort();
                info!("Session resources released successfully.");
            }
            Err(e) => {
                error!("Socket connection accept failure: {}", e);
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        }
    }
}

/// Helper method to execute virtual mouse/keyboard event injection commands.
fn execute_command(device: &mut UInputDevice, cmd: Command) -> Result<(), std::io::Error> {
    info!("Injecting Virtual Event: {:?}", cmd);
    match cmd {
        Command::Key { key_code, pressed } => {
            device.inject_key(key_code, pressed)
        }
        Command::MouseRelative { dx, dy } => {
            device.inject_mouse_move_relative(dx, dy)
        }
        Command::MouseAbsolute { x, y, max_x, max_y } => {
            device.inject_mouse_move_absolute(x, y, max_x, max_y)
        }
        Command::MouseClick { button, pressed } => {
            device.inject_mouse_click(button, pressed)
        }
        Command::MouseScroll { steps } => {
            device.inject_mouse_scroll(steps)
        }
        Command::StreamConfig { .. } => {
            Ok(())
        }
    }
}


