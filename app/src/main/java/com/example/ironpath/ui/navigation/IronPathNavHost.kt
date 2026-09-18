package com.example.ironpath.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.ironpath.ui.screens.about.AboutScreen
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_EXPERIENCE_PREVIEW_ENABLED
import com.example.ironpath.ui.screens.accountbackup.accountExperiencePreviewDestination
import com.example.ironpath.ui.screens.accountbackup.openAccountExperiencePreview
import com.example.ironpath.ui.screens.active.ActiveScreen
import com.example.ironpath.ui.screens.aiprivacy.AiPrivacyScreen
import com.example.ironpath.ui.screens.devtools.DevToolsScreen
import com.example.ironpath.ui.screens.entry.EntryScreen
import com.example.ironpath.ui.screens.history.HistoryScreen
import com.example.ironpath.ui.screens.history.WorkoutLogDetailScreen
import com.example.ironpath.ui.screens.home.HomeScreen
import com.example.ironpath.ui.screens.manual.ManualScreen
import com.example.ironpath.ui.screens.plan.PlanScreen
import com.example.ironpath.ui.screens.workoutpreview.WorkoutPreviewScreen
import kotlinx.coroutines.launch

@Composable
fun IronPathNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    startDestination: String = Route.ENTRY,
    onCompleteOnboarding: suspend () -> Boolean = { true },
    accountExperiencePreviewEnabled: Boolean = ACCOUNT_EXPERIENCE_PREVIEW_ENABLED,
    drawerOpen: Boolean = false,
    onCloseDrawer: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    var onboardingCompletionInProgress by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Route.ENTRY) {
            EntryScreen(
                onGetStarted = {
                    if (!onboardingCompletionInProgress) {
                        onboardingCompletionInProgress = true
                        coroutineScope.launch {
                            if (onCompleteOnboarding()) {
                                navController.navigate(Route.HOME) {
                                    popUpTo(Route.ENTRY) { inclusive = true }
                                }
                            } else {
                                onboardingCompletionInProgress = false
                            }
                        }
                    }
                },
                continuing = onboardingCompletionInProgress,
                accountExperiencePreviewEnabled = accountExperiencePreviewEnabled,
                onSignIn = { navController.openAccountExperiencePreview() },
            )
        }
        composable(Route.HOME) {
            DrawerAwareDestination(drawerOpen, onCloseDrawer) {
                HomeScreen(
                    onNavigateToPlan = {
                        navController.navigate(Route.PLAN) {
                            popUpTo(Route.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToActive = {
                        navController.navigate(Route.ACTIVE) {
                            popUpTo(Route.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenWorkoutPreview = { workoutId ->
                        navController.navigate(Route.workoutPreview(workoutId))
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        composable(Route.PLAN) {
            DrawerAwareDestination(drawerOpen, onCloseDrawer) {
                PlanScreen(
                    onPlanAccepted = {
                        navController.navigate(Route.HOME) {
                            popUpTo(Route.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onStartWorkout = {
                        navController.navigate(Route.ACTIVE) {
                            popUpTo(Route.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenWorkoutPreview = { workoutId ->
                        navController.navigate(Route.workoutPreview(workoutId))
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        composable(Route.ACTIVE) {
            DrawerAwareDestination(drawerOpen, onCloseDrawer) {
                ActiveScreen(
                    onNavigateToPlan = {
                        navController.navigate(Route.PLAN) {
                            popUpTo(Route.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onWorkoutComplete = {
                        navController.navigate(Route.HOME) {
                            popUpTo(Route.HOME) {
                                inclusive = true
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        composable(Route.HISTORY) {
            DrawerAwareDestination(drawerOpen, onCloseDrawer) {
                HistoryScreen(
                    onOpenLog = { logId -> navController.navigate(Route.workoutLogDetail(logId)) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        composable(Route.MANUAL) { ManualScreen(modifier = Modifier.padding(innerPadding)) }
        accountExperiencePreviewDestination(innerPadding)
        composable(Route.AI_PRIVACY) { AiPrivacyScreen(modifier = Modifier.padding(innerPadding)) }
        composable(Route.ABOUT) { AboutScreen(modifier = Modifier.padding(innerPadding)) }
        composable(
            route = Route.WORKOUT_PREVIEW,
            arguments = listOf(navArgument(Route.WORKOUT_ID_ARG) { type = NavType.StringType }),
        ) {
            WorkoutPreviewScreen(
                onBack = { navController.popBackStack() },
                onStarted = {
                    navController.navigate(Route.ACTIVE) {
                        popUpTo(Route.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
        composable(
            route = Route.WORKOUT_LOG_DETAIL,
            arguments = listOf(navArgument(Route.WORKOUT_LOG_ID_ARG) { type = NavType.StringType }),
        ) {
            WorkoutLogDetailScreen(
                onBack = { navController.popBackStack() },
                modifier = Modifier.padding(innerPadding),
            )
        }
        composable(Route.DEV_TOOLS) {
            DevToolsScreen(
                onBack = { navController.popBackStack() },
                onClearComplete = {
                    navController.navigate(Route.ENTRY) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}

@Composable
private fun DrawerAwareDestination(
    drawerOpen: Boolean,
    onCloseDrawer: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
    BackHandler(enabled = drawerOpen, onBack = onCloseDrawer)
}
