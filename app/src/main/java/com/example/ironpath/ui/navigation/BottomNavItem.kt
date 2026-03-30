package com.example.ironpath.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home(Route.HOME, "Home", Icons.Default.Home),
    Plan(Route.PLAN, "Plan", Icons.Default.List),
    Active(Route.ACTIVE, "Active", Icons.Default.PlayArrow),
    History(Route.HISTORY, "History", Icons.Default.FitnessCenter),
}
