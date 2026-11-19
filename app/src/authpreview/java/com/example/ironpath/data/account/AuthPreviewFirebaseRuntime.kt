package com.example.ironpath.data.account

import android.content.Context
import com.example.ironpath.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Firebase is initialized only from the explicit, generated authpreview resources. */
@Singleton
class AuthPreviewFirebaseRuntime
@Inject
constructor(
    @ApplicationContext context: Context,
) {
    private val runtime = resolve(context)

    val auth: FirebaseAuth? = runtime.first
    val googleWebClientId: String = runtime.second

    val configured: Boolean
        get() = auth != null && googleWebClientId.isNotBlank()

    private fun resolve(context: Context): Pair<FirebaseAuth?, String> {
        val values =
            try {
                val resources = context.resources
                if (!resources.getBoolean(R.bool.auth_preview_configured)) return null to ""
                val applicationId =
                    resources.getString(R.string.auth_preview_firebase_application_id).trim()
                val apiKey = resources.getString(R.string.auth_preview_firebase_api_key).trim()
                val projectId =
                    resources.getString(R.string.auth_preview_firebase_project_id).trim()
                val webClientId =
                    resources.getString(R.string.auth_preview_google_web_client_id).trim()
                if (
                    context.packageName != EXPECTED_PACKAGE ||
                        applicationId.isBlank() ||
                        apiKey.isBlank() ||
                        projectId.isBlank() ||
                        webClientId.isBlank()
                ) {
                    return null to ""
                }
                listOf(applicationId, apiKey, projectId, webClientId)
            } catch (_: Exception) {
                return null to ""
            }

        val (applicationId, apiKey, projectId, webClientId) = values
        val expectedOptions =
            FirebaseOptions.Builder()
                .setApplicationId(applicationId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .build()
        val app =
            try {
                val existing =
                    FirebaseApp.getApps(context).firstOrNull { it.name == FIREBASE_APP_NAME }
                when {
                    existing == null ->
                        FirebaseApp.initializeApp(context, expectedOptions, FIREBASE_APP_NAME)
                    existing.options.applicationId == applicationId &&
                        existing.options.apiKey == apiKey &&
                        existing.options.projectId == projectId -> existing
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        val auth =
            try {
                app?.let(FirebaseAuth::getInstance)
            } catch (_: Exception) {
                null
            }
        return auth to (if (auth == null) "" else webClientId)
    }

    private companion object {
        const val EXPECTED_PACKAGE = "com.example.ironpath.authpreview"
        const val FIREBASE_APP_NAME = "ironpath-authpreview"
    }
}
