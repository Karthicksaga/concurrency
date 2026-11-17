package com.localai.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.localai.app.data.settings.ThemeMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LocalAiTheme
import com.localai.app.data.settings.AppSettings
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.feature.chat.ChatListScreen
import com.localai.app.feature.chat.ChatScreen
import com.localai.app.feature.home.HomeScreen
import com.localai.app.feature.models.ModelDetailScreen
import com.localai.app.feature.models.ModelsScreen
import com.localai.app.feature.monitor.MonitorScreen
import com.localai.app.feature.onboarding.OnboardingScreen
import com.localai.app.feature.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("chats", "Chat", Icons.AutoMirrored.Outlined.Chat),
    Tab("agents", "Agents", Icons.Outlined.SmartToy),
    Tab("knowledge", "Knowledge", Icons.AutoMirrored.Outlined.MenuBook),
    Tab("models", "Models", Icons.Outlined.Memory),
)

fun androidx.navigation.NavController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppRoot(openChat: kotlinx.coroutines.flow.MutableStateFlow<String?>, vm: RootViewModel = hiltViewModel()) {
    val pendingChat by openChat.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s: AppSettings = settings ?: return   // DataStore not read yet: keep the splash background
    val dark = when (s.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    LocalAiTheme(s.theme) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val showBar = route in TABS.map { it.route }
        LaunchedEffect(pendingChat, s.onboarded) { val id = pendingChat; if (id != null && s.onboarded) { nav.navigate("chat/$id"); openChat.value = null } }
        Scaffold(
            containerColor = La.colors.bg,
            bottomBar = {
                if (showBar) NavigationBar(containerColor = La.colors.surface1) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route, onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = La.colors.accentSoft, selectedIconColor = La.colors.accentStrong,
                                selectedTextColor = La.colors.text, unselectedIconColor = La.colors.text3, unselectedTextColor = La.colors.text3),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(bottom = if (showBar) pad.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))) {
                NavHost(nav, startDestination = if (s.onboarded) "home" else "onboarding") {
                    composable("onboarding") { OnboardingScreen(onDone = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } }) }
                    composable("home") { HomeScreen(nav) }
                    composable("chats") { ChatListScreen(nav) }
                    composable("chat/{id}?attach={attach}", arguments = listOf(navArgument("attach") { defaultValue = "0" })) { ChatScreen(nav) }
                    composable("voice/{id}") { com.localai.app.feature.voice.VoiceScreen(nav) }
                    composable("studio") { com.localai.app.feature.studio.StudioScreen(nav) }
                    composable("models") { ModelsScreen(nav) }
                    composable("model/{id}") { ModelDetailScreen(nav) }
                    composable("settings") { SettingsScreen(nav) }
                    composable("monitor") { MonitorScreen(nav) }
                    composable("agents") { com.localai.app.feature.agents.AgentsScreen(nav) }
                    composable("agent/{id}") { com.localai.app.feature.agents.AgentDetailScreen(nav) }
                    composable("agentEdit/{id}") { com.localai.app.feature.agents.AgentFormScreen(nav) }
                    composable("memory/{agentId}") { com.localai.app.feature.agents.MemoryScreen(nav) }
                    composable("tools") { com.localai.app.feature.tools.ToolsScreen(nav) }
                    composable("knowledge") { com.localai.app.feature.knowledge.KnowledgeScreen(nav) }
                    composable("kbNew") { com.localai.app.feature.knowledge.KbFormScreen(nav) }
                    composable("kb/{id}") { com.localai.app.feature.knowledge.KbDetailScreen(nav) }
                    composable("doc/{id}") { com.localai.app.feature.knowledge.DocumentScreen(nav) }
                    composable("workflow") { com.localai.app.feature.workflow.WorkflowScreen(nav) }
                    composable("workflow/{runId}") { com.localai.app.feature.workflow.WorkflowScreen(nav) }
                }
            }
        }
    }
}
