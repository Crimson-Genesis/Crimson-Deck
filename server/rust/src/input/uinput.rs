// agy-pl-v2/rust/src/input/uinput.rs
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::AsRawFd;
use log::{info, error};

// ─────────────────────────────────────────────────────────────────────────────
// Linux Kernel input event structures (matching <linux/input.h> and <linux/uinput.h>)
// ─────────────────────────────────────────────────────────────────────────────

#[repr(C)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputSetup {
    id: InputId,
    name: [i8; 80],
    ff_effects_max: u32,
}

#[repr(C)]
struct UinputUserDev {
    name: [i8; 80],
    id: InputId,
    ff_effects_max: u32,
    absmax: [i32; 8], // ABS_CNT = 64, but we only configure a subset if legacy
    absmin: [i32; 8],
    absfuzz: [i32; 8],
    absflat: [i32; 8],
}

#[repr(C)]
struct InputEvent {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}

// System Ioctl Constants (Linux specific)
const UI_DEV_CREATE: libc::c_ulong = 0x5501;
const UI_DEV_DESTROY: libc::c_ulong = 0x5502;

const UI_SET_EVBIT: libc::c_ulong = 0x40045564;
const UI_SET_KEYBIT: libc::c_ulong = 0x40045565;
const UI_SET_RELBIT: libc::c_ulong = 0x40045566;
const UI_SET_ABSBIT: libc::c_ulong = 0x40045567;
const UI_DEV_SETUP: libc::c_ulong = 0x405c5503;

// Linux input event codes
const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const EV_ABS: u16 = 0x03;

const SYN_REPORT: u16 = 0;

// Mouse relative axes
const REL_X: u16 = 0x00;
const REL_Y: u16 = 0x01;
const REL_WHEEL: u16 = 0x08;

// Mouse absolute axes (Wayland/Touchscreen coordinates)
const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;

// Mouse buttons
pub const BTN_LEFT: u16 = 0x110;
pub const BTN_RIGHT: u16 = 0x111;
pub const BTN_MIDDLE: u16 = 0x112;

// ─────────────────────────────────────────────────────────────────────────────
// UInputDevice Manager
// ─────────────────────────────────────────────────────────────────────────────

pub struct UInputDevice {
    file: File,
}

impl UInputDevice {
    /// Instantiates a new virtual pointer (mouse) and keyboard device at the kernel level.
    pub fn new() -> io::Result<Self> {
        info!("Initializing high-performance virtual mouse and keyboard uinput client...");

        // 1. Open uinput character device file descriptor
        let file = OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open("/dev/uinput")
            .map_err(|e| {
                error!("Failed to open /dev/uinput. Verify permissions or membership in 'input' group: {}", e);
                e
            })?;

        let fd = file.as_raw_fd();

        unsafe {
            // 2. Configure event categories (Keypresses, relative moves, absolute moves, sync)
            Self::ioctl(fd, UI_SET_EVBIT, EV_SYN as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_EVBIT, EV_KEY as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_EVBIT, EV_REL as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_EVBIT, EV_ABS as libc::c_ulong)?;

            // 3. Register mouse relative/absolute control bits
            Self::ioctl(fd, UI_SET_RELBIT, REL_X as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_RELBIT, REL_Y as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_RELBIT, REL_WHEEL as libc::c_ulong)?;
            
            Self::ioctl(fd, UI_SET_ABSBIT, ABS_X as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_ABSBIT, ABS_Y as libc::c_ulong)?;

            // Register standard mouse buttons (Left, Right, Middle)
            Self::ioctl(fd, UI_SET_KEYBIT, BTN_LEFT as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT as libc::c_ulong)?;
            Self::ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE as libc::c_ulong)?;

            // 4. Register all keyboard key bit codes (scan codes 1 to 248)
            for key in 1..248 {
                Self::ioctl(fd, UI_SET_KEYBIT, key as libc::c_ulong)?;
            }

            // 5. Setup device specifications (Setup virtual details)
            let mut setup = UinputSetup {
                id: InputId {
                    bustype: 0x03, // USB Bus type
                    vendor: 0x1234,
                    product: 0x5678,
                    version: 1,
                },
                name: [0; 80],
                ff_effects_max: 0,
            };

            // Convert Rust string name to raw i8 C-string
            let name_str = "Crimson Deck Virtual Systems Controller";
            for (dest, src) in setup.name.iter_mut().zip(name_str.bytes()) {
                *dest = src as i8;
            }

            // Try to write setup info via modern UI_DEV_SETUP ioctl
            if libc::ioctl(fd, UI_DEV_SETUP, &setup) < 0 {
                info!("Fallback to legacy uinput setup method...");
                // Legacy fallback mechanism for older kernels
                let mut legacy_setup = UinputUserDev {
                    name: setup.name,
                    id: setup.id,
                    ff_effects_max: 0,
                    absmax: [0; 8],
                    absmin: [0; 8],
                    absfuzz: [0; 8],
                    absflat: [0; 8],
                };
                
                // Configure absolute coordinate ranges (for Touchscreen Absolute Mapping)
                legacy_setup.absmin[ABS_X as usize] = 0;
                legacy_setup.absmax[ABS_X as usize] = 32767; // High-precision scaling bounds
                legacy_setup.absmin[ABS_Y as usize] = 0;
                legacy_setup.absmax[ABS_Y as usize] = 32767;

                let setup_bytes = std::slice::from_raw_parts(
                    &legacy_setup as *const UinputUserDev as *const u8,
                    std::mem::size_of::<UinputUserDev>(),
                );
                
                let mut f = &file;
                f.write_all(setup_bytes)?;
            }

            // 6. Create the device in kernel space
            Self::ioctl(fd, UI_DEV_CREATE, 0)?;
        }

