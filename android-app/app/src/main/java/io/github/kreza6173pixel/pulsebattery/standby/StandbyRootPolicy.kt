package io.github.kreza6173pixel.pulsebattery.standby

/**
 * Why a package cannot be moved out of its current bucket, as far as a shell can tell.
 *
 * This is the honest part of the feature: the framework derives EXEMPTED from several inputs
 * and only some of them are reachable from a shell, with or without root.
 */
enum class ExemptSource {
    /** Nothing holds the package: a normal, settable row. */
    NONE,

    /**
     * The `user` Doze whitelist, i.e. what the battery-optimisation screen writes or what an
     * app obtained through REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Removable from a shell.
     */
    USER_WHITELIST,

    /**
     * A `system` / `system-excidle` entry. The framework rebuilds that list at every boot from
     * the ROM's /etc/sysconfig XML, so `dumpsys deviceidle whitelist -pkg` cannot take it away:
     * only an /etc overlay (a module) could. Root does not change this.
     */
    SYSTEM_WHITELIST,

    /**
     * No whitelist entry explains the exemption: a carrier app, a headless system app, or an
     * active foreground/alarm reason. The write can be attempted but the framework may revert it.
     */
    FRAMEWORK,
}

/** What a forced write will do, decided before anything touches the device. */
data class ForcePlan(
    val allowed: Boolean,
    /** Remove the `user` whitelist entry before writing the bucket. */
    val dropUserWhitelist: Boolean,
    val source: ExemptSource,
    /** One English sentence, shown verbatim in the result card. */
    val reason: String,
)

/**
 * Pure decision logic for the root override. No Android, no shell: unit-tested on the JVM.
 */
object StandbyRootPolicy {

    fun exemptSource(row: AppStandbyRow): ExemptSource = when {
        row.systemWhitelisted -> ExemptSource.SYSTEM_WHITELIST
        row.userWhitelisted -> ExemptSource.USER_WHITELIST
        row.bucket == StandbyBucket.EXEMPTED || row.bucket == StandbyBucket.NEVER ->
            ExemptSource.FRAMEWORK
        else -> ExemptSource.NONE
    }

    fun plan(row: AppStandbyRow, target: StandbyBucket, rootAvailable: Boolean): ForcePlan {
        val source = exemptSource(row)
        if (!StandbyParsers.isValidPackage(row.pkg)) {
            return ForcePlan(false, false, source, REASON_BAD_PACKAGE)
        }
        if (!target.settable) {
            return ForcePlan(false, false, source, REASON_DERIVED_TARGET)
        }
        if (!rootAvailable) {
            return ForcePlan(false, false, source, REASON_NO_ROOT)
        }
        return when (source) {
            ExemptSource.NONE -> ForcePlan(true, false, source, REASON_PLAIN)
            ExemptSource.USER_WHITELIST -> ForcePlan(true, true, source, REASON_USER_WHITELIST)
            ExemptSource.SYSTEM_WHITELIST ->
                ForcePlan(true, row.userWhitelisted, source, REASON_SYSTEM_WHITELIST)
            ExemptSource.FRAMEWORK -> ForcePlan(true, row.userWhitelisted, source, REASON_FRAMEWORK)
        }
    }

    /**
     * Explains a read-back that did not match the target.
     *
     * This is deliberately separate from [ForcePlan.reason]. The plan describes what the write
     * was going to do, which only reads sensibly when the write sticks.
     *
     * These sentences are the last resort. The repository prefers two things that are facts
     * about the device: whether the bucket moved on its own, and the framework's own recorded
     * `reason=` field. Anything here is only a general statement about Android.
     */
    fun explainMismatch(
        target: StandbyBucket,
        after: StandbyBucket?,
        source: ExemptSource,
        droppedUserWhitelist: Boolean = false,
    ): String {
        if (after == null) return REASON_UNREADABLE
        if (after == target) return ""
        return when {
            after == StandbyBucket.ACTIVE && target.code > StandbyBucket.ACTIVE.code ->
                REASON_BACK_TO_ACTIVE

            after == StandbyBucket.EXEMPTED && droppedUserWhitelist ->
                REASON_RE_EXEMPTED_AFTER_DROP

            after == StandbyBucket.EXEMPTED && source == ExemptSource.SYSTEM_WHITELIST ->
                REASON_SYSTEM_WHITELIST

            after == StandbyBucket.EXEMPTED -> REASON_FRAMEWORK

            else -> REASON_OTHER_BUCKET
        }
    }

    const val REASON_BAD_PACKAGE = "the package name is not a valid Android package name."
    const val REASON_DERIVED_TARGET =
        "exempted and never are states the system derives, not targets a shell can set."
    const val REASON_NO_ROOT =
        "this override needs the privileged service to run as root (uid 0)."
    const val REASON_PLAIN = "nothing holds this package: a plain bucket write."
    const val REASON_USER_WHITELIST =
        "the removable user Doze whitelist entry is dropped first, then the bucket is written."
    const val REASON_SYSTEM_WHITELIST =
        "the ROM also whitelists this package in /etc/sysconfig; a shell cannot remove that " +
            "entry, so the system may re-exempt it. The read-back decides."
    const val REASON_FRAMEWORK =
        "no whitelist entry explains the exemption, so the system may revert the write. " +
            "The read-back decides."

    /**
     * The most useful thing that can be said about a failure, and it is measured rather than
     * assumed: the bucket was read twice and the system had moved it in between.
     */
    const val REASON_UNSTABLE =
        "This package's bucket is being rewritten by the system on its own: it changed again " +
            "within seconds, without anything being asked of it. No bucket written here can " +
            "hold, by this app or any other, while that is happening. Some ROMs do this through " +
            "their own power service, and resource-only packages with no activities are a " +
            "common target."

    /**
     * Deliberately does not name a cause. The framework raises a package to active for
     * several unrelated reasons and a shell cannot tell them apart; the recorded reason below
     * the message is the one that counts.
     */
    const val REASON_BACK_TO_ACTIVE =
        "the system raised the bucket back to active. It does that for recent use, for a " +
            "system interaction, for a bound widget, for a running foreground service, and some " +
            "ROMs re-assert buckets with their own power service. The system reason printed " +
            "below, when it could be read, is the authoritative one."

    const val REASON_RE_EXEMPTED_AFTER_DROP =
        "the user Doze whitelist entry was removed and the system exempted the package again " +
            "anyway, so something else is holding it: the ROM whitelist, or an active reason " +
            "the shell cannot see."

    const val REASON_OTHER_BUCKET =
        "the system chose a different bucket than the one asked for, so something else is " +
            "deciding this package's bucket."

    const val REASON_UNREADABLE =
        "the bucket could not be read back, so whether the write stuck is unknown. Nothing is " +
            "claimed either way."
}
