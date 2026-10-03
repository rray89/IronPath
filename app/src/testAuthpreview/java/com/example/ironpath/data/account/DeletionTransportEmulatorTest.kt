package com.example.ironpath.data.account

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Only the explicit deletionTransportTest task may run this isolated synthetic flow. */
class DeletionTransportEmulatorTest {
    @Test
    fun actualClientDeletesOrphanBackupAndAuthThenRecoversReceiptWithoutIdentity() = runBlocking {
        val project = "demo-ironpath-deletion"
        val authHost = System.getenv("FIREBASE_AUTH_EMULATOR_HOST").orEmpty()
        val firestoreHost = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
        val endpoint = System.getenv("IRONPATH_DELETION_ENDPOINT").orEmpty()
        require(authHost == "127.0.0.1:9197" && firestoreHost == "127.0.0.1:8187")
        require(
            validDeletionEndpoint(endpoint, allowLoopbackForTests = true) &&
                endpoint.startsWith("http://127.0.0.1:")
        )
        val subject = "kotlin-deletion-${UUID.randomUUID()}"
        fun googleSignIn(): JsonObject {
            val google =
                buildJsonObject {
                        put("sub", subject)
                        put("email", "$subject@example.test")
                        put("email_verified", true)
                    }
                    .toString()
            val result =
                request(
                    "http://$authHost/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=demo-key",
                    "POST",
                    buildJsonObject {
                        put(
                            "postBody",
                            "providerId=google.com&id_token=" + URLEncoder.encode(google, "UTF-8")
                        )
                        put("requestUri", "http://localhost")
                        put("returnSecureToken", true)
                    }
                )
            assertEquals(200, result.first)
            return result.second!!
        }
        val initial = googleSignIn()
        val uid = initial.getValue("localId").jsonPrimitive.content
        val token = initial.getValue("idToken").jsonPrimitive.content
        val root =
            "http://$firestoreHost/v1/projects/$project/databases/(default)/documents/users/$uid"
        val orphan = "$root/backups/missing-manifest/chunks/unregistered"
        val fields = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put(
                        "unexpected",
                        buildJsonObject { put("stringValue", "synthetic-orphan-payload") }
                    )
                }
            )
        }
        val commitUrl =
            "http://$firestoreHost/v1/projects/$project/databases/(default)/documents:commit"
        val commit = buildJsonObject {
            put(
                "writes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "update",
                                buildJsonObject {
                                    put(
                                        "name",
                                        "projects/$project/databases/(default)/documents/users/$uid/backups/missing-manifest/chunks/unregistered"
                                    )
                                    put("fields", fields.getValue("fields"))
                                }
                            )
                        }
                    )
                }
            )
        }
        assertEquals(200, request(commitUrl, "POST", commit, "owner").first)
        val client = DeletionServiceRestClient(endpoint, project, allowLoopbackForTests = true)
        assertTrue(client.available())
        val operation = UUID.randomUUID().toString()
        assertEquals(DeletionServiceResult.Missing, client.resume(operation))
        assertEquals(200, request(orphan, "GET", token = "owner").first)
        val first = client.start(operation, token)
        assertTrue(
            first == DeletionServiceResult.Pending || first == DeletionServiceResult.Complete
        )
        // Wait on the durable receipt, never turn a failure into a retry success.
        withTimeout(20_000) {
            while (true) {
                when (client.resume(operation)) {
                    DeletionServiceResult.Complete -> break
                    DeletionServiceResult.Pending -> yield()
                    else -> error("Deletion receipt became unavailable")
                }
            }
        }
        assertEquals(404, request(orphan, "GET", token = "owner").first)
        assertEquals(404, request(root, "GET", token = "owner").first)
        // The permanent fence rejects the old token even before its ordinary expiry.
        assertEquals(403, request(commitUrl, "POST", commit, token).first)
        val lookup =
            request(
                "http://$authHost/identitytoolkit.googleapis.com/v1/accounts:lookup?key=demo-key",
                "POST",
                buildJsonObject { put("idToken", token) }
            )
        assertEquals(400, lookup.first)
        val recreated = DeletionServiceRestClient(endpoint, project, allowLoopbackForTests = true)
        assertEquals(DeletionServiceResult.Complete, recreated.resume(operation))
        val fresh = googleSignIn()
        assertNotEquals(uid, fresh.getValue("localId").jsonPrimitive.content)
        assertEquals(404, request(orphan, "GET", token = "owner").first)
    }

    private fun request(
        url: String,
        method: String,
        body: JsonObject? = null,
        token: String? = null
    ): Pair<Int, JsonObject?> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = it.toString().toByteArray()
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            return code to text?.let { Json.parseToJsonElement(it) as? JsonObject }
        } finally {
            connection.disconnect()
        }
    }
}
