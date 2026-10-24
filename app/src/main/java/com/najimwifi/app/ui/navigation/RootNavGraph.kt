package com.najimwifi.app.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.najimwifi.app.R
import com.najimwifi.app.data.di.AppContainer
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.data.settings.NajimSettings
import com.najimwifi.app.ui.welcome.WelcomeScreen
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.najimwifi.app.domain.service.DebtAlertOptions
import com.najimwifi.app.ui.alerts.LocalAlertCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.najimwifi.app.security.LoginSessionManager
import com.najimwifi.app.ui.home.CustomerDetailsScreen
import com.najimwifi.app.ui.home.HomeScreen
import com.najimwifi.app.ui.home.HomeViewModel
import com.najimwifi.app.ui.home.PackageLedgerScreen
import com.najimwifi.app.ui.management.ColumnEditorScreen
import com.najimwifi.app.ui.management.ColumnEditorViewModel
import com.najimwifi.app.ui.management.ManagementScreen
import com.najimwifi.app.ui.management.ManagementViewModel
import com.najimwifi.app.ui.security.LoginScreen
import com.najimwifi.app.ui.security.SecurityViewModel
import com.najimwifi.app.ui.settings.SettingsScreen
import com.najimwifi.app.domain.report.ReportScope
import com.najimwifi.app.ui.archive.ArchiveFilterState
import com.najimwifi.app.ui.operations.OperationLogScreen
import com.najimwifi.app.ui.report.ReportViewModel
import com.najimwifi.app.ui.report.ReportExportDialog

private data class ReportTarget(
    val scope: ReportScope,
    val customerId: String? = null,
    val archiveFilters: ArchiveFilterState? = null
)

private const val MANAGEMENT_COLUMNS_ROUTE = "management/internal/columns"
private const val MANAGEMENT_OPERATIONS_ROUTE = "management/internal/operations"
private const val HOME_CUSTOMER_ROUTE = "home/customer/{customerId}"
private const val HOME_PACKAGE_LEDGER_ROUTE = "home/customer/{customerId}/package/{packageId}"

private fun Context.hostActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current as? Activity
}

