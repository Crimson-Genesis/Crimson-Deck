package main

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"image"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v3"
	"github.com/pixiv/go-libjpeg/jpeg"
)

// ─────────────────────────────────────────────────────────────────────────────
// System Constants & Configuration
// ─────────────────────────────────────────────────────────────────────────────

var (
	Port             = "9090"
	SocketPath       = "/tmp/crimson-deck.sock"
	WindowManagerCmd = "i3-msg"
)

const (
	JpegQuality    = 70
	UdsRetryPeriod = 1 * time.Second
)

// Upgrader configures Gorilla WebSocket connections.
var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 4096,
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow connection from anywhere on the secure network
	},
}

// ─────────────────────────────────────────────────────────────────────────────
// Struct Definitions for Commands & State
// ─────────────────────────────────────────────────────────────────────────────

// Command matches the Rust engine input serialization representation.
type Command struct {
	Type     string `json:"type"`
	KeyCode  uint16 `json:"key_code,omitempty"`
	Pressed  bool   `json:"pressed,omitempty"`
	Dx       int32  `json:"dx,omitempty"`
	Dy       int32  `json:"dy,omitempty"`
	X        int32  `json:"x,omitempty"`
	Y        int32  `json:"y,omitempty"`
	MaxX     int32  `json:"max_x,omitempty"`
	MaxY     int32  `json:"max_y,omitempty"`
	Button   uint16 `json:"button,omitempty"`
	Steps    int32  `json:"steps,omitempty"`
	Text     string `json:"text,omitempty"`
}

type StreamConfig struct {
	FrameDropping bool   `json:"frame_dropping"`
	Transport     string `json:"transport"` // "websocket" | "webrtc"
	Backpressure  bool   `json:"backpressure"`
	Codec         string `json:"codec"`     // "mjpeg" | "h264"
}

type RustCommand struct {
	Type         string `json:"type"`
	Backpressure bool   `json:"backpressure"`
	Codec        string `json:"codec"`
	TargetFps    uint32 `json:"target_fps"`
}

var currentConfig = StreamConfig{
	FrameDropping: false,
	Transport:     "websocket",
	Backpressure:  false,
	Codec:         "h264",
}
var configMutex sync.RWMutex

// SafeConn wraps a Gorilla WebSocket connection with a write lock to prevent concurrent write corruptions.
type SafeConn struct {
	conn       *websocket.Conn
	writeMutex sync.Mutex
}

func NewSafeConn(c *websocket.Conn) *SafeConn {
	return &SafeConn{conn: c}
}

func (s *SafeConn) WriteMessage(messageType int, data []byte) error {
	s.writeMutex.Lock()
	defer s.writeMutex.Unlock()
	return s.conn.WriteMessage(messageType, data)
}

func (s *SafeConn) TryWriteMessage(messageType int, data []byte) (bool, error) {
	if !s.writeMutex.TryLock() {
		return false, nil // Connection busy, frame skipped
	}
	defer s.writeMutex.Unlock()
	err := s.conn.WriteMessage(messageType, data)
	return true, err
}

func (s *SafeConn) Close() error {
	s.writeMutex.Lock()
	defer s.writeMutex.Unlock()
	return s.conn.Close()
}

func removeWSClient(c *SafeConn) {
	state.wsClientsMutex.Lock()
	_, exists := state.wsClients[c]
	if exists {
		delete(state.wsClients, c)
		state.wsClientsMutex.Unlock()
		log.Printf("WS write failed. Removing client.\n")
		c.Close()
	} else {
		state.wsClientsMutex.Unlock()
	}
}

// ServerState coordinates connection pools and thread-safe operations.
type ServerState struct {
	// UDS Connection
	udsConn      net.Conn
	udsConnMutex sync.Mutex

	// WebSocket Client Pools
	wsClients      map[*SafeConn]bool
	wsClientsMutex sync.RWMutex

	// WebRTC Data Channels Pool
	rtcChannels      map[*webrtc.DataChannel]bool
	rtcChannelsMutex sync.RWMutex
}

var state = &ServerState{
	wsClients:   make(map[*SafeConn]bool),
	rtcChannels: make(map[*webrtc.DataChannel]bool),
}

var logFile *os.File
var logFileMutex sync.Mutex

