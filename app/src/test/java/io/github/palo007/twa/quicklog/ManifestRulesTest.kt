// HAND-OWNED (quick-log). JVM checks for hand edits in the Bubblewrap-generated manifest (packet P5).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Paths are relative to the module dir (`app/`), which is Gradle's test working dir. */
class ManifestRulesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val manifestFile = File("src/main/AndroidManifest.xml")
    private val credPrefs = "questa_quicklog.xml" // DropboxAuth.prefs() file name

    private fun parse(f: File): Document {
        assertTrue("missing ${f.path}", f.isFile)
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        return dbf.newDocumentBuilder().parse(f)
    }

    private fun Element.a(name: String): String = getAttributeNS(androidNs, name)

    private fun elements(parent: Element, tag: String): List<Element> {
        val nl = parent.getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun application(): Element = elements(parse(manifestFile).documentElement, "application").single()

    /** A `HAND-OWNED (quick-log` comment in the 3 lines before the first line that contains [needle]. */
    private fun assertMarkerBefore(needle: String) {
        val lines = manifestFile.readLines()
        val i = lines.indexOfFirst { it.contains(needle) }
        assertTrue("line with '$needle' not found", i >= 0)
        val window = lines.subList(maxOf(0, i - 3), i + 1).joinToString("\n")
        assertTrue("no HAND-OWNED (quick-log marker before '$needle'", window.contains("HAND-OWNED (quick-log"))
    }

    @Test fun appLinkFilterIsLimitedToQuestaPath() {
        val filters = elements(application(), "intent-filter").filter { it.a("autoVerify") == "true" }
        assertEquals("exactly one autoVerify filter", 1, filters.size)
        val data = elements(filters[0], "data")
        assertTrue("https scheme kept", data.any { it.a("scheme") == "https" })
        assertTrue("host kept", data.any { it.a("host") == "@string/hostName" })
        assertTrue("pathPrefix=/Questa/", data.any { it.a("pathPrefix") == "/Questa/" })
        assertTrue("path=/Questa", data.any { it.a("path") == "/Questa" })
        assertFalse("no bare pathPrefix=/Questa (would match /Questa-other)", data.any { it.a("pathPrefix") == "/Questa" })
        assertMarkerBefore("android:autoVerify=\"true\"")
    }

    @Test fun applicationReferencesBackupRulesAndKeepsBackupOn() {
        val app = application()
        assertEquals("true", app.a("allowBackup"))
        assertEquals("@xml/backup_rules", app.a("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.a("dataExtractionRules"))
        assertMarkerBefore("<application")
    }

    /** Only exclude rules; the credential prefs excluded; the upload queue (file/inbox) not excluded. */
    private fun assertExcludesOnlyCredential(scope: Element, label: String) {
        assertTrue("$label: no <include> (would drop the queue from backup)", elements(scope, "include").isEmpty())
        val ex = elements(scope, "exclude")
        assertTrue("$label: excludes sharedpref/$credPrefs",
            ex.any { it.getAttribute("domain") == "sharedpref" && it.getAttribute("path") == credPrefs })
        assertFalse("$label: must not exclude the inbox queue",
            ex.any { it.getAttribute("domain") == "file" || it.getAttribute("path").startsWith("inbox") })
        assertEquals("$label: only one exclude", 1, ex.size)
    }

    @Test fun fullBackupContentExcludesCredentialOnly() {
        val root = parse(File("src/main/res/xml/backup_rules.xml")).documentElement
        assertEquals("full-backup-content", root.tagName)
        assertExcludesOnlyCredential(root, "backup_rules")
    }

    @Test fun dataExtractionRulesExcludeCredentialInCloudAndTransfer() {
        val root = parse(File("src/main/res/xml/data_extraction_rules.xml")).documentElement
        assertEquals("data-extraction-rules", root.tagName)
        for (tag in listOf("cloud-backup", "device-transfer")) {
            assertExcludesOnlyCredential(elements(root, tag).single(), tag)
        }
    }

    @Test fun batteryOptimizationPermissionDeclared() {
        val perm = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        val root = parse(manifestFile).documentElement
        assertTrue(elements(root, "uses-permission").any { it.a("name") == perm })
        assertMarkerBefore(perm)
    }
}
