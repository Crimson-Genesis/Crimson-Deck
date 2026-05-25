# AGY PL V2 Virtual Systems Controller (`agy-pl-v2-engine-rust`)

This is the systems-level core daemon responsible for instantiating a virtual mouse and virtual keyboard directly inside the Linux kernel using `/dev/uinput`.

---

## 1. Setup `/dev/uinput` Permissions & Error Handling

By default, the `/dev/uinput` character device is owned by `root:root` and can only be accessed with superuser permissions. Running systems applications as `root` is highly discouraged for security.

Follow these steps to grant permission to your standard user securely:

### Step 1: Create the `uinput` group
```bash
sudo groupadd -f uinput
```

### Step 2: Add your user to the `uinput` and `input` groups
```bash
sudo usermod -aG input,uinput $USER
```

### Step 3: Configure Udev Rules for Permanent Access
Create a new rule file under `/etc/udev/rules.d/` to ensure that every time the kernel initializes, the `/dev/uinput` device is assigned to the `uinput` group and made read-writable:

```bash
echo 'KERNEL=="uinput", GROUP="uinput", MODE="0660", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/99-uinput.rules
```

### Step 4: Reload Udev Rules and Trigger Device Nodes
```bash
sudo udevadm control --reload-rules && sudo udevadm trigger
```

### Step 5: Apply Group Changes Instantly
To apply group membership changes to your current shell session without restarting or logging out:
```bash
newgrp uinput
```

---

## 2. Compile & Run the Systems Engine

Verify your permissions and run the compiled Rust engine directly:

```bash
# Build the project in release mode
cargo build --release

# Run the virtual input daemon test runner
cargo run
```

---

## 3. Verify Events with `evtest`

`evtest` is the definitive terminal tool to capture and inspect Linux kernel input subsystem events.

### Step 1: Install `evtest`
On Arch Linux:
```bash
sudo pacman -S evtest
```

On Debian/Ubuntu:
```bash
sudo apt-get install evtest
```

### Step 2: Discover and Bind to the Virtual Device
Launch `evtest` in your terminal:
```bash
sudo evtest
```

It will print a numbered list of all active input devices on your workstation, for example:
```
Available devices:
/dev/input/event0:     Power Button
/dev/input/event1:     Lid Switch
...
/dev/input/event14:    Crimson Deck Virtual Systems Controller
Select the device event number [0-14]:
```

Type the number matching **"Crimson Deck Virtual Systems Controller"** and press `Enter`.

### Step 3: Observe Real-Time Synthetic Events
Run `cargo run` in another split terminal pane while `evtest` is attached. You will observe the raw kernel input packets printing in real time:

```
Input driver version is 1.0.1
Input device ID: bus 0x3 vendor 0x1234 product 0x5678 version 0x1
Input device name: "Crimson Deck Virtual Systems Controller"
Supported events:
  Event type 0 (EV_SYN)
  Event type 1 (EV_KEY)
    Event code 272 (BTN_LEFT)
    Event code 273 (BTN_RIGHT)
    Event code 274 (BTN_MIDDLE)
  Event type 2 (EV_REL)
    Event code 0 (REL_X)
    Event code 1 (REL_Y)
    Event code 8 (REL_WHEEL)
  Event type 3 (EV_ABS)
    Event code 0 (ABS_X)
      Value      0
      Min        0
      Max    32767
    Event code 1 (ABS_Y)
      Value      0
      Min        0
      Max    32767

Testing ... (interrupt to exit)
Event: time 1716508930.123456, type 2 (EV_REL), code 0 (REL_X), value 10
Event: time 1716508930.123456, type 2 (EV_REL), code 1 (REL_Y), value 10
Event: time 1716508930.123456, type 0 (EV_SYN), code 0 (SYN_REPORT), value 0
...
Event: time 1716508933.456789, type 1 (EV_KEY), code 272 (BTN_LEFT), value 1
Event: time 1716508933.456789, type 0 (EV_SYN), code 0 (SYN_REPORT), value 0
Event: time 1716508933.556789, type 1 (EV_KEY), code 272 (BTN_LEFT), value 0
Event: time 1716508933.556789, type 0 (EV_SYN), code 0 (SYN_REPORT), value 0
```

Congratulations! Your high-performance kernel-level synthetic virtual controller is fully configured and ready!
