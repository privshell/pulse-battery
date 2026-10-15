package io.github.kreza6173pixel.pulsebattery.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyMismatchTest {

    @Test
    fun backToActiveGetsTheActiveExplanation() {
        // The shape the device reported for android.miui.home.launcher.res.
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RESTRICTED,
            after = StandbyBucket.ACTIVE,
            source = ExemptSource.USER_WHITELIST,
            droppedUserWhitelist = true,
        )
        assertEquals(StandbyRootPolicy.REASON_BACK_TO_ACTIVE, why)
    }

    @Test
    fun theActiveExplanationIsAboutTheOutcomeNotTheAttempt() {
        val why = StandbyRootPolicy.REASON_BACK_TO_ACTIVE
        // The original bug: a failure explained by the plan's description of the write.
        assertFalse(why.contains("is dropped first"))
        // It says what happened, and defers to the recorded reason instead of naming one.
        assertTrue(why.contains("back to active"))
        assertTrue(why.contains("authoritative"))
    }

    @Test
    fun activeIsNotAMismatchWhenActiveWasAsked() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.ACTIVE,
            after = StandbyBucket.ACTIVE,
            source = ExemptSource.NONE,
        )
        assertEquals("", why)
    }

    @Test
    fun aLowerBucketWhenActiveWasAskedIsNotTheActiveCase() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.ACTIVE,
            after = StandbyBucket.RARE,
            source = ExemptSource.NONE,
        )
        assertEquals(StandbyRootPolicy.REASON_OTHER_BUCKET, why)
    }

    @Test
    fun reExemptedAfterDroppingTheWhitelistSaysSo() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RARE,
            after = StandbyBucket.EXEMPTED,
            source = ExemptSource.USER_WHITELIST,
            droppedUserWhitelist = true,
        )
        assertEquals(StandbyRootPolicy.REASON_RE_EXEMPTED_AFTER_DROP, why)
    }

    @Test
    fun stillExemptedWithARomEntryBlamesTheRom() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RARE,
            after = StandbyBucket.EXEMPTED,
            source = ExemptSource.SYSTEM_WHITELIST,
        )
        assertEquals(StandbyRootPolicy.REASON_SYSTEM_WHITELIST, why)
    }

    @Test
    fun stillExemptedWithNoEntryBlamesTheFramework() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RARE,
            after = StandbyBucket.EXEMPTED,
            source = ExemptSource.FRAMEWORK,
        )
        assertEquals(StandbyRootPolicy.REASON_FRAMEWORK, why)
    }

    @Test
    fun anUnreadableReadBackClaimsNothing() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RARE,
            after = null,
            source = ExemptSource.NONE,
        )
        assertEquals(StandbyRootPolicy.REASON_UNREADABLE, why)
        assertTrue(why.contains("unknown"))
    }

    @Test
    fun someThirdBucketIsReportedPlainly() {
        val why = StandbyRootPolicy.explainMismatch(
            target = StandbyBucket.RESTRICTED,
            after = StandbyBucket.FREQUENT,
            source = ExemptSource.NONE,
        )
        assertEquals(StandbyRootPolicy.REASON_OTHER_BUCKET, why)
    }
}
