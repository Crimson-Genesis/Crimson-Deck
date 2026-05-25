package com.crimson.deck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crimson.deck.ui.screens.ConnectScreen
import com.crimson.deck.ui.screens.StreamScreen
import com.crimson.deck.ui.screens.SettingsScreen
import com.crimson.deck.ui.viewmodel.AgentViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val darkColorScheme = darkColorScheme(
                background = viewModel.themeBackground,
                surface = viewModel.themeBackground,
                primary = viewModel.themePrimary
            )
            MaterialTheme(colorScheme = darkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = viewModel.themeBackground
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "connect"
                    ) {
                        composable("connect") {
                            ConnectScreen(
                                viewModel = viewModel,
                                onNavigateToStream = {
                                    navController.navigate("stream")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        
                        composable("stream") {
                            StreamScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
