package io.github.kreza6173pixel.pulsebattery.standby

import io.github.kreza6173pixel.pulsebattery.diag.DiagResult
import io.github.kreza6173pixel.pulsebattery.exec.ExecBridge
import io.github.kreza6173pixel.pulsebattery.exec.ExecOutcome
import io.github.kreza6173pixel.pulsebattery.exec.ShellQuoting

data class AppStandbyRow(
    val pkg: String,
    val bucketCode: Int?,
    /** Whitelist types for this package, e.g. system-excidle, system, user. */
    val whitelist: Set<String>,
) {
    val bucket: StandbyBucket? get() = StandbyBucket.fromCode(bucketCode)
    val userWhitelisted: Boolean get() = USER in whitelist
    val systemWhitelisted: Boolean get() = whitelist.any { it != USER }

    companion object {
        const val USER = "user"
    }
}

data class StandbySnapshot(
    val rows: List<AppStandbyRow>,
    val deepState: String?,
    val lightState: String?,
)

/** Outcome of a write. [ok] is true only when a read-back confirmed the change. */
data class ActionResult(
    val ok: Boolean,
    val message: String,
    val pkg: String? = null,
    val previousBucket: StandbyBucket? = null,
)

/**
 * M4 commands. Every call blocks on the UserService: call from Dispatchers.IO.
 * Package names are validated and single-quoted before they reach the shell.
 */
class StandbyRepository(private val bridge: ExecBridge) {

    private sealed interface Shell {
        data class Done(val exit: Int, val out: String, val err: String) : Shell
        data class Failed(val message: String) : Shell
    }

    private fun sh(command: String, timeoutMs: Int = TIMEOUT_MS): Shell =
        when (val o = bridge.execBlocking(command, timeoutMs)) {
            is ExecOutcome.Failed -> Shell.Failed(o.message)
            is ExecOutcome.Completed -> Shell.Done(o.result.exitCode, o.result.stdout, o.result.stderr)
        }

    fun load(includeSystem: Boolean): DiagResult<StandbySnapshot> {
        val first = when (val r = sh(BUCKETS_COMMAND)) {
            is Shell.Failed -> return DiagResult.Error(r.message, "")
            is Shell.Done -> r
        }
        var raw = first.out + first.err
        var buckets = StandbyParsers.parseBucketList(first.out)
        if (buckets.isEmpty()) {
            // The no-package form is not verified on every ROM: fall back to one loop.
            val loop = when (val r = sh(BUCKETS_LOOP_COMMAND, LOOP_TIMEOUT_MS)) {
                is Shell.Failed -> return DiagResult.Error(r.message, raw)
                is Shell.Done -> r
            }
            raw = raw + "\n--- fallback ---\n" + loop.out + loop.err
            buckets = StandbyParsers.parseBucketList(loop.out)
        }
        if (buckets.isEmpty()) return DiagResult.Error("no standby buckets in output", raw)

        val whitelist = when (val r = sh(WHITELIST_COMMAND)) {
            is Shell.Failed -> return DiagResult.Error(r.message, raw)
            is Shell.Done -> StandbyParsers.parseWhitelist(r.out)
        }

        val packages: Collection<String> = if (includeSystem) {
            // The bucket list comes from the usage statistics database, which remembers
            // packages that are no longer installed for this user. Offering those rows made
            // the write throw inside AppStandbyController, so they are dropped here.
            val installed = when (val r = sh(ALL_PACKAGES_COMMAND)) {
                is Shell.Failed -> return DiagResult.Error(r.message, raw)
                is Shell.Done -> StandbyParsers.parsePackageList(r.out).toSet()
            }
            if (installed.isEmpty()) {
                return DiagResult.Error("no installed packages in output", raw)
            }
            buckets.keys.filter { it in installed }
        } else {
            when (val r = sh(THIRD_PARTY_COMMAND)) {
                is Shell.Failed -> return DiagResult.Error(r.message, raw)
                is Shell.Done -> StandbyParsers.parsePackageList(r.out)
            }
        }

        val deep = readState(DEEP_COMMAND)
        val light = readState(LIGHT_COMMAND)
        val rows = packages.sorted().map { AppStandbyRow(it, buckets[it], whitelist[it].orEmpty()) }
        return DiagResult.Ok(StandbySnapshot(rows, deep, light), raw)
    }

