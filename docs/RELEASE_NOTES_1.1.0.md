# PULSE // BATTERY 1.1.0 - Root mode

## Tested on a real rooted phone, not in an emulator

Every root feature here was run on a physical device before it was accepted:

- **Phone:** Redmi Note 14 4G (`tanzanite`, MediaTek `mt6789`)
- **System:** HyperOS 3, Android 16 (SDK 36)
- **Root:** **KernelSU**, with the **Sui** module serving the privileged API as `uid 0`

Each feature landed as its own commit and was tested on that phone before the next one was
written, so the history shows which commit was proven by which test.

**One exception, stated on purpose:** *Keep enforcing after a reboot* is implemented but has
**not** been through a reboot test. It is marked unverified inside the app and in the docs.

## New with root

- **Charge protection.** The three HyperOS modes: charge fully, intelligent charging, battery
  protection. Capability-checked, because not every HyperOS device has all three.
- **Automatic charge limit.** Stop at any level you pick, resume below a floor you pick. This is
  the one background component in the app: opt-in, permanent notification, stops itself.
- **Manual charge gate.** Hold or release charging right now.
- **Battery health.** Cycle count, full charge versus design capacity, voltage, current,
  temperature, manufacture date, straight from the kernel.
- **CPU time per app.** From the kernel uid statistics rather than battery stats. Platform
  accounts such as `root` and `logd` are named; packages sharing a uid are grouped.
- **Standby override for protected apps.** PULSE names what is holding an app exempt (your Doze
  whitelist, the ROM's own whitelist, or the framework), drops the removable entry and writes
  the bucket anyway. A failed write puts the whitelist entry back. When the system takes the
  bucket back, the app shows the system's own recorded reason and whether the bucket is being
  rewritten on its own.
- **Full app data backup and restore.** The whole private data directory plus external data and
  obb. The app is stopped first, the archive is verified before it counts, and ownership plus
  SELinux labels are restored afterwards.

## New without root

- **Wakelocks and background.** Block an app's wakelocks, its background services, or any
  background work, and put them back with one tap. Shizuku is enough for this.

## The rule nothing broke

A change is only reported as applied when a **read-back** returns the value that was asked for.
If the system silently undoes something, the app says so instead of showing a green tick. Root
widens what can be attempted; it never widens what may be claimed.

Honest limits, stated in the app itself:

- A data restore cannot bring back secrets an app encrypted with an Android Keystore key. The
  key never leaves the secure hardware and clearing app data deletes it.
- A ROM whitelist entry can re-exempt an app after a forced bucket write. The read-back reports
  that rather than claiming success.
- Some system packages have their bucket recomputed by the framework every few seconds. On the
  test phone `android.miui.home.launcher.res` flips between exempted and active with reason
  `d` (framework default). No tool can hold a value the system rewrites that often, and PULSE
  reports it as unstable instead of pretending.

## Not shipped, on purpose

Kernel wakelock nodes, CPU governor writes, iptables rules, `kill -9`, a resident root daemon,
`batterystats.bin` parsing, `charge_control_limit` as a percentage, and keystore database
backup. Each has a written reason in [docs/ROOT_MODE.md](ROOT_MODE.md).

## Still true

No internet permission. No account, no ads, no trackers. Every command runs through the
Shizuku user service, package names are validated and shell-quoted, and the parsers are pure
Kotlin with unit tests built from real device output.
