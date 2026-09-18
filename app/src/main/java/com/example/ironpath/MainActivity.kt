package com.example.ironpath

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.BottomNavItem
import com.example.ironpath.ui.navigation.IronPathDrawer
import com.example.ironpath.ui.navigation.IronPathNavHost
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.navigation.TopNavigationIcon
import com.example.ironpath.ui.navigation.navigationChrome
import com.example.ironpath.ui.navigation.startupRoute
import com.example.ironpath.ui.screens.accountbackup.accountExperiencePreviewTopBarTitle
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var onboardingRepository: OnboardingRepository

    @Inject lateinit var installationGuard: InstallationGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IronPathTheme {
                val onboardingCompleted by
                    produceState<Boolean?>(
                        initialValue = null,
                        key1 = onboardingRepository,
                        key2 = installationGuard,
                    ) {
                        runCatching { installationGuard.validate() }
                        value =
                            runCatching { onboardingRepository.isCompleted() }.getOrDefault(false)
                    }
                onboardingCompleted?.let { completed ->
                    IronPathApp(
                        timeProvider = timeProvider,
                        onboardingCompleted = completed,
                        onCompleteOnboarding = onboardingRepository::complete,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IronPathApp(
    timeProvider: TimeProvider,
    navController: NavHostController = rememberNavController(),
    onboardingCompleted: Boolean = false,
    onCompleteOnboarding: suspend () -> Boolean = { true },
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val chrome = navigationChrome(currentRoute)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val menuFocusRequester = remember { FocusRequester() }
    var drawerBackInterceptEnabled by remember { mutableStateOf(false) }
    var restoreMenuFocusOnClose by remember { mutableStateOf(false) }
    val drawerConcealsAppContent =
        drawerBackInterceptEnabled || drawerState.currentValue != DrawerValue.Closed

    var devTapCount by remember { mutableIntStateOf(0) }
    var devLastTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(drawerState.currentValue, chrome.navigationIcon) {
        drawerBackInterceptEnabled = drawerState.currentValue == DrawerValue.Open
        if (drawerState.currentValue == DrawerValue.Open) {
            restoreMenuFocusOnClose = true
        } else if (restoreMenuFocusOnClose) {
            if (chrome.navigationIcon == TopNavigationIcon.Menu) {
                withFrameNanos {}
                menuFocusRequester.requestFocus()
            }
            restoreMenuFocusOnClose = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome.drawerEnabled,
        drawerContent = {
            IronPathDrawer(
                selectedRoute = currentRoute,
                onDestinationSelected = { route ->
                    drawerBackInterceptEnabled = false
                    coroutineScope.launch {
                        drawerState.close()
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier =
                    Modifier.fillMaxSize().testTag(TestTags.APP_CONTENT).semantics {
                        testTagsAsResourceId = true
                        if (drawerConcealsAppContent) hideFromAccessibility()
                    },
                topBar = {
                    AnimatedVisibility(
                        visible = chrome.showTopBar,
                        enter = slideInVertically { -it },
                        exit = slideOutVertically { -it },
                    ) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = topBarTitle(currentRoute),
                                    style = MaterialTheme.typography.titleLarge,
                                    color =
                                        if (chrome.navigationIcon == TopNavigationIcon.Menu) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    modifier =
                                        if (chrome.navigationIcon == TopNavigationIcon.Menu) {
                                            Modifier.clickable(
                                                interactionSource =
                                                    remember { MutableInteractionSource() },
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
                                            }
                                        } else {
                                            Modifier
                                        },
                                )
                            },
                            navigationIcon = {
                                when (chrome.navigationIcon) {
                                    TopNavigationIcon.Menu -> {
                                        IconButton(
                                            modifier =
                                                Modifier.focusRequester(menuFocusRequester)
                                                    .focusable(),
                                            onClick = {
                                                drawerBackInterceptEnabled = true
                                                coroutineScope.launch { drawerState.open() }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "Menu",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    TopNavigationIcon.Back -> {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    TopNavigationIcon.None -> Unit
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
                        visible = chrome.showBottomBar,
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
                                            indicatorColor =
                                                MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    startDestination = startupRoute(onboardingCompleted),
                    onCompleteOnboarding = onCompleteOnboarding,
                    drawerOpen = drawerBackInterceptEnabled,
                    onCloseDrawer = {
                        drawerBackInterceptEnabled = false
                        coroutineScope.launch { drawerState.close() }
                    },
                )
            }
        }
    }
}

private fun topBarTitle(route: String?): String =
    accountExperiencePreviewTopBarTitle(route)
        ?: when (route) {
            Route.MANUAL -> "MANUAL"
            Route.AI_PRIVACY -> "AI & PRIVACY"
            Route.ABOUT -> "ABOUT IRONPATH"
            Route.WORKOUT_PREVIEW -> "WORKOUT PREVIEW"
            Route.WORKOUT_LOG_DETAIL -> "WORKOUT LOG"
            else -> "IRONPATH"
        }
