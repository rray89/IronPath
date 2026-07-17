package com.example.ironpath.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.ironpath.ui.screens.active.ActiveScreen
import com.example.ironpath.ui.screens.devtools.DevToolsScreen
import com.example.ironpath.ui.screens.entry.EntryScreen
import com.example.ironpath.ui.screens.history.HistoryScreen
import com.example.ironpath.ui.screens.history.WorkoutLogDetailScreen
import com.example.ironpath.ui.screens.home.HomeScreen
import com.example.ironpath.ui.screens.plan.PlanScreen
import com.example.ironpath.ui.screens.workoutpreview.WorkoutPreviewScreen

@Composable
fun IronPathNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.ENTRY,
        modifier = modifier,
    ) {
        composable(Route.ENTRY) {
            EntryScreen(
                onGetStarted = {
                    navController.navigate(Route.HOME) { popUpTo(Route.ENTRY) { inclusive = true } }
                },
            )
        }
        composable(Route.HOME) {
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
        composable(Route.PLAN) {
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
        composable(Route.ACTIVE) {
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
        composable(Route.HISTORY) {
            HistoryScreen(
                onOpenLog = { logId -> navController.navigate(Route.workoutLogDetail(logId)) },
                modifier = Modifier.padding(innerPadding),
            )
        }
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
