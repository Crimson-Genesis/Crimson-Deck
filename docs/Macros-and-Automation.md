# GitLab Wiki: Macros and Automation

Crimson Deck (crimson-deck) includes a highly flexible keyboard macro sequence executor and backup synchronization engine, allowing users to automate complex terminal commands or workstation hotkeys from the phone.

---

## 1. Non-Blocking Coroutine Macro Executor

Macros are declared as text formulas and executed inside the Android client's background thread using Kotlin Coroutines:
* **Asynchronous Thread Execution**: Prevents macro execution loops from blocking the main UI thread, keeping the video stream and screen shares fully responsive during long keystroke chains.
* **Typing Pace Buffers**: To ensure the Linux workstation's keyboard buffers do not merge rapid inputs, the coroutine worker introduces an adjustable delay (defaulting to 120ms) between sequential actions.

---

## 2. Macro Syntax and Operators

The macro engine parses text formulas using two primary execution operators.

### A. Sequential Actions (Comma Operator `,`)
* **Syntax**: `step_one, step_two, step_three` (e.g. `l, s, enter`).
* **Execution**: Steps are executed sequentially. The engine presses and releases the keys for `step_one`, sleeps for 120ms, and continues down the list.

### B. Concurrent Combinations (Plus Operator `+`)
* **Syntax**: `modifier+key` (e.g. `ctrl+alt+t` or `super+shift+q`).
* **Execution**: Steps are executed concurrently:
  1. Down-clicks are triggered in left-to-right order: `keydown ctrl` $\rightarrow$ `keydown alt` $\rightarrow$ `keydown t`.
  2. Release-clicks are triggered in reverse order to mimic natural finger lift: `keyup t` $\rightarrow$ `keyup alt` $\rightarrow$ `keyup ctrl`.

---

## 3. UI Macro Builder and Cyberpunk Themes

* **Macro Console Builder**: An ergonomic builder dialog in the Settings screen allows users to test and assemble macros dynamically. Modifier keys can be appended to the active cursor selection via tapping buttons.
* **Custom 2D Color Picker Canvas**: Integrates a highly visual hue-value space canvas displaying saturated neon primary shades, secondary gradients, background layers, and panels. Draggable nodes (`P`, `S`, `B`, `PL`) update configurations persistently in local preferences.
* **Theme Coordination**: Macro creation tools and builder operator badges automatically match the active Cyberpunk theme's accent gradients in real-time.

### Theme Selector and 2D Interactive Canvas
The Settings screen incorporates an advanced 2D Hue-Value Canvas Color Picker to customize cyberpunk accent hues reactively:

![Theme Engine Canvas Picker](photos/4_setting_theam_section.jpeg)

### Integrated Sequence Macro Editor
A dynamic, non-scrollable popup editor makes setting modifiers and character delays intuitive and direct:

![Keyboard Macro Builder Editor](photos/6_setting_macro_builder_console.jpeg)

---

## 4. Multi-Format Serialization & Document Pickers

To maintain a minimal footprint, the application includes custom, zero-dependency data serializers and parsers.

### A. Format Specifications

#### JSON Serializer
Outputs standardized structural arrays:
```json
[
  {
    "name": "Terminal",
    "formula": "ctrl+alt+t",
    "delayMs": 120
  }
]
```

#### TOML Serializer
Formats configurations inside structured TOML lists:
```toml
[[macros]]
name = "Terminal"
formula = "ctrl+alt+t"
delayMs = 120
```

#### YAML Serializer
Generates clean YAML lists:
```yaml
macros:
  - name: Terminal
    formula: ctrl+alt+t
    delayMs: 120
```

### B. Native Android Storage Pickers
Integrates native `CreateDocument` and `OpenDocument` launchers to prompt the user for target backup directories or to load existing `.json`, `.toml`, or `.yaml` sheets. The application dynamically parses files based on their extension, auto-deduplicating and merging imported keys into the local database.

### Macros Console Dashboard
Manage, trigger, delete, and sync macros in a central Cyberpunk console panel:

![Macros Configuration List](photos/5_setting_macros_list_section.jpeg)

---

## 5. Server-Side REST Synchronization APIs

To avoid cluttering the WebSocket control channel, macro backups and synchronizations are managed over HTTP REST endpoints in the Go network gateway.

### A. Export Interface (`/api/macros/export` - POST)
* The Android client serializes its macro database and POSTs the payload to the Go server.
* The Go server dynamically creates an `./export` directory inside the server workspace and writes `macros.json`, `macros.toml`, and `macros.yaml` files immediately.

### B. Import Interface (`/api/macros/import` - GET)
* Tapping the Sync Import button queries the endpoint to fetch the workstation's active `macros.json` configuration.
* Merges and restores the macro list onto your phone, synchronizing settings instantly.
