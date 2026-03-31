package com.example.ironpath.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ironpath.ui.screens.active.ActiveScreen
import com.example.ironpath.ui.screens.entry.EntryScreen
import com.example.ironpath.ui.screens.history.HistoryScreen
import com.example.ironpath.ui.screens.home.HomeScreen
import com.example.ironpath.ui.screens.plan.PlanScreen

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
                    navController.navigate(Route.HOME) {
                        popUpTo(Route.ENTRY) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.HOME) {
            HomeScreen(
                onNavigateToPlan = {
                    navController.navigate(Route.PLAN) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToActive = {
                    navController.navigate(Route.ACTIVE) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
        composable(Route.PLAN) {
            PlanScreen(
                onPlanAccepted = {
                    navController.navigate(Route.HOME) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
        composable(Route.ACTIVE) {
            ActiveScreen(modifier = Modifier.padding(innerPadding))
        }
        composable(Route.HISTORY) {
            HistoryScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
