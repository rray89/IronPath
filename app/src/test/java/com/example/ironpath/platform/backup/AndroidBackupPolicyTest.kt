package com.example.ironpath.platform.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidBackupPolicyTest {

    @Test
    fun `legacy policy excludes every eligible app data domain`() {
        val root = parse("app/src/main/res/xml/backup_rules.xml").documentElement

        assertEquals("full-backup-content", root.tagName)
        assertEquals(EMPTY_SET, root.rules("include"))
        assertEquals(ALL_DOMAIN_EXCLUSIONS, root.rules("exclude"))
    }

    @Test
    fun `Android 12 cloud policy excludes every eligible app data domain`() {
        val root = parse("app/src/main/res/xml/data_extraction_rules.xml").documentElement
        val cloudBackup = root.singleChild("cloud-backup")

        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(EMPTY_SET, cloudBackup.rules("include"))
        assertEquals(ALL_DOMAIN_EXCLUSIONS, cloudBackup.rules("exclude"))
    }

    @Test
    fun `Android 12 device transfer allowlists Room and non-secret onboarding state`() {
        val root = parse("app/src/main/res/xml/data_extraction_rules.xml").documentElement
        val deviceTransfer = root.singleChild("device-transfer")

        assertEquals(
            setOf(
                BackupRule("database", "."),
                BackupRule("sharedpref", "ironpath_onboarding.xml"),
            ),
            deviceTransfer.rules("include"),
        )
        assertEquals(EMPTY_SET, deviceTransfer.rules("exclude"))
    }

    @Test
    fun `manifest keeps backup enabled with both versioned policies`() {
        val application =
            parse("app/src/main/AndroidManifest.xml").documentElement.singleChild("application")

        assertEquals("true", application.androidAttribute("allowBackup"))
        assertEquals("@xml/backup_rules", application.androidAttribute("fullBackupContent"))
        assertEquals(
            "@xml/data_extraction_rules",
            application.androidAttribute("dataExtractionRules")
        )
    }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(projectFile(path))

    private fun projectFile(path: String): File {
        val root =
            generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
                    it.parentFile
                }
                .firstOrNull { File(it, path).isFile }
        assertTrue("Could not locate project file $path", root != null)
        return File(checkNotNull(root), path)
    }

    private fun Element.singleChild(tagName: String): Element {
        val matches =
            (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter {
                it.tagName == tagName
            }
        assertEquals("Expected one <$tagName> under <${this.tagName}>", 1, matches.size)
        return matches.single()
    }

    private fun Element.rules(tagName: String): Set<BackupRule> =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filterIsInstance<Element>()
            .filter { it.tagName == tagName }
            .map { BackupRule(domain = it.getAttribute("domain"), path = it.getAttribute("path")) }
            .toSet()

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private data class BackupRule(val domain: String, val path: String)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val EMPTY_SET = emptySet<BackupRule>()
        val ALL_DOMAIN_EXCLUSIONS =
            setOf(
                BackupRule("database", "."),
                BackupRule("sharedpref", "."),
                BackupRule("file", "."),
                BackupRule("root", "."),
                BackupRule("external", "."),
                BackupRule("device_database", "."),
                BackupRule("device_sharedpref", "."),
                BackupRule("device_file", "."),
                BackupRule("device_root", "."),
            )
    }
}
