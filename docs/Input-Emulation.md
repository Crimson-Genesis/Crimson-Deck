# GitLab Wiki: Input Emulation

Crimson Deck (crimson-deck) mimics mouse, keyboard, clipboard, and desktop environment actions natively on the Linux workstation using pure user-space tools and local desktop IPC.

---

## 1. User-Space Interaction Layer

By using xdotool and xclip in the standard user context, the system emulates hardware interactions cleanly without needing root uinput execution permissions for command processing.

---

## 2. Advanced Touch Pointer Mechanics

The application supports two distinct pointer interaction profiles, configured inside the settings panel.

### A. Absolute touch Mapping
Tapping or dragging on the phone maps your finger's coordinates absolutely to the workstation's screen dimensions.
* **Scale-Aware Coordinate Mapping**: The Go network gateway receives normalized coordinate payloads `(x, y, max_x, max_y)` indicating the finger's position relative to the phone's rendering viewport.
* **Scale Equations**: The Go server queries the active X11/Wayland display boundaries and scales coordinates:
  * `HostX = (x * HostScreenWidth) / max_x`
  * `HostY = (y * HostScreenHeight) / max_y`
* **Absolute Move Execution**: Cursor position is updated instantly using `xdotool mousemove --sync <HostX> <HostY>`.

### B. Relative Panning & Centroid Zooming
* **Centroid Zooming**: When zooming in, the Compose canvas tracks touch gesture centroids to scale viewport transforms smoothly, keeping the image segment under your fingers completely stable.
* **Edge-Locking Clamping**: Clamps panning offsets to prevent the scaled screen from pulling past the canvas borders. The image edges remain locked to the viewport boundaries.
* **Relative Trackpad Control**: Tracks delta movement shifts `(dx, dy)` and translates them into relative cursor motions using `xdotool mousemove_relative -- <dx> <dy>`.

### C. Tactical Cursor & Drag-to-Select Gestures
* **Glowing Target Pointer**: Renders a custom glowing crimson target ring with a white core at your active touch location on the phone's canvas. It automatically self-hides after 3 seconds of inactivity to keep the canvas clear.
* **Drag-to-Select Gestures**: Implements click-and-drag mechanics via long-press gestures. Long-pressing maps the cursor position, executes a host mouse down (`mousedown 1`), and streams absolute movements to select regions in real-time, releasing upon lift (`mouseup 1`).

---

## 3. Keyboard Emulation

Keystrokes are converted into physical events on the workstation using scan code translation tables.

### A. Scan Code Translation
* The Go server maps all coming symbol characters and developer control keys to their respective Linux evdev scan codes.
* **Shift-State Symbol Parsing**: Punctuation marks (such as `-`, `_`, `=`, `[`, `]`, `{`, `}`, `;`, `:`, `'`, `"`, `/`, `\`, `.`, `~`, `` ` ``) are dynamically resolved to their base key scan code. For example, the underscore `_` is translated to scan code `12` (the minus key) and paired with an automated Shift key press.
* **Caps-State Combination Injection**: Single uppercase characters trigger automated Shift combinations, and combination operators (e.g. `"ctrl+alt+A"`) resolve capital letters into their base code with the `shift` modifier injected at the front.

### B. Soft Keyboard & BKSP Fixes
* **IME Sync Fix**: Direct Keyboard mode uses Compose `TextFieldValue` tracking to force a single-space character buffer and reset the composition state after each delete. This keeps the soft keyboard's delete key fully enabled and responsive during rapid, consecutive tapping.
* **Repeating Key Actions**: Modifying the delete button (`BKSP`) or allowed keys (such as `TAB`, `ENT`, arrows) using `RepeatingSystemKeyButton` intercepts touch gestures to fire keystroke commands repeatedly after a user-configured delay (20ms default, scale-adjustable inside settings).

---

## 4. Workstation Workspace Control (i3wm IPC Socket)

Rather than simulating complex hotkey combinations, the system commands the i3wm window manager directly via its Unix IPC socket.

```
Phone UI Swipe Gesture / Workspace Button Tap
                       │
                       ▼
       Go Server (main.go IPC Listener)
                       │
                       ▼
Scans /run/user/1000/i3/ipc-socket.* Unix sockets
                       │
                       ▼
      Sends JSON Command over Unix Socket:
    `i3-msg workspace number <workspace_num>`
```

* **Socket Discovery**: The Go server dynamically scans `/run/user/1000/i3/ipc-socket.*` (and falls back to `/tmp/i3-*.*/ipc-socket.*` structures) to find the active Unix domain IPC socket used by your desktop's i3 window manager.
* **Direct IPC Execution**: When switching workspaces or hitting the **FULL** button, it writes to the socket directly:
  * `i3-msg workspace number <num>`
  * `i3-msg fullscreen toggle`
* **Custom Suffix Parsing**: The use of `workspace number <num>` commands i3wm to switch workspaces using only numerical prefixes, supporting workspace sheets with custom suffixes or icons natively.
