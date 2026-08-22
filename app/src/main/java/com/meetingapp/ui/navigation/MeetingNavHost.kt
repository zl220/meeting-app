package com.meetingapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meetingapp.ui.screen.ActiveMeetingScreen
import com.meetingapp.ui.screen.MeetingListScreen
import com.meetingapp.ui.screen.MeetingSetupScreen
import com.meetingapp.ui.screen.MinutesReviewScreen
import com.meetingapp.ui.screen.SettingsScreen

sealed class Screen(val route: String) {
    object List : Screen("meetings")
    object Setup : Screen("setup")
    object Active : Screen("active/{meetingId}") {
        fun go(id: Long) = "active/$id"
    }
    object Review : Screen("review/{meetingId}") {
        fun go(id: Long) = "review/$id"
    }
    object Settings : Screen("settings")
}

@Composable
fun MeetingNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.List.route) {
        composable(Screen.List.route) {
            MeetingListScreen(
                onNewMeeting = { navController.navigate(Screen.Setup.route) },
                onOpenActive = { id -> navController.navigate(Screen.Active.go(id)) },
                onOpenReview = { id -> navController.navigate(Screen.Review.go(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Setup.route) {
            MeetingSetupScreen(
                onCreated = { id ->
                    navController.navigate(Screen.Active.go(id)) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Active.route,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType })
        ) { back ->
            val id = back.arguments?.getLong("meetingId") ?: return@composable
            ActiveMeetingScreen(
                meetingId = id,
                onFinished = { navController.navigate(Screen.Review.go(id)) {
                    popUpTo(Screen.Active.route) { inclusive = true }
                }}
            )
        }
        composable(
            route = Screen.Review.route,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType })
        ) { back ->
            val id = back.arguments?.getLong("meetingId") ?: return@composable
            MinutesReviewScreen(
                meetingId = id,
                onDone = { navController.navigate(Screen.List.route) {
                    popUpTo(0) { inclusive = true }
                }}
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
