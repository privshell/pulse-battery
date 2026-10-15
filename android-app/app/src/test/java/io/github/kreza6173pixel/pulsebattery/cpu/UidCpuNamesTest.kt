package io.github.kreza6173pixel.pulsebattery.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UidCpuNamesTest {

    /** The uids the phone actually reported with no package attached. */
    private val stats = """
        0: 9860551023 39137791290
        1036: 2377781183 7490228715
        1041: 3946976297 2705389040
        14959: 8036640319 2666268009
    """.trimIndent()

    private val packages = "package:co.mangotechnologies.clickup uid:14959"

    @Test
    fun parsesTheNameLookupOutput() {
        val out = "0:root\n1036:logd\n1041:audioserver\n"
        val names = UidCpuParsers.parseUidNames(out)
        assertEquals("root", names[0])
        assertEquals("logd", names[1036])
        assertEquals("audioserver", names[1041])
    }

    @Test
    fun aUidTheDeviceCannotNameIsSkipped() {
        // `id -nu` writes nothing on failure, leaving an empty field.
        val names = UidCpuParsers.parseUidNames("0:root\n99999:\n")
        assertEquals(1, names.size)
        assertNull(names[99999])
    }

    @Test
    fun onlyUidsWithoutAPackageAreLookedUp() {
        val report = buildUidCpuReport(stats, packages)
        val unnamed = report.unnamedUids()
        assertTrue(unnamed.containsAll(listOf(0, 1036, 1041)))
        assertFalse(unnamed.contains(14959))
    }

    @Test
    fun namesAreAttachedToTheirRows() {
        val report = buildUidCpuReport(stats, packages)
            .withSystemNames(mapOf(0 to "root", 1036 to "logd", 1041 to "audioserver"))
        assertEquals("root", report.rows.first { it.uid == 0 }.systemName)
        assertEquals("logd", report.rows.first { it.uid == 1036 }.systemName)
        assertNull(report.rows.first { it.uid == 14959 }.systemName)
    }

    @Test
    fun platformUidsAreToldApartFromAppUids() {
        val report = buildUidCpuReport(stats, packages)
        assertTrue(report.rows.first { it.uid == 0 }.isPlatformUid)
        assertTrue(report.rows.first { it.uid == 1041 }.isPlatformUid)
        assertFalse(report.rows.first { it.uid == 14959 }.isPlatformUid)
    }

    @Test
    fun attachingNamesChangesNothingElse() {
        val before = buildUidCpuReport(stats, packages)
        val after = before.withSystemNames(mapOf(0 to "root"))
        assertEquals(before.rows.size, after.rows.size)
        assertEquals(before.totalMicros, after.totalMicros)
        assertEquals(before.rows.map { it.uid }, after.rows.map { it.uid })
    }
}
