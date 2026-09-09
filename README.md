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

Everything below is a row in the app's setup card, and every row disappears once it is satisfied.

1. **Shizuku** — install [Shizuku](https://shizuku.rikka.app/) (or Sui on a rooted device) and start it,
   then tap **Grant** in LuxRamp. This is what makes the ramp instant; without it LuxRamp still works, but
   the system does the ramping.
2. **Modify system settings** — needed to commit the settled brightness back to
   `Settings.System.SCREEN_BRIGHTNESS`, and to notice when you move the slider yourself.
3. **Notifications** — Android requires a notification for the foreground service. Grant it; you can hide
   the notification afterwards (step 5).
4. **Unrestricted battery**, and **Autostart** under *HyperOS extras* — HyperOS will otherwise freeze the
   service with the screen off, and will not bring it back after a reboot. Autostart lives in the Security
   app and cannot be read back, so LuxRamp can only offer to open it.
5. **Hide the notification** — under *HyperOS extras*, opens the `luxramp_service` channel's settings.
   Turning the channel off hides the notification; the service keeps running.

Then flip the master switch. The **Live** card shows raw and filtered lux against target and current
brightness, with the last two minutes as a trace. The **Tuning** card has the five numbers the loop is
made of — ramp up/down, smoothing up/down, and the offset — plus a preview of the curve they land on.

## Development

- **Build:** `cd android && ./gradlew :app:assembleDebug` (JDK 17, Android SDK 37).
- **Unit tests:** `cd android && ./gradlew :app:testDebugUnitTest`. The engine (`dev.nphil.luxramp.engine`)
  is pure Kotlin with no Android imports, so the maths — gamma percentages, curve interpolation, the
  filter and the ramper — is covered there.
- **Release builds** read the signing key from `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; the version comes from `LUXRAMP_VERSION` (`v1.2.3`).
- **Release:** push a `v*` tag. CI builds, signs and attaches `LuxRamp-vX.Y.Z.apk` to the release.
