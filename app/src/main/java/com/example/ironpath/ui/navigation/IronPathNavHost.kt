package com.example.ironpath.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.ironpath.ui.screens.active.ActiveScreen
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
        startDestination = Route.HOME,
        modifier = modifier.padding(innerPadding),
    ) {
        composable(Route.HOME) { HomeScreen() }
        composable(Route.PLAN) { PlanScreen() }
        composable(Route.ACTIVE) { ActiveScreen() }
        composable(Route.HISTORY) { HistoryScreen() }
    }
}
