package com.example.anticenter

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.anticenter.ui.components.BottomNavBar
import com.example.anticenter.ui.components.Destination
import com.example.anticenter.ui.components.TopBar
import com.example.anticenter.ui.screens.AccessControlContent
import com.example.anticenter.ui.screens.FeatureContent
import com.example.anticenter.ui.screens.FormDisplay
import com.example.anticenter.ui.screens.TestScreen
import com.example.anticenter.database.DatabaseManager
import com.example.anticenter.services.CoreProtectionService
import com.example.compose.AppTheme

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 在应用启动时立即初始化数据库
        try {
            android.util.Log.d("MainActivity", "开始初始化数据库...")
            DatabaseManager.initialize(this)
            android.util.Log.d("MainActivity", "数据库初始化成功")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "数据库初始化失败", e)
        }
        
        enableEdgeToEdge()
        setContent {
            AppTheme {
//                Scaffold(
//                    topBar = {TopBar()}
//                    ,modifier = Modifier.fillMaxSize()) { innerPadding ->
//                    FeatureContent(SelectFeatures.callProtection,Modifier.padding(innerPadding))
//                }
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 在 Activity 进入前台时启动前台保护服务，避免 FGS 限制
        startProtectionService()
    }
    override fun onResume() {
        super.onResume()
        // Retry starting service when returning from settings (in case user granted permissions)
        // This ensures the service starts after RECORD_AUDIO permission is granted
        startProtectionService()
    }

    /**
     * 启动前台保护服务
     *
     * IMPORTANT: On Android 14+ (SDK 34+) with foregroundServiceType="microphone",
     * RECORD_AUDIO permission MUST be granted before starting the service.
     */
    private fun startProtectionService() {
        try {
            // Check if RECORD_AUDIO permission is granted (required for microphone foreground service type)
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasRecordAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires RECORD_AUDIO for foregroundServiceType="microphone"
                android.util.Log.w("MainActivity", "⚠️ Cannot start CoreProtectionService: RECORD_AUDIO permission not granted (required for microphone FGS type on Android 14+)")
                android.util.Log.w("MainActivity", "Service will start once permission is granted")
                return
            }

            val serviceIntent = Intent(this, CoreProtectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
                android.util.Log.d("MainActivity", "✅ Foreground Service Started (Android O+)")
            } else {
                this.startService(serviceIntent)
                android.util.Log.d("MainActivity", "✅ Service Started (Android < O)")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ Failed to start foreground service", e)
        }
    }



     override fun onDestroy() {
        super.onDestroy()
    }

}


   




@Composable
fun AppNavHost(navController: NavHostController,selectedFeature: SelectFeatures, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Destination.Setting.route,
        modifier = modifier
    ) {
        composable(Destination.AntiPhishing.route) {
            FeatureContent(
                selectFeatures = selectedFeature, 
                modifier = Modifier.fillMaxSize(),
                onNavigateToFormDisplay = {
                    navController.navigate("form_display")
                }
            )
        }
        composable(Destination.Setting.route) {
            AccessControlContent(modifier = Modifier.fillMaxSize())
        }
        // 可选的测试路由，按代码开关加入
        if (FeatureFlags.ENABLE_TEST_SCREEN) {
            composable(Destination.Test.route) {
                TestScreen(modifier = Modifier.fillMaxSize())
            }
        }
        composable("form_display") {
            FormDisplay(
                selectFeatures = selectedFeature,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val selectedFeature = remember { mutableStateOf(SelectFeatures.callProtection) }
    val stringToFeature = mapOf(
        "Call Protection" to SelectFeatures.callProtection,
        "Meeting Protection" to SelectFeatures.meetingProtection,
        "URL Protection" to SelectFeatures.urlProtection,
        "Email Protection" to SelectFeatures.emailProtection
    )

    Scaffold(
        topBar = {
            when (currentRoute) {
                Destination.AntiPhishing.route -> {
                    TopBar(
                        selectedFeature = selectedFeature.value,
                        onMenuItemClick = { menuItem ->
                            // 根据菜单项字符串找到对应的枚举值
                            stringToFeature[menuItem]?.let { feature ->
                                selectedFeature.value = feature
                            }
                        }
                    )
                }
                "form_display" -> {
                    CenterAlignedTopAppBar(
                        title = { 
                            Text("Alert Log - ${getFeatureDisplayName(selectedFeature.value)}") 
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
                // Setting 页面不显示 TopBar
            }
        },
        bottomBar = { BottomNavBar(navController) }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            selectedFeature = selectedFeature.value
        )
    }
}

private fun getFeatureDisplayName(feature: SelectFeatures): String {
    return when (feature) {
        SelectFeatures.callProtection -> "Call Protection"
        SelectFeatures.meetingProtection -> "Meeting Protection"
        SelectFeatures.urlProtection -> "URL Protection"
        SelectFeatures.emailProtection -> "Email Protection"
        SelectFeatures.emailBlacklist -> "Email Blacklist"
    }
}



