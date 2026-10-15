# Root mode

Branch: `root-mode-v1`. Target version: 1.1.0 (versionCode 2).

This is the record of the root track: what was added, which command each control runs, how
the result is verified, and what was proposed but deliberately left out.

## The one rule everything follows

A control is only reported as applied when a **read-back** returns the value that was asked
for. Every repository in this track writes, then reads, then compares. When the system
silently reverts a write, the UI says so instead of claiming success. No exceptions were made
for root: root widens what can be attempted, it never widens what may be claimed.

## Verification status

Confirmed by hand on the reference device unless this table says otherwise. Anything not
confirmed is called out in the app itself as well, not only here.

| Control | Status | Notes |
| --- | --- | --- |
| Access detection (Sui / Shizuku, root uid) | Verified | `uid 0 (root)`, provider `Sui` |
| Charge protection, all three modes | Verified | read-back matched for `key` 0, 1 and 2 |
| Battery health | Verified | `cycle_count=263`, `charge_full=5348800`; `soh20_aging` returns an I/O error on this device and is skipped |
| Charge gate, pause and resume | Verified | holding the gate makes `status` read `Unknown` on this ROM |
| Charge limit thresholds | Verified | stopped at 85 %, notification showed `charging held` |
| CPU by app | Verified | 40 uids, 75 h 40 m total |
| Standby root override | Verified | including a refusal for a package the usage database remembers but that is no longer installed |
| Wakelocks and background (appops) | Verified | `WAKE_LOCK: default -> ignore` confirmed by read-back |
| Full data backup and restore | Verified, with a known limit | reached through init's mount namespace; keystore-bound secrets do not come back, see below |
| **Keep enforcing after a reboot** | **NOT VERIFIED** | the switch and both boot paths are implemented and compile, but no reboot test has been run on hardware. Treat it as unproven and check the charge state after the first reboot. |

### Known partial results

These are not bugs and no amount of root changes them:

- **A data restore cannot bring back keystore-bound secrets.** Secrets an app encrypts with an
  Android Keystore key stay unreadable: the key never leaves the secure world, and clearing
  app data deletes the app's keystore entries. The ciphertext restores, the key does not.
  Observed with API keys in a restored app, which had to be entered again.
- **A ROM whitelist entry can re-exempt an app** after a forced bucket write. The read-back
  reports that honestly rather than claiming the change stuck.

## Access layer

`access/AccessMode.kt` resolves one value, `rootAvailable`, from observable facts only:

- the provider that answers the binder API: the Sui module, or the Shizuku manager app
- whether the service reached READY
- the real uid the service runs with, via `Shizuku.getUid()`

`rootAvailable` is true only when that uid is 0. An installed root manager proves nothing
about this process, so it is never used as a signal. Priority: Sui, then the Shizuku manager
app, then nothing.

Reference device: Redmi Note 14 4G (`tanzanite`, `mt6789`), HyperOS 3, Android 16 / SDK 36,
KernelSU with the Sui module 13.5.4.3.

## What the track added

| Area | What it does | Commands / nodes |
| --- | --- | --- |
| Charge protection | The three HyperOS modes: charge fully, intelligent charging, battery protection | secure setting `security_pc_secure_protect_mode_key`, values 0 / 1 / 2 |
| Battery health | Read-only kernel view: capacity, cycles, voltage, current, temperature, technology | `/sys/class/power_supply/*` |
| Charge gate | Pause and resume charging now | `/sys/class/power_supply/battery/input_suspend` |
| Charge limit | Optional opt-in service that gates charging at a chosen level and recharges below a floor | the same node, driven by a foreground service |
| CPU by app | Per-uid CPU time attributed to packages | `/proc/uid_cputime/show_uid_stat`, names for platform uids from `id -nu` |
| Standby and Doze | Buckets, the Doze whitelist, forced idle, plus the root override below | `am set-standby-bucket`, `am get-standby-bucket`, `dumpsys deviceidle` |
| Wakelocks and background | Per-app wakelock and background-work blocking | `cmd appops set <pkg> WAKE_LOCK\|RUN_IN_BACKGROUND\|RUN_ANY_IN_BACKGROUND ignore` |
| Full data backup | Archive and restore the private data directory, plus external data and obb | `tar` inside init's mount namespace, staged through `/data/local/tmp` |

### Capability checks, not assumptions