/** Three approved tabs; the operations log lives under management. */
@Composable
fun RootNavGraph(settings: NajimSettings, settingsStore: SettingsStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val activity = remember(context) { context.hostActivity() }
    // The simplified app has no debt/late-settlement notifications.
    // Disable previously scheduled alerts once without touching backup schedules.
    LaunchedEffect(applicationContext) {
        try {
            withContext(Dispatchers.IO) {
                if (settingsStore.settings.first().alerts != DebtAlertOptions()) {
                    settingsStore.updateAlerts(DebtAlertOptions())
                }
                LocalAlertCoordinator(applicationContext).reconcile(DebtAlertOptions())
            }
        } catch (_: Exception) {
            // Optional legacy alerts must never block access to sales records.
        }
    }
    val container = remember(applicationContext) { AppContainer(applicationContext) }
    val screenFactory = remember(container) {
        NajimScreenViewModelFactory(container, applicationContext)
    }
    val securityViewModel: SecurityViewModel = viewModel(factory = screenFactory)
    val security by securityViewModel.state.collectAsState()

    DisposableEffect(activity, securityViewModel) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> securityViewModel.onBackground()
                Lifecycle.Event.ON_START -> securityViewModel.onForeground()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    SideEffect {
        activity?.window?.let { window ->
            if (security.configured && security.policy.isEnabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    if (security.locked) {
        Box(
            modifier = modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceContainerLow
                ))
            )
        ) {
            LoginScreen(viewModel = securityViewModel)
        }
        return
    }

    val welcomeScope = rememberCoroutineScope()
    var finishingWelcome by remember { mutableStateOf(false) }
    if (!settings.onboardingFinished) {
        WelcomeScreen(
            busy = finishingWelcome,
            onFinish = {
                if (!finishingWelcome) {
                    finishingWelcome = true
                    welcomeScope.launch {
                        try { settingsStore.finishOnboarding() }
                        finally { finishingWelcome = false }
                    }
                }
            }
        )
        return
    }

    val navController = rememberNavController()
    var reportTarget by remember { mutableStateOf<ReportTarget?>(null) }
    val reportViewModel: ReportViewModel = viewModel(factory = screenFactory)
    val destinations = RootDestination.entries
    require(destinations.size == 3 && destinations.first() == RootDestination.HOME) {
        "Expected exactly three root destinations"
    }
    val managementDestination = RootDestination.MANAGEMENT
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedDestination = when (currentRoute) {
        MANAGEMENT_COLUMNS_ROUTE, MANAGEMENT_OPERATIONS_ROUTE -> managementDestination
        HOME_CUSTOMER_ROUTE, HOME_PACKAGE_LEDGER_ROUTE -> RootDestination.HOME
        else -> destinations.firstOrNull { it.route == currentRoute }
            ?: RootDestination.HOME
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 4.dp
                ) {
                    destinations.forEach { destination ->
                        val navIcon = when (destination) {
                            RootDestination.HOME -> R.drawable.najim_nav_home
                            RootDestination.MANAGEMENT -> R.drawable.najim_nav_management
                            RootDestination.SETTINGS -> R.drawable.najim_nav_settings
                        }
                        NavigationBarItem(
                            selected = selectedDestination == destination,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(navIcon),
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = RootDestination.HOME.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f)
                    )
                )
            )
        ) {
            destinations.forEach { destination ->
                composable(destination.route) {
                    when (destination) {
                        RootDestination.HOME -> {
                            val homeViewModel: HomeViewModel = viewModel(factory = screenFactory)
                            HomeScreen(
                                viewModel = homeViewModel,
                                onOpenCustomer = { customerId ->
                                    navController.navigate(
                                        "home/customer/" + Uri.encode(customerId)
                                    ) { launchSingleTop = true }
                                }
                            )
                        }
                        RootDestination.MANAGEMENT -> {
                            val managementViewModel: ManagementViewModel = viewModel(factory = screenFactory)
                            ManagementScreen(
                                viewModel = managementViewModel,
                                onOpenColumns = {
                                    navController.navigate(MANAGEMENT_COLUMNS_ROUTE) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenOperations = {
                                    navController.navigate(MANAGEMENT_OPERATIONS_ROUTE) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        RootDestination.SETTINGS -> {
                            SettingsScreen(securityViewModel = securityViewModel, settingsStore = settingsStore,
                                settings = settings, container = container)
                        }
                    }
                }
            }

            composable(
                route = HOME_CUSTOMER_ROUTE,
                arguments = listOf(navArgument("customerId") { type = NavType.StringType })
            ) { entry ->
                val customerId = requireNotNull(entry.arguments?.getString("customerId"))
                val homeOwner = remember(entry) {
                    navController.getBackStackEntry(RootDestination.HOME.route)
                }
                val homeViewModel: HomeViewModel = viewModel(
                    viewModelStoreOwner = homeOwner,
                    factory = screenFactory
                )
                CustomerDetailsScreen(
                    viewModel = homeViewModel,
                    customerId = customerId,
                    onBack = { navController.popBackStack() },
                    onOpenReport = { reportTarget = ReportTarget(ReportScope.CUSTOMER, customerId) },
                    onOpenPackage = { packageId ->
                        navController.navigate(
                            "home/customer/" + Uri.encode(customerId) +
                                "/package/" + Uri.encode(packageId)
                        ) { launchSingleTop = true }
                    }
                )
            }

            composable(
                route = HOME_PACKAGE_LEDGER_ROUTE,
                arguments = listOf(
                    navArgument("customerId") { type = NavType.StringType },
                    navArgument("packageId") { type = NavType.StringType }
                )
            ) { entry ->
                val customerId = requireNotNull(entry.arguments?.getString("customerId"))
                val packageId = requireNotNull(entry.arguments?.getString("packageId"))
                val homeOwner = remember(entry) {
                    navController.getBackStackEntry(RootDestination.HOME.route)
                }
                val homeViewModel: HomeViewModel = viewModel(
                    viewModelStoreOwner = homeOwner,
                    factory = screenFactory
                )
                PackageLedgerScreen(
                    viewModel = homeViewModel,
                    customerId = customerId,
                    packageId = packageId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(MANAGEMENT_COLUMNS_ROUTE) {
                val columnViewModel: ColumnEditorViewModel = viewModel(factory = screenFactory)
                ColumnEditorScreen(
                    viewModel = columnViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(MANAGEMENT_OPERATIONS_ROUTE) {
                OperationLogScreen(
                    database = container.database,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
    reportTarget?.let { target ->
        ReportExportDialog(
            container = container,
            viewModel = reportViewModel,
            scope = target.scope,
            initialCustomerId = target.customerId,
            archiveFilters = target.archiveFilters,
            onClose = { reportTarget = null }
        )
    }
}

/** App data services stay unchanged; login owns a separate Keystore-backed store. */
private class NajimScreenViewModelFactory(
    private val container: AppContainer,
    context: Context
) : ViewModelProvider.Factory {
    private val applicationContext = context.applicationContext
    private val loginManager by lazy { LoginSessionManager(applicationContext) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass == SecurityViewModel::class.java -> SecurityViewModel(loginManager) as T
        modelClass == HomeViewModel::class.java ->
            container.newHomeViewModel { loginManager.authorizeCorrection(it) } as T
        modelClass == ManagementViewModel::class.java -> container.newManagementViewModel() as T
        modelClass == ColumnEditorViewModel::class.java ->
            ColumnEditorViewModel(container.database) as T
        modelClass == ReportViewModel::class.java -> container.newReportViewModel() as T
        else -> throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
    }
}
