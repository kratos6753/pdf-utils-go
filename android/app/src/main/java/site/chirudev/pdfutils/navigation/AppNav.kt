package site.chirudev.pdfutils.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import site.chirudev.pdfutils.MainViewModel
import site.chirudev.pdfutils.account.AccountScreen
import site.chirudev.pdfutils.auth.ForgotPasswordScreen
import site.chirudev.pdfutils.auth.LoginScreen
import site.chirudev.pdfutils.auth.ResetPasswordScreen
import site.chirudev.pdfutils.auth.SignupScreen
import site.chirudev.pdfutils.auth.VerifyEmailScreen
import site.chirudev.pdfutils.history.HistoryScreen
import site.chirudev.pdfutils.home.HomeScreen
import site.chirudev.pdfutils.home.ToolCard
import site.chirudev.pdfutils.tools.Accepts
import site.chirudev.pdfutils.tools.ToolNav
import site.chirudev.pdfutils.tools.ToolScreen
import site.chirudev.pdfutils.tools.tools

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val topLevel = listOf(
    TopLevel(HomeRoute, "Tools", Icons.Outlined.Apps, Icons.Rounded.Apps),
    TopLevel(HistoryRoute, "History", Icons.Outlined.History, Icons.Rounded.History),
    TopLevel(AccountRoute, "Account", Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
)

@Composable
fun AppNav(main: MainViewModel, pendingRoute: MutableStateFlow<Any?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val snackbar = remember { SnackbarHostState() }
    val onTopLevel = topLevel.any { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }

    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let { nav.navigate(it); pendingRoute.value = null }
    }

    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
    NavigationSuiteScaffold(
        layoutType = if (onTopLevel) adaptive else NavigationSuiteType.None,
        navigationSuiteItems = {
            topLevel.forEach { t ->
                val selected = destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true
                item(
                    selected = selected,
                    onClick = { nav.switchTab(t.route) },
                    icon = { Icon(if (selected) t.selectedIcon else t.icon, null) },
                    label = { Text(t.label) },
                )
            }
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
        ) {
            composable<HomeRoute> {
                TopLevelScaffold(snackbar) { padding -> HomeScreen(onOpen = { nav.navigate(ToolRoute(it.slug)) }, contentPadding = padding) }
            }
            composable<HistoryRoute> {
                TopLevelScaffold(snackbar) { padding ->
                    HistoryScreen(padding, snackbar, onLogin = { nav.navigate(LoginRoute) }, onOpenWith = { tool, file -> nav.navigate(ToolRoute(tool.slug, fileIds = file.id)) })
                }
            }
            composable<AccountRoute> {
                TopLevelScaffold(snackbar) { padding ->
                    AccountScreen(padding, snackbar, onLogin = { nav.navigate(LoginRoute) }, onSignup = { nav.navigate(SignupRoute) })
                }
            }
            composable<ToolRoute> {
                ToolScreen(
                    ToolNav(
                        back = { nav.popBackStack() },
                        openTool = { slug, fileId -> nav.navigate(ToolRoute(slug, fileIds = fileId)) },
                        signUp = { nav.navigate(SignupRoute) },
                        login = { nav.navigate(LoginRoute) },
                        history = { nav.switchTab(HistoryRoute) },
                    ),
                )
            }
            composable<LoginRoute> {
                LoginScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { nav.popBackStack() },
                    onSignup = { nav.navigate(SignupRoute) { popUpTo<LoginRoute> { inclusive = true } } },
                    onForgot = { nav.navigate(ForgotRoute(it)) },
                )
            }
            composable<SignupRoute> {
                SignupScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { nav.popBackStack() },
                    onLogin = { nav.navigate(LoginRoute) { popUpTo<SignupRoute> { inclusive = true } } },
                )
            }
            composable<ForgotRoute> { ForgotPasswordScreen(it.toRoute<ForgotRoute>().email, onBack = { nav.popBackStack() }) }
            composable<ResetRoute> {
                ResetPasswordScreen(
                    it.toRoute<ResetRoute>().token,
                    onBack = { nav.popBackStack() },
                    onLogin = { nav.navigate(LoginRoute) { popUpTo<ResetRoute> { inclusive = true } } },
                )
            }
            composable<VerifyRoute> { VerifyEmailScreen(it.toRoute<VerifyRoute>().token, onContinue = { nav.popBackStack() }) }
        }
    }

    val incoming by main.incoming.pending.collectAsStateWithLifecycle()
    if (incoming.isNotEmpty()) {
        SharedFilesSheet(
            uris = incoming,
            onDismiss = main.incoming::dismiss,
            onPick = { slug ->
                main.incoming.handOff()
                nav.navigate(ToolRoute(slug, incoming = true))
            },
        )
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun TopLevelScaffold(snackbar: SnackbarHostState, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets.statusBars) { content(it) }
}

/** "Share → PDF Utils": choose what to do with the incoming files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedFilesSheet(uris: List<Uri>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mimes = remember(uris) { uris.map { context.contentResolver.getType(it) ?: "application/pdf" } }
    val choices = remember(mimes) {
        val kinds = mimes.mapNotNull(Accepts::of).toSet()
        tools.filter { t -> kinds.size == 1 && t.accepts in kinds && (t.multiple || uris.size == 1 || t.accepts == Accepts.Pdf) }
            .sortedByDescending { it.multiple && uris.size > 1 }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (uris.size == 1) "What do you want to do with this file?" else "What do you want to do with ${uris.size} files?",
                style = MaterialTheme.typography.titleLarge,
            )
            if (choices.isEmpty()) {
                Text("PDF Utils works with PDFs and JPG, PNG, WEBP or TIFF images.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            choices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { t -> ToolCard(t, onClick = { onPick(t.slug) }, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp).fillMaxWidth())
        }
    }
}
