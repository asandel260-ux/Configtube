package com.studyflow.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainVM = viewModel()
            StudyTheme(vm.settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Timer("Timer", Ico.Timer),
    Log("Log", Ico.Log),
    Stats("Stats", Ico.Stats),
    Subjects("Subjects", Ico.Folder),
    Settings("Settings", Ico.Settings),
}

@Composable
fun App(vm: MainVM) {
    val s = vm.settings
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity

    // Notification permission (Android 13+)
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Ticker that only runs while the app is visible
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.tick()
                delay(250)
            }
        }
    }

    // Keep screen on while timing
    LaunchedEffect(s.keepOn, vm.timer.running) {
        if (s.keepOn && vm.timer.running) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val tabs = remember(s.showLog, s.showStats) {
        buildList {
            add(Tab.Timer)
            if (s.showLog) add(Tab.Log)
            if (s.showStats) add(Tab.Stats)
            add(Tab.Subjects)
            add(Tab.Settings)
        }
    }
    var cur by rememberSaveable { mutableStateOf(Tab.Timer.name) }
    val tab = tabs.firstOrNull { it.name == cur } ?: Tab.Timer

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { cur = t.name },
                        icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        label = {
                            Text(t.label, fontWeight = if (t == tab) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Timer -> TimerScreen(vm)
                Tab.Log -> LogScreen(vm)
                Tab.Stats -> StatsScreen(vm)
                Tab.Subjects -> SubjectsScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}
