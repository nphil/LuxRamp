# LuxRamp

Auto-brightness for HyperOS that reacts at the speed of the room, not the speed of a policy timer.

LuxRamp reads the ambient light sensor itself, maps lux to brightness with the device's own stock
curve, and drives the panel through Shizuku with `IDisplayManager.setTemporaryBrightness` — the same
call the system uses, but with a ramp you own.

## Why

HyperOS' display policy on the Xiaomi Pad 8 Pro, straight out of `dumpsys display`:

- The automatic ramp is **linear and slow**: roughly `0.008–0.025` brightness units per second
  (`brightnessRampRateSlowDecrease` / `…SlowIncrease` and their fast counterparts, on the 0..1 float
  scale). Walking from a dim hallway into a lit room is a five-to-thirty second fade.
- Small brightenings are **debounced for 5 s** (`brighteningLightDebounceConfigIdle` / the ambient
  brightening debounce). The screen has to be convinced the light really changed before it starts
  the slow ramp above.

The two compound: the panel spends most of a normal day's light changes visibly behind the room.
LuxRamp replaces that policy with a filter and a ramp you can see and tune — a fast asymmetric EMA on
lux, then a fixed-duration ramp interpolated in the perceptual (gamma) space the OS itself displays
percentages in.

## What it actually does

- Samples `Sensor.TYPE_LIGHT` while the screen is on; unregisters when it is off.
- Filters lux with an asymmetric EMA in the log domain — quick to brighten, slow to dim, so a passing
  shadow does not dim the screen.
- Maps filtered lux to brightness through the device's stock lux table, with your
  `screen_auto_brightness_adj` applied as the same gamma offset Android uses (`b' = b ^ (3 ^ -offset)`).
- Ramps to that target over a duration you choose, writing every 16 ms through Shizuku.
- Two seconds after the ramp settles, commits the result to `Settings.System.SCREEN_BRIGHTNESS`, so the
  system slider agrees with the screen.
- Watches that setting: if *you* drag the slider, LuxRamp solves for the offset that would have produced
  the brightness you picked and adopts it. Correcting the screen is how you tune the curve.
- Without Shizuku it falls back to writing `Settings.System.SCREEN_BRIGHTNESS`, which means the system
  performs the ramp — correct, but back at the slow rates above. The status line always names the writer
  in use.

Brightness on this device is a float `0..1` where `0.49975574` is the top of the normal range
(above it is the sunlight boost) and `0.001709819` is the floor; the integer setting scale is `0..512`.

## Install (Obtainium)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add an app with this URL: **`https://github.com/nphil/LuxRamp`**
3. Obtainium tracks releases and installs `LuxRamp-vX.Y.Z.apk`. The APK is signed with the project key
   (SHA-256 `69:F1:B9:46:51:95:E1:62:DF:C7:31:44:46:3A:04:4C:D9:DA:DC:43:B0:19:71:12:BF:17:80:0A:25:1D:BF:F3`).

Or download the APK from the [latest release](https://github.com/nphil/LuxRamp/releases/latest).

## Setup

The first launch is one screen that asks for everything at once, with a plain sentence under each row
saying what it buys you. **Set up everything** walks the outstanding items one at a time and stops on
its own when the list runs out or you decline something.

1. **Modify system settings** (required) lets LuxRamp save the brightness it settles on, so the system
   slider agrees with the screen, and notice when you move that slider yourself.
2. **Notifications** (required) because Android will not run a background service without one. You can
   hide it again afterwards under HyperOS extras and the service keeps going.
3. **Unrestricted battery** (required on HyperOS) or the service is frozen with the screen off.
4. **Shizuku** (optional, but the reason the app exists) makes the writes instant. Install
   [Shizuku](https://shizuku.rikka.app/), or Sui on a rooted device, start it, then authorise LuxRamp.
   Without it LuxRamp still works, but the system performs the ramp at the slow rates above.
5. **Display over other apps** (optional) is only needed for the floating panel.

Two HyperOS knobs cannot be read back, so they live behind a disclosure in Settings rather than
pretending to be checklist rows that never tick: **Autostart** in the Security app, which is what brings
the service back after a reboot, and **hide the notification**, which opens the `luxramp_service`
channel's own settings.

## The app

- **Master card**: the switch, and one line saying what is actually happening. If Shizuku is not
  connected it says so here, because that is the one degradation worth putting on the main screen.
- **Live**: the panel's brightness and the room's light as they happen, a gauge showing where the ramp
  is against where it is heading, and the last two minutes as a trace. The trace advances on the frame
  clock rather than on the 4 Hz sample rate, so it glides instead of stepping.
- **Preview**: replays your current tuning against a walk from a dim room into direct sun and back,
  looping with a beat between runs, including a swatch that lights and dims the way the panel would.
  Nothing in preview touches the screen.
- **Your curve versus stock**: the stock HyperOS curve and yours on one chart, with the band between
  them shaded. Drag across it to read any light level, drag up or down on it to brighten or dim your
  curve directly.
- **Tuning**: the five numbers the loop is made of, each with a sentence saying which way to drag it.
- **Floating panel**: a small always on top window with a brightness slider, a button that turns the
  automatic control off, and the ramp offset. It fades to a low opacity a few seconds after you stop
  touching it, collapses to a pill, and remembers where you parked it. Turn it on in Settings.
- **Settings**: theme mode, Material You, and twenty palettes shared with the HomeLabber app, plus the
  floating panel options, the deadband, and every permission again so a grant can be revisited.

## Development

- **Build:** `cd android && ./gradlew :app:assembleDebug` (JDK 17, Android SDK 37).
- **Unit tests:** `cd android && ./gradlew :app:testDebugUnitTest`. The engine (`dev.nphil.luxramp.engine`)
  is pure Kotlin with no Android imports, so the maths — gamma percentages, curve interpolation, the
  filter and the ramper — is covered there.
- **Release builds** read the signing key from `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; the version comes from `LUXRAMP_VERSION` (`v1.2.3`).
- **Release:** push a `v*` tag. CI builds, signs and attaches `LuxRamp-vX.Y.Z.apk` to the release.
