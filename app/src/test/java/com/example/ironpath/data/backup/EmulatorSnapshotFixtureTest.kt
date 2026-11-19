package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PersonalRecord
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/** The emulator protocol fixtures must be byte-for-byte snapshots produced by the app codec. */
class EmulatorSnapshotFixtureTest {
    @Test
    fun emulatorSnapshotsMatchKotlinCodecAndPreserveBothOneSidedChanges() {
        val fixture = Json.parseToJsonElement(projectFile(FIXTURE).readText()).jsonObject
        val examples =
            mapOf(
                "base" to bundle(5, 100.5, 60.5),
                "local" to bundle(6, 110.5, 60.5),
                "remote" to bundle(6, 100.5, 65.5),
                "merged" to bundle(7, 110.5, 65.5),
            )
        val merge =
            ManualSyncMerger.analyze(
                examples.getValue("base"),
                examples.getValue("local"),
                examples.getValue("remote"),
            )
        assertEquals(0, merge.conflicts.values.sum())
        assertEquals(
            examples.getValue("merged"),
            checkNotNull(merge.localResult).copy(localChangeRevision = 7),
        )
        examples.forEach { (name, bundle) ->
            val document = fixture.getValue(name).jsonObject
            val snapshot =
                EncodedBackupSnapshot(
                    document.getValue("formatVersion").jsonPrimitive.int,
                    document.getValue("localChangeRevision").jsonPrimitive.long,
                    document.getValue("chunks").jsonArray.map { value ->
                        val chunk = value.jsonObject
                        BackupChunk(
                            chunk.getValue("index").jsonPrimitive.int,
                            chunk.getValue("payload").jsonPrimitive.content,
                            chunk.getValue("encodedByteCount").jsonPrimitive.int,
                            chunk.getValue("digest").jsonPrimitive.content,
                        )
                    },
                    document.getValue("entityCounts").jsonObject.mapValues {
                        it.value.jsonPrimitive.int
                    },
                    document.getValue("encodedByteCount").jsonPrimitive.int,
                    document.getValue("contentDigest").jsonPrimitive.content,
                )
            assertEquals(
                "Emulator fixture $name drifted from the app codec",
                BackupSnapshotCodec().encode(bundle),
                snapshot
            )
            assertEquals(bundle, BackupSnapshotCodec().decode(snapshot))
        }
    }

    private fun bundle(revision: Long, squat: Double, bench: Double) =
        BackupBundle(
            revision,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                record("record-bench", "Bench Press", bench),
                record("record-squat", "Squat", squat)
            ),
        )

    private fun record(id: String, name: String, weight: Double) =
        PersonalRecord(
            id,
            name,
            name.lowercase(),
            weight,
            "2026-09-18",
            createdAt = 1_800_000_000_000
        )

    private fun projectFile(path: String): File =
        generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
                it.parentFile
            }
            .map { File(it, path) }
            .first { it.isFile }

    private companion object {
        const val FIXTURE = "firebase/test/fixtures/manual-backup-v1.json"
    }
}
