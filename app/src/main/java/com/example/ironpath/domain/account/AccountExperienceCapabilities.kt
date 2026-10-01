package com.example.ironpath.domain.account

/** Build-selected account actions. Ownership data alone never grants a disabled capability. */
data class AccountExperienceCapabilities(
    val mode: Mode,
    val canSignIn: Boolean,
    val canUseBackup: Boolean,
    val canDeleteAccount: Boolean,
    val canAssociateLocalData: Boolean,
    val canUseDemoPreview: Boolean,
    val inspectRemoteSessionState: Boolean,
) {
    enum class Mode {
        Demo,
        AuthPreview,
        Unavailable,
    }

    companion object {
        val Demo =
            AccountExperienceCapabilities(
                mode = Mode.Demo,
                canSignIn = true,
                canUseBackup = true,
                canDeleteAccount = true,
                canAssociateLocalData = true,
                canUseDemoPreview = true,
                inspectRemoteSessionState = true,
            )

        val AuthPreview =
            AccountExperienceCapabilities(
                mode = Mode.AuthPreview,
                canSignIn = true,
                canUseBackup = false,
                canDeleteAccount = false,
                canAssociateLocalData = false,
                canUseDemoPreview = false,
                inspectRemoteSessionState = false,
            )

        val Unavailable =
            AccountExperienceCapabilities(
                mode = Mode.Unavailable,
                canSignIn = false,
                canUseBackup = false,
                canDeleteAccount = false,
                canAssociateLocalData = false,
                canUseDemoPreview = false,
                inspectRemoteSessionState = false,
            )
    }
}