The HyperOS protection mode is capability-checked: another HyperOS 3 device (Poco F5) has no
mode 2, and an absent setting key reads as intelligent charging rather than as an error. The
charge nodes are probed before use: on `tanzanite`, `charge_control_limit` is a thermal
current **level** (0 to 16), not a percentage, so it is never written as one, and
`battery_charging_enabled` / `charging_enabled` do not exist at all.

### App data isolation

Being root is not enough to read `/data/data/<pkg>`. Android 11 and newer give each app
process a mount namespace containing only its own data directory, and the privileged user
service inherits one of those, so another app's directory reports `No such file or directory`
rather than a permission error. The data backup probes for this and, when the directory is
invisible, re-runs the command inside init's namespace with
`nsenter --mount=/proc/1/ns/mnt`.

That namespace has no per-user `/sdcard` view, so archives are never written there directly.
They are staged in `/data/local/tmp`, which is the same directory in both namespaces, and
copied to the vault by the ordinary shell afterwards.

Restore refuses unless the package is installed, restores ownership from the live uid of the
data directory, and runs `restorecon` last. When `restorecon` cannot run the result is
reported as NOT applied even though the files were written, because wrong SELinux labels mean
the app crashes on launch.

### The standby root override

`standby/StandbyRootPolicy.kt` first names why a package cannot leave its bucket:

- **user Doze whitelist**: removable from a shell, so it is dropped before the bucket write
- **ROM whitelist** (`system`, `system-excidle`): rebuilt at every boot from the ROM's
  `/etc/sysconfig` XML. A shell cannot remove it, root included; only an `/etc` overlay could.
  The write is still attempted, the limitation is disclosed, and the read-back decides.
- **the framework itself**: no whitelist entry explains the exemption (carrier app, headless
  system app, active reason). Attempted, verified, and reported honestly.

`exempted` and `never` are never offered as targets: the system derives them, a shell cannot
set them. A failed bucket write restores a whitelist entry that was dropped, so the device is
left as it was found.

The bucket list comes from the usage statistics database, which remembers packages that are
no longer installed. Those are filtered out of the list and refused by the write paths,
because attempting one throws inside `AppStandbyController`.

### The appops screen needs no root

`cmd appops` is settable with the shell uid, so this screen works in shell mode too. It is
offered with the breakage stated on screen: WAKE_LOCK set to `ignore` makes every wakelock
that app takes a silent no-op, which can break its alarms, downloads, music and messaging.
An op that has never been used is not printed by `appops get`, so an absent entry and the
word `default` are folded into the same value; the read-back accepts both.

## Dropped from the original proposal, with reasons

1. **Kernel wakelock nodes** (`/sys/power/wake_lock`, `wake_unlock`). These create and release
   *kernel* wakelocks by name. They cannot block a wakelock an app takes through
   PowerManager. Replaced by the AppOps `WAKE_LOCK ignore` path, which actually does that.
2. **CPU governor writes.** The HyperOS performance and thermal services own
   `scaling_governor` and rewrite it within seconds. A control that cannot hold its value
   fails the read-back rule by design, so it was not shipped.
3. **iptables / traffic rules.** Not a battery feature. It belongs to the other project.
4. **`kill -9`.** Strictly weaker than `am force-stop`, which also tells the framework not to
   restart the process. No reason to ship the weaker one.
5. **A resident root daemon.** Unnecessary: when Sui or Shizuku is started as root, the user
   service already runs as uid 0. A second privileged process would add attack surface and
   buy nothing.
6. **Parsing `batterystats.bin`.** A private binary format whose layout changes between
   Android versions. `dumpsys batterystats` text and `/proc` are parsed instead, with unit
   tests built from real device output.
7. **`charge_control_limit` as a percentage.** Verified on the reference device to be a
   current level, not a percentage. Writing a percentage there would throttle current and
   look like a working limit while never stopping the charge.
8. **Backing up and restoring the keystore database.** On the same device it is theoretically
   possible, since the secure world can still unwrap the blobs. It was not shipped: the
   database is shared by every app, keys are bound to an app uid that changes on reinstall,
   and auth-bound keys are invalidated by any lockscreen or biometric change. The risk of
   breaking the keystore for the whole device outweighs recovering a few secrets.

## Testing

Pure logic is unit-tested on the JVM: `StandbyRootPolicyTest`, `AppOpsParsersTest`,
`DataVaultParsersTest`, `UidCpuNamesTest`, `ChargeLimitPolicyTest`, plus the existing parser
suites. Parsers are built from real output captured on the reference device. UI and device
behaviour are verified by hand on the phone, one feature at a time, and the verification
table above is the record of what has actually been through that.