func initLogFile() {
	path := os.Getenv("LOG_FILE_PATH")
	if path == "" {
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err == nil {
		logFile = f
		// Set Go standard log to output to both console (stderr) and the log file
		log.SetOutput(io.MultiWriter(os.Stderr, f))
	}
}

func logToFileOnly(format string, v ...interface{}) {
	msg := fmt.Sprintf(format, v...)
	timestamp := time.Now().Format("2006/01/02 15:04:05 ")
	fullMsg := timestamp + msg
	if !strings.HasSuffix(fullMsg, "\n") {
		fullMsg += "\n"
	}
	
	logFileMutex.Lock()
	if logFile != nil {
		logFile.WriteString(fullMsg)
	}
	logFileMutex.Unlock()
}

// Sequential xdotool Execution Queue to ensure key down/up and mouse clicks never run out of order
var xdotoolQueue = make(chan []string, 1000)

func init() {
	go startXdotoolWorker()
	go startPipelineBroadcaster()
	go startBackpressureMonitor()
}

var droppedFrames uint64
var totalFrames uint64

func recordDroppedFrame() {
	atomic.AddUint64(&droppedFrames, 1)
}

func recordSentFrame() {
	atomic.AddUint64(&totalFrames, 1)
}

func startBackpressureMonitor() {
	ticker := time.NewTicker(2 * time.Second)
	go func() {
		for range ticker.C {
			configMutex.RLock()
			active := currentConfig.Backpressure
			configMutex.RUnlock()

			if !active {
				continue
			}

			dropped := atomic.SwapUint64(&droppedFrames, 0)
			total := atomic.SwapUint64(&totalFrames, 0)
			all := dropped + total

			if all > 10 {
				dropRate := float64(dropped) / float64(all)
				log.Printf("[Backpressure Monitor] Total: %d, Dropped: %d, Rate: %.1f%%\n", all, dropped, dropRate*100)

				// Adjust Rust FPS dynamically
				var targetFps uint32 = 60
				if dropRate > 0.4 {
					targetFps = 20
				} else if dropRate > 0.2 {
					targetFps = 30
				} else if dropRate > 0.05 {
					targetFps = 45
				}

				sendPacingToRust(targetFps)
			}
		}
	}()
}

func sendPacingToRust(fps uint32) {
	configMutex.RLock()
	config := currentConfig
	configMutex.RUnlock()

	cmd := RustCommand{
		Type:         "streamconfig",
		Backpressure: config.Backpressure,
		Codec:        config.Codec,
		TargetFps:    fps,
	}

	payload, err := json.Marshal(cmd)
	if err != nil {
		log.Printf("Error marshaling rust backpressure pacing command: %v\n", err)
		return
	}

	_ = forwardCommandToUds(payload)
}

func notifyRustDaemonOfConfig() {
	configMutex.RLock()
	config := currentConfig
	configMutex.RUnlock()

	cmd := RustCommand{
		Type:         "streamconfig",
		Backpressure: config.Backpressure,
		Codec:        config.Codec,
		TargetFps:    60,
	}

	payload, err := json.Marshal(cmd)
	if err != nil {
		log.Printf("Error marshaling rust config command: %v\n", err)
		return
	}

	_ = forwardCommandToUds(payload)
}

func startXdotoolWorker() {
	for args := range xdotoolQueue {
		execCmd := exec.Command("xdotool", args...)
		// Ensure it has standard environment including XAUTHORITY and DISPLAY
		execCmd.Env = append(os.Environ(), "DISPLAY=:0")
		if err := execCmd.Run(); err != nil {
			log.Printf("Warning: xdotool %v failed: %v\n", args, err)
		}
	}
}

type ServerConfig struct {
	NetworkPort      string `json:"network_port"`
	UdsSocketPath    string `json:"uds_socket_path"`
	WindowManagerCmd string `json:"window_manager_cmd"`
}

func loadServerConfig() {
	paths := []string{"../config.json", "config.json", "server/config.json", "../server/config.json"}
	var fileBytes []byte
	var err error
	for _, p := range paths {
		fileBytes, err = os.ReadFile(p)
		if err == nil {
			break
		}
	}
	if err != nil {
		log.Printf("[INFO] config.json not found, using default configurations.\n")
		return
	}
	
	var conf ServerConfig
	if err := json.Unmarshal(fileBytes, &conf); err != nil {
		log.Printf("[WARNING] Failed to parse config.json: %v. Using defaults.\n", err)
		return
	}
	
	if conf.NetworkPort != "" {
		Port = conf.NetworkPort
	}
	if conf.UdsSocketPath != "" {
		SocketPath = conf.UdsSocketPath
	}
	if conf.WindowManagerCmd != "" {
		WindowManagerCmd = conf.WindowManagerCmd
	}
	log.Printf("[INFO] Loaded configurations: Port=%s, SocketPath=%s, WM=%s\n", Port, SocketPath, WindowManagerCmd)
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Server Entry Point
// ─────────────────────────────────────────────────────────────────────────────

func main() {
	loadServerConfig()
	initLogFile()
	log.Println("====================================================")
	log.Println("     CRIMSON DECK SIGNALING DAEMON  ")
	log.Println("====================================================")

	// 1. Resolve and display Tailscale interface details
	ip := getTailscaleOrLocalIP()
	log.Printf("Binding workstation services strictly to network endpoint: %s\n", ip)

	// 2. Start UDS background connection and screen frame parsing loop
	go monitorAndProcessUDS()

	// 3. Register HTTP handlers
	http.HandleFunc("/offer", handleWebRTCOffer)
	http.HandleFunc("/ws", handleCommandWebSocket)
	http.HandleFunc("/stream", handleStreamWebSocket)
	http.HandleFunc("/api/stream/config", handleStreamConfigAPI)
	http.HandleFunc("/api/macros/export", handleMacrosExportAPI)
	http.HandleFunc("/api/macros/import", handleMacrosImportAPI)
	http.HandleFunc("/api/fs/list", handleFsListAPI)
	http.HandleFunc("/api/fs/upload", handleFsUploadAPI)
	http.HandleFunc("/api/fs/download", handleFsDownloadAPI)
	http.HandleFunc("/api/fs/mkdir", handleFsMkdirAPI)

	// 4. Listen and serve on all interfaces (Wi-Fi LAN + Tailscale VPN)
	addr := fmt.Sprintf("0.0.0.0:%s", Port)
	log.Printf("Listening for client handshakes on port %s (All interfaces, including http://%s:%s)\n", Port, ip, Port)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("Fatal: Web Server crashed: %v\n", err)
	}
}

func handleStreamConfigAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var newConfig StreamConfig
	if err := json.NewDecoder(r.Body).Decode(&newConfig); err != nil {
		log.Printf("Error decoding stream config: %v\n", err)
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	configMutex.Lock()
	currentConfig = newConfig
	configMutex.Unlock()

	log.Printf("Stream configuration updated: %+v\n", newConfig)

	// Notify Rust daemon dynamically of configuration state changes
	notifyRustDaemonOfConfig()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status":"success"}`))
}

// ─────────────────────────────────────────────────────────────────────────────
// Network Interface Discovery Helpers
// ─────────────────────────────────────────────────────────────────────────────

// getTailscaleOrLocalIP attempts to locate a Tailscale interface IP (100.64.0.0/10)
// and falls back to loopback or wildcards if unavailable.
func getTailscaleOrLocalIP() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		log.Printf("Warning: Failed to fetch network interfaces: %v\n", err)
		return "0.0.0.0"
	}

	for _, iface := range interfaces {
		// Look for standard Tailscale interfaces or tunnels
		if strings.HasPrefix(iface.Name, "tailscale") || strings.Contains(iface.Name, "tun") {
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, addr := range addrs {
				var ip net.IP
				switch v := addr.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}
				if ip == nil || ip.IsLoopback() {
					continue
				}
				ip4 := ip.To4()
				if ip4 != nil {
					return ip4.String()
				}
			}
		}
	}

	// Fallback to checking all system unicast interface addresses for Tailscale range
	addrs, err := net.InterfaceAddrs()
	if err == nil {
		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
				ip4 := ipnet.IP.To4()
				if ip4 != nil {
					// Tailscale CGNAT IPs reside in 100.64.0.0/10 range
					if ip4[0] == 100 && (ip4[1]&0xC0) == 64 {
						return ip4.String()
					}
				}
			}
		}
	}

	// Dynamic LAN discovery fallback
	return "0.0.0.0"
}

