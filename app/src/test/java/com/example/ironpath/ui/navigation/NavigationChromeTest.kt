package com.example.ironpath.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationChromeTest {
    @Test
    fun `primary destinations show menu top bar and bottom navigation`() {
        listOf(Route.HOME, Route.PLAN, Route.ACTIVE, Route.HISTORY).forEach { route ->
            assertEquals(
                NavigationChrome(
                    showTopBar = true,
                    showBottomBar = true,
                    navigationIcon = TopNavigationIcon.Menu,
                    drawerEnabled = true,
                ),
                navigationChrome(route),
            )
        }
    }

    @Test
    fun `secondary and detail destinations show shared back without bottom navigation`() {
        listOf(
                "manual",
                "ai_privacy",
                "about",
                Route.WORKOUT_PREVIEW,
                Route.WORKOUT_LOG_DETAIL,
            )
            .forEach { route ->
                assertEquals(
                    NavigationChrome(
                        showTopBar = true,
                        showBottomBar = false,
                        navigationIcon = TopNavigationIcon.Back,
                        drawerEnabled = false,
                    ),
                    navigationChrome(route),
                )
            }
    }

    @Test
    fun `entry dev tools and unresolved routes own no shared application chrome`() {
        listOf(Route.ENTRY, Route.DEV_TOOLS, null).forEach { route ->
            assertEquals(
                NavigationChrome(
                    showTopBar = false,
                    showBottomBar = false,
                    navigationIcon = TopNavigationIcon.None,
                    drawerEnabled = false,
                ),
                navigationChrome(route),
            )
        }
    }
}

class StartupRouteTest {
    @Test
    fun `first launch starts at entry while completed onboarding starts at home`() {
        assertEquals(Route.ENTRY, startupRoute(onboardingCompleted = false))
        assertEquals(Route.HOME, startupRoute(onboardingCompleted = true))
    }
}
