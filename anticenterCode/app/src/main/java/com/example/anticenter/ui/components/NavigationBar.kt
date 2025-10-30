package com.example.anticenter.ui.components


import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

sealed class Destination(
    val route: String,
    val label: String,
    val contentDescription: String,
    val icon: ImageVector
) {
    data object AntiPhishing : Destination(
        route = "anti_phishing",
        label = "Anti-Phishing",
        contentDescription = "Anti-Phishing",
        icon = Icons.Filled.FrontHand
    )

    data object Setting : Destination(
        route = "setting",
        label = "Setting",
        contentDescription = "Setting",
        icon = Icons.Filled.Settings
    )

    data object Test : Destination(
        route = "test",
        label = "Test",
        contentDescription = "Test Playground",
        icon = Icons.Filled.BugReport
    )
}
@Composable
fun BottomNavBar(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar(modifier = modifier) {
        val items = buildBottomNavItems()
        items.forEach { dest ->
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(dest.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                },
                icon = { Icon(dest.icon, contentDescription = dest.contentDescription) },
                label = { Text(dest.label) }
            )
        }
    }
}

@Composable
private fun buildBottomNavItems(): List<Destination> {
    val base = mutableListOf(
        Destination.AntiPhishing,
        Destination.Setting,
    )
    // 通过 BuildConfig 开关控制是否展示 Test 入口
    // 临时隐藏 Test 页面入口
    /*if (com.example.anticenter.FeatureFlags.ENABLE_TEST_SCREEN) {
        base.add(Destination.Test)
    }*/
    return base
}