// ─────────────────────────────────────────────────────────────────────────────
// IPC UDS (Unix Domain Socket) Manager
// ─────────────────────────────────────────────────────────────────────────────

// monitorAndProcessUDS implements an infinite reconnect loop to pull screen
// grab frames from the Rust systems daemon, swap channels, encode JPEGs, and stream.
func monitorAndProcessUDS() {
	for {
		log.Printf("Connecting to local systems engine over UDS: %s\n", SocketPath)
		conn, err := net.Dial("unix", SocketPath)
		if err != nil {
			log.Printf("Systems engine unreachable. Retrying in %v...\n", UdsRetryPeriod)
			time.Sleep(UdsRetryPeriod)
			continue
		}

		log.Println("Successfully attached to systems engine Unix Domain Socket!")
		state.udsConnMutex.Lock()
		state.udsConn = conn
		state.udsConnMutex.Unlock()

		err = parseFrameStream(conn)
		if err != nil {
			log.Printf("UDS connection dropped: %v\n", err)
		}

		state.udsConnMutex.Lock()
		if state.udsConn != nil {
			state.udsConn.Close()
			state.udsConn = nil
		}
		state.udsConnMutex.Unlock()

		time.Sleep(1 * time.Second)
	}
}

type encodeResult struct {
	data []byte
}

var pipeline = make(chan chan encodeResult, 128)

func startPipelineBroadcaster() {
	for resChan := range pipeline {
		res := <-resChan
		if res.data != nil {
			broadcastFrame(res.data)
		}
	}
}

var pixelPool = sync.Pool{
	New: func() interface{} {
		return nil
	},
}

var bufferPool = sync.Pool{
	New: func() interface{} {
		return new(bytes.Buffer)
	},
}