    fun setBucket(pkg: String, target: StandbyBucket): ActionResult {
        if (!StandbyParsers.isValidPackage(pkg) || !target.settable) {
            return ActionResult(false, "refused: $pkg -> ${target.shellName}")
        }
        if (!isInstalled(pkg)) {
            return ActionResult(false, "$pkg: $REFUSE_NOT_INSTALLED", pkg)
        }
        val before = readBucket(pkg)
        val command = "am set-standby-bucket " + ShellQuoting.quote(pkg) + " " + target.shellName
        when (val r = sh(command)) {
            is Shell.Failed -> return ActionResult(false, r.message, pkg, before)
            is Shell.Done -> if (r.exit != 0) {
                return ActionResult(false, "exit ${r.exit}: " + (r.err + " " + r.out).trim(), pkg, before)
            }
        }
        val after = readBucket(pkg)
        if (after == target) {
            return ActionResult(true, "$pkg: ${nameOf(before)} -> ${target.shellName}", pkg, before)
        }
        return ActionResult(
            false,
            mismatchMessage(pkg, target, after, ExemptSource.NONE, false),
            pkg,
            before,
        )
    }

    /**
     * Root-only override for packages the system keeps exempt.
     *
     * It does exactly three things, in order: drop the removable `user` Doze whitelist entry when
     * the plan says so, write the bucket, read the bucket back. Success is claimed only when the
     * read-back equals the target; when the bucket write fails, a dropped whitelist entry is put
     * back so the device is left as it was found.
     */
    fun setBucketForced(
        row: AppStandbyRow,
        target: StandbyBucket,
        rootAvailable: Boolean,
    ): ActionResult {
        val pkg = row.pkg
        val plan = StandbyRootPolicy.plan(row, target, rootAvailable)
        if (!plan.allowed) {
            return ActionResult(false, "refused: ${plan.reason}", pkg.takeIf { it.isNotEmpty() }, row.bucket)
        }
        // Checked before anything is changed: a package the system does not have makes
        // AppStandbyController throw, which is not a result worth showing the user.
        if (!isInstalled(pkg)) {
            return ActionResult(false, "$pkg: $REFUSE_NOT_INSTALLED", pkg, row.bucket)
        }
        val before = readBucket(pkg)
        var droppedWhitelist = false
        if (plan.dropUserWhitelist) {
            val dropped = setUserWhitelisted(pkg, false)
            if (!dropped.ok) {
                return ActionResult(false, "whitelist step failed: ${dropped.message}", pkg, before)
            }
            droppedWhitelist = true
        }
        val command = "am set-standby-bucket " + ShellQuoting.quote(pkg) + " " + target.shellName
        when (val r = sh(command)) {
            is Shell.Failed -> {
                if (droppedWhitelist) setUserWhitelisted(pkg, true)
                return ActionResult(false, r.message, pkg, before)
            }
            is Shell.Done -> if (r.exit != 0) {
                if (droppedWhitelist) setUserWhitelisted(pkg, true)
                return ActionResult(
                    false,
                    "exit ${r.exit}: " + (r.err + " " + r.out).trim(),
                    pkg,
                    before,
                )
            }
        }
        val after = readBucket(pkg)
        if (after == target) {
            val note = if (droppedWhitelist) " (user Doze whitelist entry removed)" else ""
            return ActionResult(true, "$pkg: ${nameOf(before)} -> ${target.shellName}$note", pkg, before)
        }
        return ActionResult(
            false,
            mismatchMessage(pkg, target, after, plan.source, droppedWhitelist),
            pkg,
            before,
        )
    }

