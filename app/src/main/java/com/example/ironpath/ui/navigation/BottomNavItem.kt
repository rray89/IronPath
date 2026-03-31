package com.example.ironpath.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home(Route.HOME, "Home", Icons.Default.Home),
    Plan(Route.PLAN, "Plan", Icons.Default.CalendarMonth),
    Active(Route.ACTIVE, "Active", Icons.Default.FlashOn),
    History(Route.HISTORY, "History", Icons.Default.Schedule),
}
