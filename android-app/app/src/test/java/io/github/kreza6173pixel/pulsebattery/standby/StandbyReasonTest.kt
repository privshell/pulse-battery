package io.github.kreza6173pixel.pulsebattery.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyReasonTest {

    /**
     * Verbatim rows from the reference device. The reason field is AOSP's short code, a single
     * letter, not the long form the parser was first written against.
     */
    private val dump = """
         package=com.miui.screenrecorder u=0 bucket=50 reason=d used=<uninitialized> usedByUser=<uninitialized> lastInformedBucket=50 idle=y
         package=com.devrinth.launchpad u=0 bucket=30 reason=t used=+1d2h53m11s584ms usedByUser=+1d2h53m17s260ms lastInformedBucket=30 idle=n
         package=org.fossify.contacts u=0 bucket=50 reason=t used=<uninitialized> lastInformedBucket=50 idle=y
    """.trimIndent()

    /** History events for the same package. These are not the current state. */
    private val history = """
         time="2026-10-11 08:40:17" type=STANDBY_BUCKET_CHANGED package=android.miui.home.launcher.res standbyBucket=5 reason=d flags=0x0
         time="2026-10-11 08:40:21" type=STANDBY_BUCKET_CHANGED package=android.miui.home.launcher.res standbyBucket=10 reason=d flags=0x0
    """.trimIndent()

    @Test
    fun readsTheRecordedBucketAndReason() {
        val found = StandbyParsers.parseStandbyReason(dump, "com.miui.screenrecorder")
        assertEquals(50, found?.bucketCode)
        assertEquals("d", found?.reason)
    }

    @Test
    fun readsATimeoutRow() {
        val found = StandbyParsers.parseStandbyReason(dump, "com.devrinth.launchpad")
        assertEquals(30, found?.bucketCode)
        assertEquals("t", found?.reason)
    }

    @Test
    fun historyEventsAreNotMistakenForTheCurrentState() {
        // These rows carry type= and standbyBucket=, not bucket=. Reading them would report a
        // value from minutes ago as if it were current.
        assertNull(StandbyParsers.parseStandbyReason(history, "android.miui.home.launcher.res"))
    }

    @Test
    fun theCurrentRowIsStillFoundAmongHistoryEvents() {
        val mixed = history + "\n package=android.miui.home.launcher.res u=0 bucket=10 reason=d idle=n"
        val found = StandbyParsers.parseStandbyReason(mixed, "android.miui.home.launcher.res")
        assertEquals(10, found?.bucketCode)
        assertEquals("d", found?.reason)
    }

    @Test
    fun anAbsentPackageReturnsNothing() {
        assertNull(StandbyParsers.parseStandbyReason(dump, "com.not.here"))
        assertNull(StandbyParsers.parseStandbyReason("", "com.miui.screenrecorder"))
    }

    @Test
    fun aPrefixOfAnotherPackageIsNotMatched() {
        assertNull(StandbyParsers.parseStandbyReason(dump, "org.fossify"))
        assertNull(StandbyParsers.parseStandbyReason(dump, "org.fossify.contacts.extra"))
    }

    @Test
    fun theShortCodesAreTranslated() {
        assertNotNull(StandbyParsers.describeReason("d"))
        assertNotNull(StandbyParsers.describeReason("t"))
        assertNotNull(StandbyParsers.describeReason("u"))
        assertNotNull(StandbyParsers.describeReason("p"))
        assertTrue(StandbyParsers.describeReason("t")!!.contains("timeout"))
        assertTrue(StandbyParsers.describeReason("u")!!.contains("usage"))
    }

    @Test
    fun aSubReasonDoesNotChangeTheMainMeaning() {
        assertEquals(
            StandbyParsers.describeReason("u"),
            StandbyParsers.describeReason("u-si"),
        )
    }

    @Test
    fun anUnknownCodeIsNotGuessedAt() {
        // The caller shows the raw value when this returns null.
        assertNull(StandbyParsers.describeReason("zz"))
        assertNull(StandbyParsers.describeReason(""))
        assertNull(StandbyParsers.describeReason(null))
    }

    @Test
    fun theUnstableMessageSaysNoToolCanWin() {
        val why = StandbyRootPolicy.REASON_UNSTABLE
        assertTrue(why.contains("within seconds"))
        assertTrue(why.contains("or any other"))
    }

    @Test
    fun theFallbackSentenceNamesNoSingleCause() {
        val why = StandbyRootPolicy.REASON_BACK_TO_ACTIVE
        assertTrue(why.contains("recent use"))
        assertTrue(why.contains("authoritative"))
        assertEquals(false, why.contains("such as a launcher"))
    }
}
