// agy-pl/rust/src/ipc/uds.rs
use tokio::net::{UnixListener, UnixStream};
use std::path::Path;
use std::fs;
use log::{info, warn};
use serde::{Serialize, Deserialize};

// ─────────────────────────────────────────────────────────────────────────────
// Command Event Structures from Go Server
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Command {
    Key {
        key_code: u16,
        pressed: bool,
    },
    MouseRelative {
        dx: i32,
        dy: i32,
    },
    MouseAbsolute {
        x: i32,
        y: i32,
        max_x: i32,
        max_y: i32,
    },
    MouseClick {
        button: u16,
        pressed: bool,
    },
    MouseScroll {
        steps: i32,
    },
    StreamConfig {
        backpressure: bool,
        codec: String,
        target_fps: u32,
    },
}

// ─────────────────────────────────────────────────────────────────────────────
// Asynchronous Unix Domain Socket Server
// ─────────────────────────────────────────────────────────────────────────────

pub struct UdsServer {
    listener: UnixListener,
    socket_path: String,
}

impl UdsServer {
    /// Binds and starts listening on the Unix Domain Socket at the specified path.
    pub fn new(socket_path: &str) -> std::io::Result<Self> {
        let path = Path::new(socket_path);
        
        // Remove existing socket file if it exists
        if path.exists() {
            fs::remove_file(path)?;
        }

        info!("Binding UDS Server to: {}", socket_path);
        let listener = UnixListener::bind(path)?;
        
        // Ensure standard users can connect to the socket
        let mut perms = fs::metadata(path)?.permissions();
        perms.set_readonly(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o666))?;
        }

        Ok(UdsServer { 
            listener,
            socket_path: socket_path.to_string(),
        })
    }
    
    /// Listens for a new connection from the Go signaling server.
    pub async fn accept(&self) -> std::io::Result<UnixStream> {
        let (stream, _) = self.listener.accept().await?;
        info!("Go Signaling Client successfully connected to Unix Domain Socket!");
        Ok(stream)
    }
}

/// Helper method to write a screen frame asynchronously down the UDS stream.
/// Frame format: [header: 20B: 4B width, 4B height, 8B timestamp, 4B payload_len][payload buffer]
pub async fn send_frame<W: tokio::io::AsyncWriteExt + Unpin>(
    stream: &mut W,
    width: u32,
    height: u32,
    timestamp: u64,
    payload_len: u32,
    pixels: &[u8],
) -> std::io::Result<()> {
	let mut header = [0u8; 20];
	header[0..4].copy_from_slice(&width.to_be_bytes());
	header[4..8].copy_from_slice(&height.to_be_bytes());
	header[8..16].copy_from_slice(&timestamp.to_be_bytes());
	header[16..20].copy_from_slice(&payload_len.to_be_bytes());

	// Send header first
	stream.write_all(&header).await?;
	// Send raw pixel buffers
	stream.write_all(pixels).await?;
	stream.flush().await?;
	
	Ok(())
}

/// Helper method to read the next incoming command packet from the Go signaling server.
/// Command format: [length: u32][JSON String]
pub async fn read_command<R: tokio::io::AsyncReadExt + Unpin>(
    stream: &mut R,
) -> std::io::Result<Option<Command>> {
	let mut len_buf = [0u8; 4];
	if let Err(e) = stream.read_exact(&mut len_buf).await {
		if e.kind() == std::io::ErrorKind::UnexpectedEof {
			return Ok(None);
		}
		return Err(e);
	}

	let len = u32::from_be_bytes(len_buf) as usize;
	if len == 0 || len > 16384 {
		return Err(std::io::Error::new(
			std::io::ErrorKind::InvalidData,
			format!("Invalid command length packet: {}", len),
		));
	}

	let mut json_buf = vec![0u8; len];
	stream.read_exact(&mut json_buf).await?;

	match serde_json::from_slice::<Command>(&json_buf) {
		Ok(cmd) => Ok(Some(cmd)),
		Err(e) => {
			warn!("Failed to deserialize UDS command JSON: {}", e);
			Ok(None)
		}
	}
}

// Clean up socket file on drop
impl Drop for UdsServer {
    fn drop(&mut self) {
        let path = Path::new(&self.socket_path);
        if path.exists() {
            let _ = fs::remove_file(path);
        }
    }
}