// parseFrameStream reads frame packets structured as:
// [4B Width][4B Height][8B Timestamp][4B PayloadLen][Payload Bytes]
func parseFrameStream(r io.Reader) error {
	header := make([]byte, 20)

	for {
		// 1. Read header block
		_, err := io.ReadFull(r, header)
		if err != nil {
			return err
		}

		// 2. Parse fields
		width := binary.BigEndian.Uint32(header[0:4])
		height := binary.BigEndian.Uint32(header[4:8])
		_ = binary.BigEndian.Uint64(header[8:16]) // timestamp
		payloadLen := binary.BigEndian.Uint32(header[16:20])

		// Sanity check resolution
		if width == 0 || height == 0 || width > 7680 || height > 4320 {
			return fmt.Errorf("invalid frame dimensions read: %dx%d", width, height)
		}

		// Check if there are active subscribers before performing expensive JPEG encoding
		state.wsClientsMutex.RLock()
		wsCount := len(state.wsClients)
		state.wsClientsMutex.RUnlock()

		state.rtcChannelsMutex.RLock()
		rtcCount := len(state.rtcChannels)
		state.rtcChannelsMutex.RUnlock()

		if wsCount == 0 && rtcCount == 0 {
			// Skip expensive image processing and JPEG encoding when no clients are connected.
			// Efficiently discard the frame from the reader without allocating.
			_, err = io.CopyN(io.Discard, r, int64(payloadLen))
			if err != nil {
				return err
			}
			continue
		}

		// Read the payload bytes
		payload := make([]byte, payloadLen)
		_, err = io.ReadFull(r, payload)
		if err != nil {
			return err
		}

		configMutex.RLock()
		isH264 := currentConfig.Codec == "h264"
		configMutex.RUnlock()

		// 4. Create pipeline channel for this frame and push it to keep sequential order
		resChan := make(chan encodeResult, 1)
		pipeline <- resChan

		if isH264 {
			// In H.264 mode, the payload is already the compressed H.264 slice.
			// We skip JPEG compression and RGBA swap entirely!
			resChan <- encodeResult{data: payload}
		} else {
			// MJPEG mode: payload contains raw BGRA pixels
			go func(p []byte, w, h uint32, ch chan encodeResult) {
				// Swap channels from BGRA (X11 ZPixmap) to RGBA in-place (No heap allocations)
				for i := 0; i < len(p); i += 4 {
					p[i], p[i+2] = p[i+2], p[i]
				}

				// Wrap RGBA slice as standard image and compress to JPEG in memory
				img := &image.RGBA{
					Pix:    p,
					Stride: int(w) * 4,
					Rect:   image.Rect(0, 0, int(w), int(h)),
				}

				bufVal := bufferPool.Get()
				jpegBuf := bufVal.(*bytes.Buffer)
				jpegBuf.Reset()
				defer bufferPool.Put(jpegBuf)

				err := jpeg.Encode(jpegBuf, img, &jpeg.EncoderOptions{Quality: JpegQuality})
				if err != nil {
					log.Printf("JPEG compression failure: %v\n", err)
					ch <- encodeResult{data: nil}
					return
				}

				// Copy the compressed bytes to a new slice to release the pooled buffer safely
				compressedData := make([]byte, jpegBuf.Len())
				copy(compressedData, jpegBuf.Bytes())
				ch <- encodeResult{data: compressedData}
			}(payload, width, height, resChan)
		}
	}
}

// broadcastFrame distributes the compressed JPEG bytes to active subscribers.
func broadcastFrame(data []byte) {
	configMutex.RLock()
	dropFrames := currentConfig.FrameDropping
	configMutex.RUnlock()

	// Send to WebSocket stream clients
	state.wsClientsMutex.RLock()
	for client := range state.wsClients {
		if dropFrames {
			go func(c *SafeConn) {
				sent, err := c.TryWriteMessage(websocket.BinaryMessage, data)
				if err != nil {
					removeWSClient(c)
				} else if !sent {
					recordDroppedFrame()
				} else {
					recordSentFrame()
				}
			}(client)
		} else {
			go func(c *SafeConn) {
				err := c.WriteMessage(websocket.BinaryMessage, data)
				if err != nil {
					removeWSClient(c)
				} else {
					recordSentFrame()
				}
			}(client)
		}
	}
	state.wsClientsMutex.RUnlock()

	// Send to WebRTC Data Channel clients
	state.rtcChannelsMutex.RLock()
	for channel := range state.rtcChannels {
		if dropFrames && channel.BufferedAmount() > 256*1024 {
			// WebRTC channel busy, drop frame
			recordDroppedFrame()
			continue
		}
		go func(dc *webrtc.DataChannel) {
			err := dc.Send(data)
			if err != nil {
				state.rtcChannelsMutex.Lock()
				_, exists := state.rtcChannels[dc]
				if exists {
					delete(state.rtcChannels, dc)
					state.rtcChannelsMutex.Unlock()
					log.Printf("WebRTC Data Channel write failed. Removing channel.\n")
					dc.Close()
				} else {
					state.rtcChannelsMutex.Unlock()
				}
			} else {
				recordSentFrame()
			}
		}(channel)
	}
	state.rtcChannelsMutex.RUnlock()
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP & WebSockets Router Implementation
// ─────────────────────────────────────────────────────────────────────────────

// handleStreamWebSocket establishes a high-performance binary frame pipe.
func handleStreamWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket Upgrade Error: %v\n", err)
		return
	}

	log.Printf("Android client connected to video stream endpoint: %s\n", conn.RemoteAddr())

	safeConn := NewSafeConn(conn)

	state.wsClientsMutex.Lock()
	state.wsClients[safeConn] = true
	state.wsClientsMutex.Unlock()

	// Proactively notify the Rust daemon to trigger a keyframe immediately for the new client
	notifyRustDaemonOfConfig()

	// Keep-alive/Read reader loop (Must block to maintain socket state)
	defer func() {
		state.wsClientsMutex.Lock()
		delete(state.wsClients, safeConn)
		state.wsClientsMutex.Unlock()
		safeConn.Close()
		log.Printf("Video stream client disconnected: %s\n", conn.RemoteAddr())
	}()

	for {
		_, _, err := conn.ReadMessage()
		if err != nil {
			break
		}
	}
}

