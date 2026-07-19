package com.example.ironpath

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.BottomNavItem
import com.example.ironpath.ui.navigation.IronPathNavHost
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var timeProvider: TimeProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IronPathTheme { IronPathApp(timeProvider) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IronPathApp(
    timeProvider: TimeProvider,
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBars =
        currentRoute != Route.ENTRY && currentRoute != Route.DEV_TOOLS && currentRoute != null

    var devTapCount by remember { mutableIntStateOf(0) }
    var devLastTapAt by remember { mutableLongStateOf(0L) }

    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        topBar = {
            AnimatedVisibility(
                visible = showBars,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "IRONPATH",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    val now = timeProvider.epochMillis()
                                    if (now - devLastTapAt > 2000L) devTapCount = 0
                                    devTapCount++
                                    devLastTapAt = now
                                    if (devTapCount >= 5) {
                                        devTapCount = 0
                                        navController.navigate(Route.DEV_TOOLS)
                                    }
                                },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { /* non-functional in MVP */}) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showBars,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    BottomNavItem.entries.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            modifier = Modifier.testTag(TestTags.bottomNav(item.route)),
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Route.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(
                                    text = item.label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        IronPathNavHost(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
}
