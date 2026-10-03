package com.polymath.os.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.polymath.os.BuildConfig
import com.polymath.os.ui.components.LoadingState
import com.polymath.os.ui.components.OfflineBanner
import com.polymath.os.ui.screens.BackupScreen
import com.polymath.os.ui.screens.ChatScreen
import com.polymath.os.ui.screens.CreateSkillScreen
import com.polymath.os.ui.screens.DiagnosticsScreen
import com.polymath.os.ui.screens.GoalDetailScreen
import com.polymath.os.ui.screens.GoalsScreen
import com.polymath.os.ui.screens.InboxScreen
import com.polymath.os.ui.screens.JournalScreen
import com.polymath.os.ui.screens.ManualGoalScreen
import com.polymath.os.ui.screens.MoreScreen
import com.polymath.os.ui.screens.OnboardingScreen
import com.polymath.os.ui.screens.ProposalScreen
import com.polymath.os.ui.screens.ProposalsListScreen
import com.polymath.os.ui.screens.ScheduleScreen
import com.polymath.os.ui.screens.SettingsScreen
import com.polymath.os.ui.screens.SkillDetailScreen
import com.polymath.os.ui.screens.SkillEditorScreen
import com.polymath.os.ui.screens.SkillsScreen
import com.polymath.os.ui.screens.TodayScreen
import com.polymath.os.vm.AppViewModel

object Routes {
    const val TODAY = "today"
    const val SKILLS = "skills"
    const val INBOX = "inbox"
    const val JOURNAL = "journal"
    const val MORE = "more"
    const val SKILL = "skill/{id}"
    const val CREATE = "create?name={name}&curiosity={curiosity}&goalSkill={goalSkill}"
    const val EDITOR = "editor?id={id}&proposal={proposal}&name={name}"
    const val PROPOSAL = "proposal/{id}"
    const val PROPOSALS = "proposals"
    const val GOALS = "goals"
    const val GOAL = "goal/{id}"
    const val GOAL_MANUAL = "goal_manual"
    const val SCHEDULE = "schedule"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"
    const val DIAGNOSTICS = "diagnostics"

    fun skill(id: String) = "skill/${Uri.encode(id)}"
    fun create(name: String = "", curiosity: String = "", goalSkill: String = "") =
        "create?name=${Uri.encode(name)}&curiosity=${Uri.encode(curiosity)}&goalSkill=${Uri.encode(goalSkill)}"
    fun editor(id: String = "", proposal: String = "", name: String = "") =
        "editor?id=${Uri.encode(id)}&proposal=${Uri.encode(proposal)}&name=${Uri.encode(name)}"
    fun proposal(id: String) = "proposal/${Uri.encode(id)}"
    fun goal(id: String) = "goal/${Uri.encode(id)}"
}

/** Navigation callbacks handed to screens, so composables never touch the NavController directly. */
class Nav(private val nav: NavHostController) {
    fun to(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() { nav.popBackStack() }
    fun replace(route: String) = nav.navigate(route) { nav.currentDestination?.route?.let { popUpTo(it) { inclusive = true } }; launchSingleTop = true }
    fun tab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Outlined.WbSunny),
    Tab(Routes.SKILLS, "Skills", Icons.Outlined.School),
    Tab(Routes.INBOX, "Inbox", Icons.Outlined.Inbox),
    Tab(Routes.JOURNAL, "Journal", Icons.Outlined.AutoStories),
    Tab(Routes.MORE, "More", Icons.Outlined.MoreHoriz),
)

@Composable
fun PolymathRoot(launchDestination: String?) {
    val app: AppViewModel = hiltViewModel()
    val onboarded by app.onboarded.collectAsStateWithLifecycle()
    when (onboarded) {
        null -> LoadingState()
        false -> OnboardingScreen(app)
        true -> MainScaffold(app, launchDestination)
    }
}

@Composable
private fun MainScaffold(app: AppViewModel, launchDestination: String?) {
    val navController = rememberNavController()
    val nav = Nav(navController)
    val online by app.online.collectAsStateWithLifecycle()
    val ai by app.ai.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    var handledLaunch by rememberSaveable { mutableStateOf(false) }

    // Keyed effect: a notification tap opens the matching tab once. No data work here.
    LaunchedEffect(launchDestination) {
        if (!handledLaunch && launchDestination != null) {
            handledLaunch = true
            when (launchDestination) {
                "WEEKLY_REVIEW" -> nav.tab(Routes.INBOX)
                else -> Unit
            }
        }
    }

    val showTabs = tabs.any { it.route == current }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.tab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController, startDestination = Routes.TODAY, modifier = Modifier.weight(1f)) {
                composable(Routes.TODAY) { TodayScreen(nav, ai) }
                composable(Routes.SKILLS) { SkillsScreen(nav) }
                composable(Routes.INBOX) { InboxScreen(nav, ai) }
                composable(Routes.JOURNAL) { JournalScreen() }
                composable(Routes.MORE) { MoreScreen(nav, debug = BuildConfig.DEBUG) }
                composable(Routes.SKILL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { SkillDetailScreen(nav, ai) }
                composable(
                    Routes.CREATE,
                    arguments = listOf(
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("curiosity") { type = NavType.StringType; defaultValue = "" },
                        navArgument("goalSkill") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { CreateSkillScreen(nav, ai) }
                composable(
                    Routes.EDITOR,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        navArgument("proposal") { type = NavType.StringType; defaultValue = "" },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { SkillEditorScreen(nav) }
                composable(Routes.PROPOSAL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { ProposalScreen(nav, ai) }
                composable(Routes.PROPOSALS) { ProposalsListScreen(nav, app) }
                composable(Routes.GOALS) { GoalsScreen(nav, ai) }
                composable(Routes.GOAL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { GoalDetailScreen(nav) }
                composable(Routes.GOAL_MANUAL) { ManualGoalScreen(nav) }
                composable(Routes.SCHEDULE) { ScheduleScreen(nav) }
                composable(Routes.CHAT) { ChatScreen(nav, ai) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.BACKUP) { BackupScreen(nav) }
                if (BuildConfig.DEBUG) composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(nav) }
            }
            OfflineBanner(!online, if (showTabs) Modifier else Modifier.navigationBarsPadding())
        }
    }
}
