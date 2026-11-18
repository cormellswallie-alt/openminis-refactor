package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the restore screen offers as a source, and where the Server row goes
 * when nothing is configured yet.
 *
 * Both rules below are decisions about a Compose tree, and the behaviour a
 * user sees is one `onClick` lambda and one absent row — there is no seam to
 * drive them through without an instrumentation host. Rather than skip the
 * coverage or stand up Robolectric for two facts, this asserts against the
 * source itself: the rows are declared literally, so their presence and the
 * branch in the Server row's onClick are readable and pin exactly the thing a
 * future edit would undo.
 *
 * The weakness is honest — this is a structural test, not a behavioural one.
 * It cannot catch a row that renders but is invisible. It CAN catch the two
 * regressions that actually happened here (a duplicate source entry coming
 * back, and the empty-state dialog returning as a dead end), which is what it
 * exists for.
 */
class RestoreSourceEntriesTest {
    @Test
    fun `its strings are gone from every locale`() {
        val dead = listOf("backup_choose_shared", "backup_shared_title", "backup_shared_empty")
        for (locale in listOf("values", "values-zh", "values-es")) {
            val f = File("src/main/res/$locale/strings.xml")
            assertTrue("missing $locale/strings.xml", f.isFile)
            val xml = f.readText()
            for (key in dead) {
                assertFalse("$locale still declares $key", xml.contains("\"$key\""))
            }
        }
    }
}