// handleCommand processes and routes pointer, scroll, click, keyboard, text, and clipboard sync events using xdotool/xclip.
func handleCommand(message []byte) {
	var cmd Command
	if err := json.Unmarshal(message, &cmd); err != nil {
		log.Printf("Malformed control package received: %v\n", err)
		return
	}

	// Helper function to send xdotool commands to the sequential worker queue
	runXdotool := func(args ...string) {
		xdotoolQueue <- args
	}

	switch cmd.Type {
	case "keyframe":
		log.Println("Force keyframe request received from client.")
		notifyRustDaemonOfConfig()

	case "workspace":
		if cmd.Text != "" {
			ws := cmd.Text
			isNumeric := true
			for _, r := range ws {
				if r < '0' || r > '9' {
					isNumeric = false
					break
				}
			}
			
			var i3Args []string
			if isNumeric {
				log.Printf("Emulating i3 workspace switch to number: %s\n", ws)
				i3Args = []string{"workspace", "number", ws}
			} else {
				log.Printf("Emulating i3 workspace switch to: %s\n", ws)
				i3Args = []string{"workspace", ws}
			}

			go func(args []string) {
				execCmd := exec.Command(WindowManagerCmd, args...)
				execCmd.Env = os.Environ()
				if sockPath := getI3SocketPath(); sockPath != "" {
					execCmd.Env = append(execCmd.Env, "I3SOCK="+sockPath)
				}
				execCmd.Env = append(execCmd.Env, "DISPLAY=:0")
				if err := execCmd.Run(); err != nil {
					log.Printf("Warning: %s workspace switch failed: %v\n", WindowManagerCmd, err)
				}
			}(i3Args)
		}

	case "fullscreen":
		log.Printf("Emulating %s toggle fullscreen for focused window\n", WindowManagerCmd)
		go func() {
			execCmd := exec.Command(WindowManagerCmd, "fullscreen", "toggle")
			execCmd.Env = os.Environ()
			if sockPath := getI3SocketPath(); sockPath != "" {
				execCmd.Env = append(execCmd.Env, "I3SOCK="+sockPath)
			}
			execCmd.Env = append(execCmd.Env, "DISPLAY=:0")
			if err := execCmd.Run(); err != nil {
				log.Printf("Warning: %s fullscreen toggle failed: %v\n", WindowManagerCmd, err)
			}
		}()

	case "text":
		if cmd.Text != "" {
			log.Printf("Emulating typing text: %q\n", cmd.Text)
			runXdotool("type", "--delay", "10", cmd.Text)
		}

	case "clipboard":
		if cmd.Text != "" {
			log.Printf("Emulating clipboard sync: %d bytes\n", len(cmd.Text))
			go func(t string) {
				// Set CLIPBOARD selection
				execCmd1 := exec.Command("xclip", "-selection", "clipboard")
				execCmd1.Env = append(os.Environ(), "DISPLAY=:0")
				execCmd1.Stdin = strings.NewReader(t)
				if err := execCmd1.Run(); err != nil {
					log.Printf("Warning: xclip failed to set clipboard: %v\n", err)
				}

				// Set PRIMARY selection
				execCmd2 := exec.Command("xclip", "-selection", "primary")
				execCmd2.Env = append(os.Environ(), "DISPLAY=:0")
				execCmd2.Stdin = strings.NewReader(t)
				if err := execCmd2.Run(); err != nil {
					log.Printf("Warning: xclip failed to set primary: %v\n", err)
				}

				// Wait a brief moment to ensure X11 registers the selections
				time.Sleep(50 * time.Millisecond)

				// Paste using Shift+Insert, queued sequentially through runXdotool
				runXdotool("key", "shift+Insert")
			}(cmd.Text)
		}

	case "key":
		log.Printf("Emulating Keyboard Key: KeyCode=%d (X11 Keycode=%d), Pressed=%t\n", cmd.KeyCode, cmd.KeyCode+8, cmd.Pressed)
		x11Keycode := fmt.Sprintf("%d", cmd.KeyCode+8)
		if cmd.Pressed {
			runXdotool("keydown", x11Keycode)
		} else {
			runXdotool("keyup", x11Keycode)
		}

	case "mouseabsolute":
		log.Printf("Emulating Mouse Absolute Move: X=%d, Y=%d\n", cmd.X, cmd.Y)
		runXdotool("mousemove", fmt.Sprintf("%d", cmd.X), fmt.Sprintf("%d", cmd.Y))

	case "mouserelative":
		log.Printf("Emulating Mouse Relative Move: dx=%d, dy=%d\n", cmd.Dx, cmd.Dy)
		runXdotool("mousemove_relative", "--", fmt.Sprintf("%d", cmd.Dx), fmt.Sprintf("%d", cmd.Dy))

	case "mouseclick":
		log.Printf("Emulating Mouse Click: Button=%d, Pressed=%t\n", cmd.Button, cmd.Pressed)
		var x11Button string
		switch cmd.Button {
		case 272: // Left Button
			x11Button = "1"
		case 273: // Right Button
			x11Button = "3"
		case 274: // Middle Button
			x11Button = "2"
		default:
			x11Button = "1"
		}
		if cmd.Pressed {
			runXdotool("mousedown", x11Button)
		} else {
			runXdotool("mouseup", x11Button)
		}

	case "mousescroll":
		log.Printf("Emulating Mouse Scroll: Steps=%d\n", cmd.Steps)
		if cmd.Steps > 0 {
			runXdotool("click", "--repeat", fmt.Sprintf("%d", cmd.Steps), "4") // Scroll Up
		} else if cmd.Steps < 0 {
			runXdotool("click", "--repeat", fmt.Sprintf("%d", -cmd.Steps), "5") // Scroll Down
		}
	}
}

