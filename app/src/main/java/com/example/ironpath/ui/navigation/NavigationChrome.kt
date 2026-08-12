package com.example.ironpath.ui.navigation

enum class TopNavigationIcon {
    None,
    Menu,
    Back,
}

data class NavigationChrome(
    val showTopBar: Boolean,
    val showBottomBar: Boolean,
    val navigationIcon: TopNavigationIcon,
    val drawerEnabled: Boolean,
)

fun navigationChrome(route: String?): NavigationChrome =
    when (route) {
        Route.HOME,
        Route.PLAN,
        Route.ACTIVE,
        Route.HISTORY ->
            NavigationChrome(
                showTopBar = true,
                showBottomBar = true,
                navigationIcon = TopNavigationIcon.Menu,
                drawerEnabled = true,
            )
        Route.MANUAL,
        Route.AI_PRIVACY,
        Route.ABOUT,
        Route.WORKOUT_PREVIEW,
        Route.WORKOUT_LOG_DETAIL ->
            NavigationChrome(
                showTopBar = true,
                showBottomBar = false,
                navigationIcon = TopNavigationIcon.Back,
                drawerEnabled = false,
            )
        else ->
            NavigationChrome(
                showTopBar = false,
                showBottomBar = false,
                navigationIcon = TopNavigationIcon.None,
                drawerEnabled = false,
            )
    }

fun startupRoute(onboardingCompleted: Boolean): String =
    if (onboardingCompleted) Route.HOME else Route.ENTRY
