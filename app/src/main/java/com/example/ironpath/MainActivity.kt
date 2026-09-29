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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.BottomNavItem
import com.example.ironpath.ui.navigation.IronPathDrawer
import com.example.ironpath.ui.navigation.IronPathNavHost
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.navigation.TopNavigationIcon
import com.example.ironpath.ui.navigation.navigationChrome
import com.example.ironpath.ui.navigation.startupRoute
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_EXPERIENCE_PREVIEW_ENABLED
import com.example.ironpath.ui.screens.accountbackup.AccountBackupViewModel
import com.example.ironpath.ui.screens.accountbackup.ManualBackupActions
import com.example.ironpath.ui.screens.accountbackup.ManualBackupUiState
import com.example.ironpath.ui.screens.accountbackup.accountExperiencePreviewTopBarTitle
import com.example.ironpath.ui.screens.accountbackup.isAccountBackupRoute
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var onboardingRepository: OnboardingRepository

    @Inject lateinit var installationGuard: InstallationGuard

    @Inject lateinit var accountDeletionManager: AccountDeletionManager

    @Inject lateinit var accountGateway: AccountGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IronPathTheme {
                var startup by remember { mutableStateOf<StartupState>(StartupState.Loading) }
                val scope = rememberCoroutineScope()
                suspend fun prepareApp(): StartupState {
                    val deletion =
                        try {
                            accountDeletionManager.recoverAtStartup()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AccountDeletionResult.Unavailable
                        }
                    return when (deletion) {
                        AccountDeletionResult.Idle,
                        AccountDeletionResult.Completed -> {
                            try {
                                accountGateway.reconcileAfterDeletionRecovery()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Keep the existing recoverable account state available to the UI.
                            }
                            when (val accountState = accountGateway.state.value) {
                                is AccountState.AccountDeletionPending ->
                                    return StartupState.DeletionPending(accountState.progress)
                                AccountState.DeletingAccount -> {
                                    val pending =
                                        try {
                                            accountDeletionManager.pending()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                    return StartupState.DeletionPending(pending)
                                }
                                else -> Unit
                            }
                            runCatching { installationGuard.validate() }
                            StartupState.Ready(
                                runCatching { onboardingRepository.isCompleted() }
                                    .getOrDefault(false)
                            )
                        }
                        is AccountDeletionResult.RetryRequired ->
                            StartupState.DeletionPending(deletion.progress)
                        AccountDeletionResult.Unavailable -> {
                            val pending =
                                try {
                                    accountDeletionManager.pending()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            StartupState.DeletionPending(pending)
                        }
                    }
                }
                LaunchedEffect(accountDeletionManager) { startup = prepareApp() }
                when (val current = startup) {
                    StartupState.Loading ->
                        AccountDeletionStartupScreen(
                            title = "Opening IronPath",
                            detail = "Checking account and local data status.",
                        )
                    is StartupState.DeletionPending ->
                        AccountDeletionStartupScreen(
                            title = "Finishing account deletion",
                            detail =
                                "IronPath will open after the saved deletion steps finish. " +
                                    "Training data stays unavailable while cleanup is pending.",
                            onRetry = { scope.launch { startup = prepareApp() } },
                        )
                    is StartupState.Ready -> {
                        val accountViewModel =
                            if (ACCOUNT_EXPERIENCE_PREVIEW_ENABLED)
                                hiltViewModel<AccountBackupViewModel>()
                            else null
                        val accountState =
                            accountViewModel?.state?.collectAsStateWithLifecycle()?.value
                                ?: AccountState.LocalOnly
                        val manualState =
                            accountViewModel?.manual?.collectAsStateWithLifecycle()?.value
                                ?: ManualBackupUiState()
                        key(manualState.profileResetEpoch) {
                            IronPathApp(
                                timeProvider = timeProvider,
                                onboardingCompleted =
                                    current.onboardingCompleted ||
                                        manualState.profileResetEpoch > 0,
                                onCompleteOnboarding = onboardingRepository::complete,
                                accountState = accountState,
                                manualBackupState = manualState,
                                manualBackupActions =
                                    ManualBackupActions(
                                        previewBackup = { accountViewModel?.previewBackup() },
                                        previewSync = { accountViewModel?.previewSync() },
                                        previewRestore = { accountViewModel?.previewRestore() },
                                        previewUndo = { accountViewModel?.previewUndo() },
                                        selectResolution = {
                                            accountViewModel?.selectResolution(it)
                                        },
                                        confirmDestructive = {
                                            accountViewModel?.confirmDestructive(it)
                                        },
                                        confirmActiveWorkoutDiscard = {
                                            accountViewModel?.confirmActiveWorkoutDiscard(it)
                                        },
                                        holdGuidance = { accountViewModel?.holdGuidance() },
                                        confirm = { accountViewModel?.confirm() },
                                        keepDeviceEmpty = { accountViewModel?.keepDeviceEmpty() },
                                        recoverUnreadableSession = {
                                            accountViewModel?.recoverUnreadableSession()
                                        },
                                        openSignOutReview = {
                                            accountViewModel?.openSignOutReview()
                                        },
                                        dismissSignOutReview = {
                                            accountViewModel?.dismissSignOutReview()
                                        },
                                        chooseSignOutChoice = {
                                            accountViewModel?.chooseSignOutChoice(it)
                                        },
                                        requestRemoveConfirmation = {
                                            accountViewModel?.requestRemoveConfirmation()
                                        },
                                        dismissRemoveConfirmation = {
                                            accountViewModel?.dismissRemoveConfirmation()
                                        },
                                        confirmSignOut = { accountViewModel?.confirmSignOut(it) },
                                        retrySignOut = { accountViewModel?.retrySignOut() },
                                        openDeleteReview = { accountViewModel?.openDeleteReview() },
                                        dismissDeleteReview = {
                                            accountViewModel?.dismissDeleteReview()
                                        },
                                        continueAccountDeletion = {
                                            accountViewModel?.continueAccountDeletion()
                                        },
                                        confirmAccountDeletion = {
                                            accountViewModel?.confirmAccountDeletion()
                                        },
                                        retryAccountDeletion = {
                                            accountViewModel?.retryAccountDeletion()
                                        },
                                        acknowledgeDeletionNavigation = {
                                            accountViewModel?.acknowledgeDeletionNavigation()
                                        },
                                    ),
                                onAccountSignIn = { accountViewModel?.signIn() },
                                onAccountRetry = { accountViewModel?.refresh() },
                                onAccountLeave = { onLeave ->
                                    accountViewModel?.leave(onLeave) ?: onLeave()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private sealed interface StartupState {
        data object Loading : StartupState

        data class Ready(val onboardingCompleted: Boolean) : StartupState

        data class DeletionPending(val progress: AccountDeletionProgress?) : StartupState
    }

    @Composable
    private fun AccountDeletionStartupScreen(
        title: String,
        detail: String,
        onRetry: (() -> Unit)? = null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
            onRetry?.let { retry ->
                Button(onClick = retry, modifier = Modifier.fillMaxWidth()) {
                    Text("RETRY DELETION")
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
    accountState: AccountState = AccountState.LocalOnly,
    manualBackupState: ManualBackupUiState = ManualBackupUiState(),
    manualBackupActions: ManualBackupActions = ManualBackupActions(),
    onAccountSignIn: () -> Unit = {},
    onAccountRetry: () -> Unit = {},
    onAccountLeave: (() -> Unit) -> Unit = { it() },
) {
    LaunchedEffect(manualBackupState.accountDeletion.completed) {
        if (manualBackupState.accountDeletion.completed) {
            navController.navigate(Route.HOME) {
                popUpTo(Route.HOME) { inclusive = true }
                launchSingleTop = true
            }
            manualBackupActions.acknowledgeDeletionNavigation()
        }
    }
    val onAccountBack: () -> Unit = {
        onAccountLeave {
            if (isAccountBackupRoute(navController.currentDestination?.route))
                navController.popBackStack()
        }
    }
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
                accountState = accountState,
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
                                        IconButton(
                                            onClick = {
                                                if (isAccountBackupRoute(currentRoute))
                                                    onAccountBack()
                                                else navController.popBackStack()
                                            }
                                        ) {
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
                if (
                    accountState == AccountState.DeletingAccount ||
                        accountState is AccountState.AccountDeletionPending
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        Text(
                            if (accountState == AccountState.DeletingAccount)
                                "Deleting account and all data"
                            else "Account deletion needs retry",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            if (accountState == AccountState.DeletingAccount)
                                "IronPath is removing the demo backups and local training data. Keep the app open while this finishes."
                            else {
                                val progress =
                                    (accountState as AccountState.AccountDeletionPending).progress
                                "Deletion stopped at ${progress.stage.name.lowercase().replace('_', ' ')}. Retry to finish the same operation."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier.semantics {
                                    stateDescription =
                                        if (accountState == AccountState.DeletingAccount)
                                            "Account deletion in progress"
                                        else "Account deletion needs retry"
                                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                },
                        )
                        if (accountState is AccountState.AccountDeletionPending) {
                            Button(
                                onClick = manualBackupActions.retryAccountDeletion,
                                enabled = !manualBackupState.accountDeletion.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("RETRY DELETION")
                            }
                        }
                    }
                } else {
                    IronPathNavHost(
                        navController = navController,
                        innerPadding = innerPadding,
                        startDestination = startupRoute(onboardingCompleted),
                        onCompleteOnboarding = onCompleteOnboarding,
                        accountState = accountState,
                        onAccountSignIn = onAccountSignIn,
                        onAccountRetry = onAccountRetry,
                        manualBackupState = manualBackupState,
                        manualBackupActions = manualBackupActions,
                        onAccountBack = onAccountBack,
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
