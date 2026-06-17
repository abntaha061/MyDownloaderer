package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var downloadRepository: DownloadRepository

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var ytdlpEngine: YtdlpEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeStyle by settingsManager.themeStyle.collectAsState(initial = "system")
            val darkTheme = when (themeStyle) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()
                var currentRoute by remember { mutableStateOf("history") }

                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            val items = listOf(
                                Triple("history", "الرئيسية", Icons.Rounded.History),
                                Triple("terminal", "الطرفية", Icons.Rounded.Terminal),
                                Triple("settings", "الإعدادات", Icons.Rounded.Settings)
                            )
                            items.forEach { (route, label, icon) ->
                                val selected = currentRoute == route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (currentRoute != route) {
                                            currentRoute = route
                                            navController.navigate(route) {
                                                popUpTo(navController.graph.startDestinationId) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        Icon(imageVector = icon, contentDescription = label)
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { paddingValues ->
                    NavHost(
                        navController = navController,
                        startDestination = "history",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        composable("history") {
                            HistoryScreen(
                                downloadRepository = downloadRepository,
                                onNavigateToTerminal = {
                                    currentRoute = "terminal"
                                    navController.navigate("terminal")
                                },
                                onNavigateBack = {
                                    // Main launcher screen back press pop state
                                }
                            )
                        }

                        composable("terminal") {
                            TerminalScreen(
                                onNavigateBack = {
                                    currentRoute = "history"
                                    navController.navigate("history") {
                                        popUpTo("history") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                settingsManager = settingsManager,
                                ytdlpEngine = ytdlpEngine
                            )
                        }
                    }
                }
            }
        }
    }
}