// getI3SocketPath dynamically locates the active i3 IPC Unix Domain Socket on disk.
func getI3SocketPath() string {
	matches, err := filepath.Glob("/run/user/1000/i3/ipc-socket.*")
	if err == nil && len(matches) > 0 {
		return matches[0]
	}
	matches, err = filepath.Glob("/tmp/i3-*.*/ipc-socket.*")
	if err == nil && len(matches) > 0 {
		return matches[0]
	}
	return ""
}

// handleCommandWebSocket brokers pointer, scroll, click, and keyboard keystroke inputs.
func handleCommandWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket Command Upgrade Error: %v\n", err)
		return
	}

	log.Printf("Android client connected to input control endpoint: %s\n", conn.RemoteAddr())

	defer conn.Close()

	for {
		_, message, err := conn.ReadMessage()
		if err != nil {
			log.Printf("Command stream connection terminated: %s\n", conn.RemoteAddr())
			break
		}

		handleCommand(message)
	}
}

// forwardCommandToUds packages command buffers into `[Length u32][Payload JSON]` UDS frames.
func forwardCommandToUds(payload []byte) error {
	state.udsConnMutex.Lock()
	defer state.udsConnMutex.Unlock()

	if state.udsConn == nil {
		return fmt.Errorf("systems engine UDS connection is currently down")
	}

	// 1. Prepare packet length header (BigEndian 4B)
	lenBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(lenBuf, uint32(len(payload)))

	// 2. Write header
	_, err := state.udsConn.Write(lenBuf)
	if err != nil {
		return err
	}

	// 3. Write payload
	_, err = state.udsConn.Write(payload)
	if err != nil {
		return err
	}

	return nil
}

// ─────────────────────────────────────────────────────────────────────────────
// Pion WebRTC Signaling Server Implementation
// ─────────────────────────────────────────────────────────────────────────────

type WebRtcEnvelope struct {
	SDP string `json:"sdp"`
}