        info!("✓ Crimson Deck Virtual Input device successfully loaded in kernel.");
        Ok(UInputDevice { file })
    }

    /// Invokes raw libc ioctl safely.
    unsafe fn ioctl(fd: libc::c_int, request: libc::c_ulong, arg: libc::c_ulong) -> io::Result<()> {
        if libc::ioctl(fd, request, arg) < 0 {
            let err = io::Error::last_os_error();
            error!("ioctl system call error: {}", err);
            return Err(err);
        }
        Ok(())
    }

    /// Sends a precise event state packet down into the /dev/uinput driver.
    fn write_event(&mut self, type_: u16, code: u16, value: i32) -> io::Result<()> {
        let event = InputEvent {
            time: libc::timeval { tv_sec: 0, tv_usec: 0 },
            type_,
            code,
            value,
        };

        let bytes = unsafe {
            std::slice::from_raw_parts(
                &event as *const InputEvent as *const u8,
                std::mem::size_of::<InputEvent>(),
            )
        };

        self.file.write_all(bytes)?;
        Ok(())
    }

    /// Flushes input events to the kernel (forces synchronizing).
    pub fn sync(&mut self) -> io::Result<()> {
        self.write_event(EV_SYN, SYN_REPORT, 0)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Controls APIs
    // ─────────────────────────────────────────────────────────────────────────────

    /// Emulates keyboard keypresses or releases.
    pub fn inject_key(&mut self, key_code: u16, pressed: bool) -> io::Result<()> {
        let val = if pressed { 1 } else { 0 };
        self.write_event(EV_KEY, key_code, val)?;
        self.sync()
    }

    /// Emulates traditional relative mouse movement adjustments.
    pub fn inject_mouse_move_relative(&mut self, dx: i32, dy: i32) -> io::Result<()> {
        if dx != 0 {
            self.write_event(EV_REL, REL_X, dx)?;
        }
        if dy != 0 {
            self.write_event(EV_REL, REL_Y, dy)?;
        }
        self.sync()
    }

    /// Emulates absolute cursor positioning (perfect for mobile coordinate maps).
    pub fn inject_mouse_move_absolute(&mut self, x: i32, y: i32, max_x: i32, max_y: i32) -> io::Result<()> {
        // Map absolute incoming coordinate to uinput max bounds (32767)
        let mapped_x = (x * 32767) / max_x;
        let mapped_y = (y * 32767) / max_y;

        self.write_event(EV_ABS, ABS_X, mapped_x)?;
        self.write_event(EV_ABS, ABS_Y, mapped_y)?;
        self.sync()
    }

    /// Emulates mouse clicks (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE).
    pub fn inject_mouse_click(&mut self, button: u16, pressed: bool) -> io::Result<()> {
        let val = if pressed { 1 } else { 0 };
        self.write_event(EV_KEY, button, val)?;
        self.sync()
    }

    /// Emulates mouse vertical scroll wheel events.
    pub fn inject_mouse_scroll(&mut self, steps: i32) -> io::Result<()> {
        self.write_event(EV_REL, REL_WHEEL, steps)?;
        self.sync()
    }
}

impl Drop for UInputDevice {
    fn drop(&mut self) {
        let fd = self.file.as_raw_fd();
        info!("Tearing down virtual uinput client...");
        unsafe {
            let _ = libc::ioctl(fd, UI_DEV_DESTROY);
        }
    }
}
