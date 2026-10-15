package io.github.kreza6173pixel.pulsebattery.standby

/** android.app.usage.UsageStatsManager STANDBY_BUCKET_* codes and their `am` names. */
enum class StandbyBucket(val code: Int, val shellName: String, val settable: Boolean) {
    EXEMPTED(5, "exempted", false),
    ACTIVE(10, "active", true),
    WORKING_SET(20, "working_set", true),
    FREQUENT(30, "frequent", true),
    RARE(40, "rare", true),
    RESTRICTED(45, "restricted", true),
    NEVER(50, "never", false);

    companion object {
        fun fromCode(code: Int?): StandbyBucket? = entries.firstOrNull { it.code == code }

        val SETTABLE: List<StandbyBucket> get() = entries.filter { it.settable }
    }
}

/**
 * The framework's own record of a package's bucket, from `dumpsys usagestats`.
 *
 * [reason] is the short code the dump prints, such as `d` or `t` or `u-si`. It is kept exactly
 * as written and never rewritten, because the point of reading it is to stop guessing.
 */
data class StandbyReason(val bucketCode: Int?, val reason: String?)

/** Pure parsers for M4. Tested against real Xiaomi / Android 16 output where available. */
object StandbyParsers {

    private val PACKAGE_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val BUCKET_LINE = Regex("""^\s*([A-Za-z][A-Za-z0-9_.]*)\s*:\s*(\d+)\s*$""")
    private val STATE = Regex("""^[A-Z_]+$""")
    private val WHITESPACE = Regex("""\s+""")

    fun isValidPackage(name: String): Boolean = PACKAGE_NAME.matches(name)

    /** `am get-standby-bucket <pkg>` prints one integer, e.g. `5`. */
    fun parseSingleBucket(output: String): Int? =
        output.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()

    /** Lines of the form `com.example: 10`. Anything else is ignored. */
    fun parseBucketList(output: String): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (line in output.lineSequence()) {
            val m = BUCKET_LINE.matchEntire(line) ?: continue
            val pkg = m.groupValues[1]
            if (isValidPackage(pkg)) map[pkg] = m.groupValues[2].toInt()
        }
        return map
    }

    /** `dumpsys deviceidle whitelist`: lines `type,package,uid`. Returns package to types. */
    fun parseWhitelist(output: String): Map<String, Set<String>> {
        val map = LinkedHashMap<String, MutableSet<String>>()
        for (line in output.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.size < 2) continue
            val type = parts[0].trim()
            val pkg = parts[1].trim()
            if (type.isEmpty() || !isValidPackage(pkg)) continue
            map.getOrPut(pkg) { LinkedHashSet() }.add(type)
        }
        return map
    }

    /** `pm list packages`: lines `package:com.example`. */
    fun parsePackageList(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { isValidPackage(it) }
            .toList()

    /** `dumpsys deviceidle get deep|light` prints one word such as ACTIVE or IDLE. */
    fun parseIdleState(output: String): String? =
        output.trim().lineSequence().firstOrNull()?.trim()?.takeIf { STATE.matches(it) }

    /**
     * Picks the app-standby row for [pkg] out of `dumpsys usagestats` output.
     *
     * The row is a list of `key=value` tokens. Only an exact `package=<pkg>` token counts, so
     * a package whose name is a prefix of another cannot be mistaken for it. Rows that carry
     * `standbyBucket=` instead of `bucket=` are history events, not the current state, so they
     * are skipped: the dump prints both for the same package.
     */
    fun parseStandbyReason(output: String, pkg: String): StandbyReason? {
        val wanted = "package=$pkg"
        for (line in output.lineSequence()) {
            val tokens = line.trim().split(WHITESPACE)
            if (wanted !in tokens) continue
            if (tokens.any { it.startsWith("type=") }) continue
            val bucket = tokens.firstOrNull { it.startsWith("bucket=") }
                ?.removePrefix("bucket=")?.toIntOrNull()
            val reason = tokens.firstOrNull { it.startsWith("reason=") }
                ?.removePrefix("reason=")?.takeIf { it.isNotEmpty() }
            if (bucket != null || reason != null) return StandbyReason(bucket, reason)
        }
        return null
    }

    /**
     * Translates the short reason code the dump prints.
     *
     * The codes come from AOSP's `UsageStatsManager.reasonToString`, which abbreviates the main
     * reason to a letter and appends a sub-reason after a dash. Only the main letter is
     * translated here; an unknown code returns null so the caller shows the raw value instead
     * of inventing a meaning for it.
     */
    fun describeReason(reason: String?): String? {
        val main = reason?.substringBefore('-')?.trim()?.lowercase() ?: return null
        return when (main) {
            "d" -> "the framework's default, recomputed by the system"
            "t" -> "a bucket timeout, meaning the app went long enough without use"
            "u" -> "recent usage"
            "p" -> "the system's own prediction"
            "f" -> "forced by the user"
            "s" -> "forced by the system"
            else -> null
        }
    }
}
