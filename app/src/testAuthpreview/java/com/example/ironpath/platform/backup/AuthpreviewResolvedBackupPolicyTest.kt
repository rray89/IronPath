package com.example.ironpath.platform.backup

import com.example.ironpath.ui.screens.accountbackup.ANDROID_TRAINING_BACKUP_POLICY
import com.example.ironpath.ui.screens.accountbackup.accountExperienceEntryContent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Reads generated variant artifacts, checking both resource overlays and manifest merging. */
class AuthpreviewResolvedBackupPolicyTest {
    @Test
    fun `resolved manifest uses whole-database exclusions and onboarding-only transfer`() {
        val application =
            parse(
                    "app/build/intermediates/merged_manifest/authpreview/processAuthpreviewMainManifest/AndroidManifest.xml"
                )
                .documentElement
                .getElementsByTagName("application")
                .item(0) as Element
        val namespace = "http://schemas.android.com/apk/res/android"
        assertEquals("true", application.getAttributeNS(namespace, "allowBackup"))
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(namespace, "fullBackupContent")
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(namespace, "dataExtractionRules")
        )
        val resources =
            "app/build/intermediates/packaged_res/authpreview/packageAuthpreviewResources/xml/"
        val legacy = parse(resources + "backup_rules.xml").documentElement
        assertEquals(emptySet<Pair<String, String>>(), legacy.rules("include"))
        assertEquals(allExclusions, legacy.rules("exclude"))
        val extraction = parse(resources + "data_extraction_rules.xml").documentElement
        val cloud = extraction.getElementsByTagName("cloud-backup").item(0) as Element
        assertEquals(emptySet<Pair<String, String>>(), cloud.rules("include"))
        assertEquals(allExclusions, cloud.rules("exclude"))
        val transfer = extraction.getElementsByTagName("device-transfer").item(0) as Element
        assertEquals(setOf("sharedpref" to "ironpath_onboarding.xml"), transfer.rules("include"))
        assertEquals(emptySet<Pair<String, String>>(), transfer.rules("exclude"))
    }

    @Test
    fun `authpreview guidance matches system exclusion and explicit cloud transfer`() {
        assertTrue(
            ANDROID_TRAINING_BACKUP_POLICY.contains(
                "Android system backup and device transfer do not copy"
            )
        )
        assertTrue(ANDROID_TRAINING_BACKUP_POLICY.contains("manual cloud backup and restore"))
        assertTrue(
            accountExperienceEntryContent.privacyCopy.contains(ANDROID_TRAINING_BACKUP_POLICY)
        )
    }

    private fun Element.rules(tag: String): Set<Pair<String, String>> =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

    private fun parse(relative: String) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(projectFile(relative))

    private fun projectFile(relative: String): File {
        val root =
            generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
                    it.parentFile
                }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertTrue("Project root not found", root != null)
        val file = File(checkNotNull(root), relative)
        assertTrue("Required generated variant artifact missing: $relative", file.isFile)
        return file
    }

    private val allExclusions =
        setOf(
                "database",
                "sharedpref",
                "file",
                "root",
                "external",
                "device_database",
                "device_sharedpref",
                "device_file",
                "device_root"
            )
            .map { it to "." }
            .toSet()
}