    /**
     * Builds the failure text, in order of how much it is worth: what was asked and what the
     * system reports, whether the bucket is being rewritten by something else, the framework's
     * own recorded reason, and only then a general explanation.
     *
     * The instability check and the recorded reason are facts about this package on this
     * device. The general sentence is a statement about how Android behaves, so it goes last.
     */
    private fun mismatchMessage(
        pkg: String,
        target: StandbyBucket,
        after: StandbyBucket?,
        source: ExemptSource,
        droppedWhitelist: Boolean,
    ): String {
        val lines = mutableListOf(
            "$pkg: asked ${target.shellName}, system reports ${nameOf(after)}."
        )

        // The recorded reason is shown in both branches: it is a fact either way.
        val recorded = readStandbyReason(pkg)?.reason
        val reasonLine = recorded?.let {
            val described = StandbyParsers.describeReason(it)
            if (described == null) "System reason: $it" else "System reason: $it, which is $described."
        }

        // Checked first because it makes every other explanation beside the point: a bucket
        // that moves on its own cannot hold any value, from this app or any other.
        if (movedOnItsOwn(pkg, after)) {
            lines += StandbyRootPolicy.REASON_UNSTABLE
            if (reasonLine != null) lines += reasonLine
        } else {
            if (reasonLine != null) lines += reasonLine
            lines += StandbyRootPolicy.explainMismatch(target, after, source, droppedWhitelist)
        }
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * Reads the bucket a few more times after the write and reports whether the system moved it
     * without being asked.
     *
     * On the reference device one resource-only package was observed flipping between exempted
     * and active every four to eight seconds. A single later read can land on the same value by
     * chance, so it is sampled several times and any change counts. This only runs on a
     * failure, so it never delays a write that worked.
     */
    private fun movedOnItsOwn(pkg: String, after: StandbyBucket?): Boolean {
        if (after == null) return false
        repeat(STABILITY_SAMPLES) {
            runCatching { Thread.sleep(STABILITY_STEP_MS) }
            val later = readBucket(pkg)
            if (later != null && later != after) return true
        }
        return false
    }

    /**
     * The framework's recorded bucket and reason for one package.
     *
     * grep runs on the device: the whole `dumpsys usagestats` output is far past the 64 KiB
     * transfer cap, and only this package's current row is wanted. History rows (`type=...`)
     * are removed on the device before `head`, because a package whose bucket keeps changing
     * has enough history rows to push the current row out of the first few matches.
     */
    private fun readStandbyReason(pkg: String): StandbyReason? {
        val pattern = ShellQuoting.quote("package=$pkg")
        val history = ShellQuoting.quote("type=")
        val command = "dumpsys usagestats | grep -F $pattern | grep -v -F $history | head -n $REASON_LINES"
        return when (val r = sh(command, REASON_TIMEOUT_MS)) {
            is Shell.Failed -> null
            is Shell.Done -> StandbyParsers.parseStandbyReason(r.out, pkg)
        }
    }

    fun setUserWhitelisted(pkg: String, on: Boolean): ActionResult {
        if (!StandbyParsers.isValidPackage(pkg)) return ActionResult(false, "refused: $pkg")
        val sign = if (on) "+" else "-"
        val command = "dumpsys deviceidle whitelist " + ShellQuoting.quote(sign + pkg)
        val printed = when (val r = sh(command)) {
            is Shell.Failed -> return ActionResult(false, r.message, pkg)
            is Shell.Done -> (r.out + " " + r.err).trim()
        }
        val nowOn: Boolean? = when (val r = sh(WHITELIST_COMMAND)) {
            is Shell.Failed -> null
            is Shell.Done -> AppStandbyRow.USER in StandbyParsers.parseWhitelist(r.out)[pkg].orEmpty()
        }
        return if (nowOn == on) {
            ActionResult(true, "$pkg: $printed", pkg)
        } else {
            ActionResult(false, "$pkg: $printed (read-back user whitelist = $nowOn)", pkg)
        }
    }

    fun forceIdle(): ActionResult = simple("dumpsys deviceidle force-idle")

    fun unforce(): ActionResult = simple("dumpsys deviceidle unforce")

    private fun simple(command: String): ActionResult = when (val r = sh(command)) {
        is Shell.Failed -> ActionResult(false, r.message)
        is Shell.Done -> ActionResult(r.exit == 0, (r.out + "\n" + r.err).trim().ifEmpty { "exit ${r.exit}" })
    }

    /** True when the package is installed for the current user. */
    private fun isInstalled(pkg: String): Boolean =
        when (val r = sh("pm path " + ShellQuoting.quote(pkg))) {
            is Shell.Failed -> false
            is Shell.Done -> r.out.lineSequence().any { it.trim().startsWith("package:/") }
        }

    private fun readBucket(pkg: String): StandbyBucket? =
        when (val r = sh("am get-standby-bucket " + ShellQuoting.quote(pkg))) {
            is Shell.Failed -> null
            is Shell.Done -> StandbyBucket.fromCode(StandbyParsers.parseSingleBucket(r.out))
        }

    private fun readState(command: String): String? = when (val r = sh(command)) {
        is Shell.Failed -> null
        is Shell.Done -> StandbyParsers.parseIdleState(r.out)
    }

    private fun nameOf(b: StandbyBucket?): String = b?.shellName ?: "unknown"

    companion object {
        const val BUCKETS_COMMAND = "am get-standby-bucket"
        const val BUCKETS_LOOP_COMMAND =
            "for p in \$(pm list packages -3 | cut -d: -f2); do echo \"\$p: \$(am get-standby-bucket \$p)\"; done"
        const val WHITELIST_COMMAND = "dumpsys deviceidle whitelist"
        const val THIRD_PARTY_COMMAND = "pm list packages -3"
        const val ALL_PACKAGES_COMMAND = "pm list packages"
        const val DEEP_COMMAND = "dumpsys deviceidle get deep"
        const val LIGHT_COMMAND = "dumpsys deviceidle get light"

        const val REFUSE_NOT_INSTALLED =
            "not installed for this user, so the system has no bucket to set. The bucket list " +
                "comes from usage statistics, which remembers packages that are gone."

        private const val TIMEOUT_MS = 15_000
        private const val LOOP_TIMEOUT_MS = 90_000

        /** dumpsys usagestats is a large dump; give it room but keep the output tiny. */
        private const val REASON_TIMEOUT_MS = 30_000
        private const val REASON_LINES = 3

        /**
         * Three reads, three seconds apart: nine seconds in total, which covers the four to
         * eight second interval observed on the reference device at least once.
         */
        private const val STABILITY_SAMPLES = 3
        private const val STABILITY_STEP_MS = 3_000L
    }
}
