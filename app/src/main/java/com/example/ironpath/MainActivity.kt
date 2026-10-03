package com.example.ironpath

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.AccountCredentialActivityHost
import com.example.ironpath.domain.account.AccountDeletionManager
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionResult
import com.example.ironpath.domain.account.AccountExperienceCapabilities
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
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_DELETION_CANCELLATION_SCOPE
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_EXPERIENCE_PREVIEW_ENABLED
import com.example.ironpath.ui.screens.accountbackup.AccountBackupViewModel
import com.example.ironpath.ui.screens.accountbackup.AccountDeletionRecoveryScreen
import com.example.ironpath.ui.screens.accountbackup.ManualBackupActions
import com.example.ironpath.ui.screens.accountbackup.ManualBackupUiState
import com.example.ironpath.ui.screens.accountbackup.accountDeletionRecoveryMessage
import com.example.ironpath.ui.screens.accountbackup.accountExperiencePreviewTopBarTitle
import com.example.ironpath.ui.screens.accountbackup.blocksAccountActions
import com.example.ironpath.ui.screens.accountbackup.canCancelBeforeActivation
import com.example.ironpath.ui.screens.accountbackup.isAccountBackupRoute
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var onboardingRepository: OnboardingRepository

    @Inject lateinit var installationGuard: InstallationGuard

    @Inject lateinit var accountDeletionManager: AccountDeletionManager

    @Inject lateinit var accountGateway: AccountGateway

    @Inject lateinit var accountExperienceCapabilities: AccountExperienceCapabilities

    @Inject lateinit var accountContextReader: AccountContextReader

    @Inject lateinit var accountCredentialActivityHost: AccountCredentialActivityHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountCredentialActivityHost.attach(this)
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(accountGateway) {
                accountGateway.sessionChanges.collect { accountGateway.reconcileSessionChange() }
            }
            IronPathTheme {
                var startup by remember { mutableStateOf<StartupState>(StartupState.Loading) }
                var startupAttempt by remember { mutableIntStateOf(0) }
                var startupRecoveryAction by remember {
                    mutableStateOf(StartupRecoveryAction.Observe)
                }
                var observedStartupDeletion by remember { mutableStateOf(false) }
                var knownStartupDeletionProgress by remember {
                    mutableStateOf<AccountDeletionProgress?>(null)
                }
                fun deletionPending(progress: AccountDeletionProgress?): StartupState {
                    observedStartupDeletion = true
                    if (progress != null) knownStartupDeletionProgress = progress
                    return StartupState.DeletionPending(knownStartupDeletionProgress)
                }
                suspend fun readReadyProfile(expectedGeneration: Long? = null): StartupState {
                    try {
                        val observed = accountContextReader.read()
                        val onboardingCompleted = onboardingRepository.isCompleted()
                        val confirmed = accountContextReader.read()
                        if (
                            observed.profileGeneration != confirmed.profileGeneration ||
                                (expectedGeneration != null &&
                                    observed.profileGeneration != expectedGeneration)
                        ) {
                            return StartupState.ProfileVerificationUnavailable
                        }
                        return StartupState.Ready(
                            onboardingCompleted = onboardingCompleted,
                            profileGeneration = confirmed.profileGeneration,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return StartupState.ProfileVerificationUnavailable
                    }
                }
                suspend fun prepareApp(): StartupState {
                    val deletion =
                        try {
                            // Recreation always observes. Only explicit actions may activate or
                            // cancel.
                            when (startupRecoveryAction) {
                                StartupRecoveryAction.Observe ->
                                    accountDeletionManager.recoverAtStartup()
                                StartupRecoveryAction.Retry -> accountDeletionManager.retry()
                                StartupRecoveryAction.CancelUnactivated ->
                                    accountDeletionManager.cancelUnactivated()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AccountDeletionResult.Unavailable
                        }
                    return when (deletion) {
                        AccountDeletionResult.Idle,
                        AccountDeletionResult.Cancelled,
                        AccountDeletionResult.Completed -> {
                            val terminalDeletion = deletion != AccountDeletionResult.Idle
                            if (
                                !terminalDeletion &&
                                    (observedStartupDeletion ||
                                        startupRecoveryAction != StartupRecoveryAction.Observe)
                            ) {
                                // Idle cannot retire an observed deletion or prove an explicit
                                // retry/cancellation succeeded, even when the journal read is null.
                                return deletionPending(knownStartupDeletionProgress)
                            }
                            val reconciled =
                                try {
                                    if (terminalDeletion)
                                        accountGateway.reconcileAfterDeletionRecovery()
                                    else accountGateway.refreshLocal()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    AccountActionResult.Unavailable
                                }
                            when (val accountState = accountGateway.state.value) {
                                is AccountState.AccountDeletionPending ->
                                    return deletionPending(accountState.progress)
                                AccountState.DeletingAccount -> {
                                    val pending =
                                        try {
                                            accountDeletionManager.pending()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                    return deletionPending(pending)
                                }
                                else -> Unit
                            }
                            val stable =
                                accountGateway.state.value.let {
                                    it == AccountState.LocalOnly ||
                                        it is AccountState.SignedIn ||
                                        it is AccountState.AwaitingDataChoice
                                }
                            // Ordinary startup must expose interrupted sign-out and unreadable
                            // session recovery. The stricter stable-state gate applies only after
                            // an explicit, authoritative terminal deletion outcome.
                            if (
                                terminalDeletion &&
                                    (reconciled != AccountActionResult.Completed || !stable)
                            )
                                return StartupState.ProfileVerificationUnavailable
                            if (terminalDeletion) {
                                // Successful stable reconciliation already acknowledged this
                                // deletion. Later independent startup checks must retry normally.
                                observedStartupDeletion = false
                                knownStartupDeletionProgress = null
                                startupRecoveryAction = StartupRecoveryAction.Observe
                            }
                            val installation =
                                runCatching { installationGuard.validate() }.getOrNull()
                            if (
                                installation == null ||
                                    installation == InstallationValidationResult.Failed
                            )
                                return StartupState.ProfileVerificationUnavailable
                            readReadyProfile()
                        }
                        is AccountDeletionResult.RetryRequired -> deletionPending(deletion.progress)
                        is AccountDeletionResult.Failed,
                        AccountDeletionResult.Unavailable -> {
                            val pending =
                                try {
                                    accountDeletionManager.pending()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            deletionPending(pending)
                        }
                    }
                }
                LaunchedEffect(accountDeletionManager, accountContextReader, startupAttempt) {
                    startup = prepareApp()
                    try {
                        accountContextReader.changes.collect {
                            val ready = startup as? StartupState.Ready ?: return@collect
                            val observedGeneration =
                                try {
                                    accountContextReader.read().profileGeneration
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    startup = StartupState.ProfileVerificationUnavailable
                                    return@collect
                                }
                            if (observedGeneration == ready.profileGeneration) return@collect
                            startup = StartupState.VerifyingProfile
                            withFrameNanos {}
                            val settledAccountState =
                                accountGateway.state.first {
                                    it != AccountState.DeletingAccount &&
                                        it != AccountState.SigningOut
                                }
                            if (settledAccountState is AccountState.AccountDeletionPending) {
                                startup = deletionPending(settledAccountState.progress)
                                return@collect
                            }
                            startup = readReadyProfile(expectedGeneration = observedGeneration)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        startup = StartupState.ProfileVerificationUnavailable
                    }
                }
                when (val current = startup) {
                    StartupState.Loading ->
                        AccountDeletionStartupScreen(
                            title = "Opening IronPath",
                            detail = "Checking account and local data status.",
                        )
                    StartupState.VerifyingProfile ->
                        AccountDeletionStartupScreen(
                            title =
                                if (
                                    startupRecoveryAction == StartupRecoveryAction.CancelUnactivated
                                )
                                    "Checking cancellation"
                                else "Checking local profile",
                            detail =
                                if (
                                    startupRecoveryAction == StartupRecoveryAction.CancelUnactivated
                                )
                                    "Checking whether the service can cancel this reservation. Training data remains locked until the result is verified."
                                else
                                    "Verifying the current training profile and onboarding status.",
                        )
                    StartupState.ProfileVerificationUnavailable ->
                        AccountDeletionStartupScreen(
                            title = "Local profile unavailable",
                            detail =
                                "IronPath couldn't verify the local profile and onboarding state. " +
                                    "Training data stays closed until verification succeeds.",
                            retryLabel = "RETRY",
                            onRetry = {
                                startupRecoveryAction = StartupRecoveryAction.Observe
                                startup = StartupState.VerifyingProfile
                                startupAttempt++
                            },
                        )
                    is StartupState.DeletionPending ->
                        AccountDeletionStartupScreen(
                            title = "Finishing account deletion",
                            detail =
                                accountDeletionRecoveryMessage(
                                    current.progress,
                                    accountExperienceCapabilities.mode ==
                                        AccountExperienceCapabilities.Mode.Demo,
                                ),
                            progress = current.progress,
                            onRetry = {
                                startupRecoveryAction = StartupRecoveryAction.Retry
                                startup = StartupState.VerifyingProfile
                                startupAttempt++
                            },
                            onCancelUnactivated =
                                if (
                                    accountExperienceCapabilities.mode ==
                                        AccountExperienceCapabilities.Mode.AuthPreview &&
                                        current.progress?.canCancelBeforeActivation() == true
                                ) {
                                    {
                                        startupRecoveryAction =
                                            StartupRecoveryAction.CancelUnactivated
                                        startup = StartupState.VerifyingProfile
                                        startupAttempt++
                                    }
                                } else null,
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
                        key(current.profileGeneration) {
                            IronPathApp(
                                timeProvider = timeProvider,
                                verifiedProfileGeneration = current.profileGeneration,
                                onboardingCompleted = current.onboardingCompleted,
                                onCompleteOnboarding = onboardingRepository::complete,
                                accountState = accountState,
                                accountExperienceMode = accountExperienceCapabilities.mode,
                                accountSignInAvailable = accountExperienceCapabilities.canSignIn,
                                manualBackupState = manualState,
                                manualBackupActions =
                                    ManualBackupActions(
                                        cancelReview = { accountViewModel?.leave {} },
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
                                        cancelAccountDeletion = {
                                            accountViewModel?.cancelAccountDeletion()
                                        },
                                        acknowledgeDeletionNavigation = {
                                            accountViewModel?.acknowledgeDeletionNavigation(it)
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

    override fun onDestroy() {
        accountCredentialActivityHost.detach(this)
        super.onDestroy()
    }

    private enum class StartupRecoveryAction {
        Observe,
        Retry,
        CancelUnactivated
    }

    private sealed interface StartupState {
        data object Loading : StartupState

        data object VerifyingProfile : StartupState

        data object ProfileVerificationUnavailable : StartupState

        data class Ready(
            val onboardingCompleted: Boolean,
            val profileGeneration: Long,
        ) : StartupState

        data class DeletionPending(val progress: AccountDeletionProgress?) : StartupState
    }
}

/** The cold-start barrier remains visible until deletion and local-profile checks finish. */
@Composable
internal fun AccountDeletionStartupScreen(
    title: String,
    detail: String,
    retryLabel: String = "RETRY DELETION",
    onRetry: (() -> Unit)? = null,
    progress: AccountDeletionProgress? = null,
    onCancelUnactivated: (() -> Unit)? = null,
) {
    BackHandler { /* Startup recovery cannot reveal unverified training data. */}
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.semantics {
                        stateDescription = title
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            onRetry?.let { retry ->
                Button(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text(retryLabel) }
            }
            if (progress?.canCancelBeforeActivation() == true && onCancelUnactivated != null) {
                Text(
                    ACCOUNT_DELETION_CANCELLATION_SCOPE,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onCancelUnactivated, modifier = Modifier.fillMaxWidth()) {
                    Text("CANCEL IF NOT STARTED")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IronPathApp(
    timeProvider: TimeProvider,
    verifiedProfileGeneration: Long = 0L,
    navController: NavHostController = rememberNavController(),
    onboardingCompleted: Boolean = false,
    onCompleteOnboarding: suspend () -> Boolean = { true },
    accountState: AccountState = AccountState.LocalOnly,
    accountExperienceMode: AccountExperienceCapabilities.Mode =
        AccountExperienceCapabilities.Mode.Demo,
    accountSignInAvailable: Boolean = true,
    manualBackupState: ManualBackupUiState = ManualBackupUiState(),
    manualBackupActions: ManualBackupActions = ManualBackupActions(),
    onAccountSignIn: () -> Unit = {},
    onAccountRetry: () -> Unit = {},
    onAccountLeave: (() -> Unit) -> Unit = { it() },
) {
    if (manualBackupState.accountDeletion.blocksAccountActions(accountState)) {
        BackHandler { /* Leaving cannot bypass recovery; cancellation needs its explicit server check. */}
        Surface(modifier = Modifier.fillMaxSize()) {
            AccountDeletionRecoveryScreen(
                state = accountState,
                manual = manualBackupState,
                onRetry = manualBackupActions.retryAccountDeletion,
                modifier = Modifier.safeDrawingPadding(),
                demoStorage = accountExperienceMode == AccountExperienceCapabilities.Mode.Demo,
                onCancelUnactivated =
                    manualBackupActions.cancelAccountDeletion.takeIf {
                        accountExperienceMode == AccountExperienceCapabilities.Mode.AuthPreview
                    },
            )
        }
        return
    }
    val onAccountBack: () -> Unit = {
        onAccountLeave {
            if (isAccountBackupRoute(navController.currentDestination?.route))
                navController.popBackStack()
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val deletionCompletionTargetGeneration =
        manualBackupState.accountDeletion.completionTargetGeneration
    val deletionCompletionDestination = startupRoute(onboardingCompleted)
    LaunchedEffect(
        deletionCompletionTargetGeneration,
        verifiedProfileGeneration,
        deletionCompletionDestination,
        currentRoute,
    ) {
        val targetGeneration = deletionCompletionTargetGeneration ?: return@LaunchedEffect
        if (targetGeneration != verifiedProfileGeneration || currentRoute == null) {
            return@LaunchedEffect
        }
        if (currentRoute != deletionCompletionDestination) {
            navController.navigate(deletionCompletionDestination) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else {
            manualBackupActions.acknowledgeDeletionNavigation(targetGeneration)
        }
    }
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
                IronPathNavHost(
                    navController = navController,
                    innerPadding = innerPadding,
                    startDestination = startupRoute(onboardingCompleted),
                    onCompleteOnboarding = onCompleteOnboarding,
                    accountState = accountState,
                    accountSignInAvailable = accountSignInAvailable,
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