// handleWebRTCOffer handles custom client offers to establish high-speed UDP streams.
func handleWebRTCOffer(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var envelope WebRtcEnvelope
	err := json.NewDecoder(r.Body).Decode(&envelope)
	if err != nil {
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	// WebRTC Configuration
	config := webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{
				URLs: []string{"stun:stun.l.google.com:19302"},
			},
		},
	}

	peerConnection, err := webrtc.NewPeerConnection(config)
	if err != nil {
		log.Printf("Failed to instantiate WebRTC connection: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Command Receiver data channel
	peerConnection.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() == "commands" {
			log.Printf("WebRTC Command Data Channel opened: %s\n", dc.Label())
			dc.OnMessage(func(msg webrtc.DataChannelMessage) {
				handleCommand(msg.Data)
			})
		} else if dc.Label() == "display" {
			log.Printf("WebRTC Display Data Channel opened: %s\n", dc.Label())
			state.rtcChannelsMutex.Lock()
			state.rtcChannels[dc] = true
			state.rtcChannelsMutex.Unlock()

			dc.OnClose(func() {
				state.rtcChannelsMutex.Lock()
				delete(state.rtcChannels, dc)
				state.rtcChannelsMutex.Unlock()
				log.Println("WebRTC Display Data Channel closed.")
			})
		}
	})

	// Set remote offer SDP
	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  envelope.SDP,
	}

	err = peerConnection.SetRemoteDescription(offer)
	if err != nil {
		log.Printf("Failed to map remote WebRTC description: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Create and register local answer SDP
	answer, err := peerConnection.CreateAnswer(nil)
	if err != nil {
		log.Printf("Failed to generate WebRTC local answer SDP: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Initialize ICE gathering
	gatherComplete := webrtc.GatheringCompletePromise(peerConnection)
	err = peerConnection.SetLocalDescription(answer)
	if err != nil {
		log.Printf("Failed to establish local WebRTC description: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Wait for ICE candidates gathering to complete so we send a fully-resolved SDP
	<-gatherComplete

	responseSDP := peerConnection.LocalDescription().SDP
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(WebRtcEnvelope{SDP: responseSDP})
	log.Printf("Completed secure WebRTC SDP handshake with client!\n")
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom Macros Backup & Synchronization HTTP REST APIs
// ─────────────────────────────────────────────────────────────────────────────

type MacrosExportPayload struct {
	JSON string `json:"json"`
	TOML string `json:"toml"`
	YAML string `json:"yaml"`
}

func handleMacrosExportAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var payload MacrosExportPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		log.Printf("Error decoding macros export payload: %v\n", err)
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	// Ensure export directory exists
	exportDir := "./export"
	if err := os.MkdirAll(exportDir, 0755); err != nil {
		log.Printf("Error creating export directory: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Write JSON format
	if err := os.WriteFile(filepath.Join(exportDir, "macros.json"), []byte(payload.JSON), 0644); err != nil {
		log.Printf("Error writing macros.json: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Write TOML format
	if err := os.WriteFile(filepath.Join(exportDir, "macros.toml"), []byte(payload.TOML), 0644); err != nil {
		log.Printf("Error writing macros.toml: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	// Write YAML format
	if err := os.WriteFile(filepath.Join(exportDir, "macros.yaml"), []byte(payload.YAML), 0644); err != nil {
		log.Printf("Error writing macros.yaml: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	log.Printf("✓ Custom macros successfully exported to server in JSON, TOML, and YAML formats!\n")
	w.WriteHeader(http.StatusOK)
}

func handleMacrosImportAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	jsonPath := filepath.Join("./export", "macros.json")
	if _, err := os.Stat(jsonPath); os.IsNotExist(err) {
		log.Println("Import requested but no exported macros found on server.")
		http.Error(w, "Exported macros not found on server", http.StatusNotFound)
		return
	}

	data, err := os.ReadFile(jsonPath)
	if err != nil {
		log.Printf("Error reading macros.json: %v\n", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
	log.Println("✓ Custom macros successfully imported from server!")
}

// ─────────────────────────────────────────────────────────────────────────────
// Workstation File Sharing & Traverser HTTP REST APIs
// ─────────────────────────────────────────────────────────────────────────────

type FsItem struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	IsDir bool   `json:"isDir"`
	Size  int64  `json:"size"`
}

type FsListResponse struct {
	CurrentPath string   `json:"currentPath"`
	ParentPath  string   `json:"parentPath"`
	Items       []FsItem `json:"items"`
}

func handleFsListAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	targetPath := r.URL.Query().Get("path")
	if targetPath == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Printf("Error resolving home directory: %v\n", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}
		targetPath = home
	}

	targetPath = filepath.Clean(targetPath)
	dirEntries, err := os.ReadDir(targetPath)
	if err != nil {
		log.Printf("Error reading directory %s: %v\n", targetPath, err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	items := make([]FsItem, 0, len(dirEntries))
	for _, entry := range dirEntries {
		name := entry.Name()
		// Filter out dotfiles/hidden directories for a cleaner traversal experience
		if strings.HasPrefix(name, ".") {
			continue
		}
		info, err := entry.Info()
		var size int64 = 0
		if err == nil {
			size = info.Size()
		}
		absPath := filepath.Join(targetPath, name)
		items = append(items, FsItem{
			Name:  name,
			Path:  absPath,
			IsDir: entry.IsDir(),
			Size:  size,
		})
	}

	parentPath := filepath.Dir(targetPath)
	if parentPath == targetPath {
		parentPath = ""
	}

	resp := FsListResponse{
		CurrentPath: targetPath,
		ParentPath:  parentPath,
		Items:       items,
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(resp); err != nil {
		log.Printf("Error encoding fs list: %v\n", err)
	}
}

type trackingReader struct {
	r    io.Reader
	read *int64
}

func (tr *trackingReader) Read(p []byte) (n int, err error) {
	n, err = tr.r.Read(p)
	atomic.AddInt64(tr.read, int64(n))
	return
}

type trackingResponseWriter struct {
	http.ResponseWriter
	written *int64
}

func (trw *trackingResponseWriter) Write(p []byte) (n int, err error) {
	n, err = trw.ResponseWriter.Write(p)
	atomic.AddInt64(trw.written, int64(n))
	return
}

func handleFsUploadAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	dest := r.URL.Query().Get("dest")
	if dest == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Printf("Error resolving home: %v\n", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}
		dest = home
	}
	dest = filepath.Clean(dest)

	var bytesRead int64
	totalBytes := r.ContentLength
	doneChan := make(chan struct{})
	ticker := time.NewTicker(10 * time.Second)
	go func() {
		for {
			select {
			case <-ticker.C:
				read := atomic.LoadInt64(&bytesRead)
				var left int64
				if totalBytes > 0 {
					left = totalBytes - read
					if left < 0 {
						left = 0
					}
					log.Printf("[UPLOAD PROGRESS] Got %.2f MB / %.2f MB (%.1f%%), %.2f MB left\n",
						float64(read)/(1024*1024),
						float64(totalBytes)/(1024*1024),
						float64(read)*100.0/float64(totalBytes),
						float64(left)/(1024*1024))
				} else {
					log.Printf("[UPLOAD PROGRESS] Got %.2f MB (total size unknown)\n",
						float64(read)/(1024*1024))
				}
			case <-doneChan:
				ticker.Stop()
				return
			}
		}
	}()
	defer close(doneChan)

	r.Body = io.NopCloser(&trackingReader{r: r.Body, read: &bytesRead})

	mr, err := r.MultipartReader()
	if err != nil {
		log.Printf("Error reading multipart body: %v\n", err)
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			log.Printf("Error reading part: %v\n", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}

		fileName := part.FileName()
		if fileName == "" {
			continue
		}

		// Prevent directory traversal attacks
		targetFile := filepath.Clean(filepath.Join(dest, fileName))
		if !strings.HasPrefix(targetFile, dest) {
			log.Printf("Warning: blocked directory traversal attempt: %s\n", targetFile)
			continue
		}

		// Ensure recursive subfolders exist on the host
		parentDir := filepath.Dir(targetFile)
		if err := os.MkdirAll(parentDir, 0755); err != nil {
			log.Printf("Error creating subfolders: %v\n", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}

		// Write the file contents sequentially
		f, err := os.OpenFile(targetFile, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			log.Printf("Error creating file %s: %v\n", targetFile, err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}

		_, err = io.Copy(f, part)
		f.Close()
		if err != nil {
			log.Printf("Error writing file %s: %v\n", targetFile, err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
			return
		}
	}

	log.Printf("✓ File sharing upload successfully saved to target folder: %s\n", dest)
	w.WriteHeader(http.StatusOK)
}

type FsDownloadPayload struct {
	Paths []string `json:"paths"`
}

func handleFsDownloadAPI(w http.ResponseWriter, r *http.Request) {
	// Accept POST to safely pass a long array of paths
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var payload FsDownloadPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil || len(payload.Paths) == 0 {
		log.Printf("Error decoding download payload: %v\n", err)
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	var totalBytes int64
	for _, target := range payload.Paths {
		target = filepath.Clean(target)
		info, err := os.Stat(target)
		if err != nil {
			continue
		}
		if info.IsDir() {
			filepath.Walk(target, func(path string, fileInfo os.FileInfo, walkErr error) error {
				if walkErr == nil && !fileInfo.IsDir() {
					totalBytes += fileInfo.Size()
				}
				return nil
			})
		} else {
			totalBytes += info.Size()
		}
	}

	var bytesWritten int64
	doneChan := make(chan struct{})
	ticker := time.NewTicker(10 * time.Second)
	go func() {
		for {
			select {
			case <-ticker.C:
				written := atomic.LoadInt64(&bytesWritten)
				var left int64
				if totalBytes > 0 {
					left = totalBytes - written
					if left < 0 {
						left = 0
					}
					log.Printf("[DOWNLOAD PROGRESS] Sent %.2f MB / %.2f MB (%.1f%%), %.2f MB left\n",
						float64(written)/(1024*1024),
						float64(totalBytes)/(1024*1024),
						float64(written)*100.0/float64(totalBytes),
						float64(left)/(1024*1024))
				} else {
					log.Printf("[DOWNLOAD PROGRESS] Sent %.2f MB\n",
						float64(written)/(1024*1024))
				}
			case <-doneChan:
				ticker.Stop()
				return
			}
		}
	}()
	defer close(doneChan)

	trw := &trackingResponseWriter{ResponseWriter: w, written: &bytesWritten}

	// Case 1: Downloading exactly one file -> stream it directly
	if len(payload.Paths) == 1 {
		singlePath := filepath.Clean(payload.Paths[0])
		info, err := os.Stat(singlePath)
		if err == nil && !info.IsDir() {
			trw.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filepath.Base(singlePath)))
			trw.Header().Set("Content-Type", "application/octet-stream")
			http.ServeFile(trw, r, singlePath)
			return
		}
	}

	// Case 2: Downloading a folder or multiple files -> package in dynamic ZIP stream
	zipName := "download.zip"
	if len(payload.Paths) == 1 {
		// If zipping exactly one folder, name the zip after that folder
		zipName = filepath.Base(payload.Paths[0]) + ".zip"
	}

	trw.Header().Set("Content-Type", "application/zip")
	trw.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", zipName))

	zw := zip.NewWriter(trw)
	defer zw.Close()

	for _, target := range payload.Paths {
		target = filepath.Clean(target)
		info, err := os.Stat(target)
		if err != nil {
			log.Printf("Download target not found: %s\n", target)
			continue
		}

		baseDir := filepath.Dir(target)

		if info.IsDir() {
			// Walk and stream directory contents recursively
			err = filepath.Walk(target, func(path string, fileInfo os.FileInfo, walkErr error) error {
				if walkErr != nil {
					return walkErr
				}

				// Build relative path inside the zip structure
				relPath, err := filepath.Rel(baseDir, path)
				if err != nil {
					return err
				}

				header, err := zip.FileInfoHeader(fileInfo)
				if err != nil {
					return err
				}

				header.Name = relPath
				if fileInfo.IsDir() {
					header.Name += "/"
				} else {
					header.Method = zip.Deflate
				}

				writer, err := zw.CreateHeader(header)
				if err != nil {
					return err
				}

				if !fileInfo.IsDir() {
					f, err := os.Open(path)
					if err != nil {
						return err
					}
					defer f.Close()
					_, err = io.Copy(writer, f)
					if err != nil {
						return err
					}
				}
				return nil
			})
			if err != nil {
				log.Printf("Error walking folder %s: %v\n", target, err)
			}
		} else {
			// Add individual file to ZIP stream
			relPath := filepath.Base(target)
			header, err := zip.FileInfoHeader(info)
			if err != nil {
				continue
			}
			header.Name = relPath
			header.Method = zip.Deflate

			writer, err := zw.CreateHeader(header)
			if err != nil {
				continue
			}

			f, err := os.Open(target)
			if err != nil {
				continue
			}
			_, err = io.Copy(writer, f)
			f.Close()
		}
	}
	log.Printf("✓ File sharing dynamic ZIP download completed successfully!\n")
}

func handleFsMkdirAPI(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	path := r.URL.Query().Get("path")
	if path == "" {
		http.Error(w, "Missing path parameter", http.StatusBadRequest)
		return
	}
	path = filepath.Clean(path)

	if err := os.MkdirAll(path, 0755); err != nil {
		log.Printf("Error creating folder %s: %v\n", path, err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	log.Printf("✓ Securely created remote directory: %s\n", path)
	w.WriteHeader(http.StatusOK)
}
