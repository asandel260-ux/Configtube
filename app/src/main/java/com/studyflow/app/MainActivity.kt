package com.studyflow.app

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column as ColumnLayout
import com.studyflow.app.p2p.InviteCodec
import com.studyflow.app.p2p.Social
import java.time.LocalDate
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
    private var pendingInvite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInvite = intent?.dataString
        setContent {
            val vm: MainVM = viewModel()
            StudyTheme(vm.settings) {
                CompositionLocalProvider(LocalAnimations provides vm.settings.animations) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(vm, pendingInvite) { pendingInvite = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInvite = intent.dataString
    }

    override fun onStart() {
        super.onStart()
        Social.get(this).acquire("ui")
    }

    override fun onStop() {
        Social.get(this).release("ui")
        super.onStop()
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Timer("Timer", Ico.Timer),
    Log("Log", Ico.Log),
    Stats("Stats", Ico.Stats),
    Groups("Groups", Ico.Group),
    Settings("Settings", Ico.Settings),
}

@Composable
fun App(vm: MainVM, invite: String?, onInviteHandled: () -> Unit) {
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
            add(Tab.Groups)
            add(Tab.Settings)
        }
    }
    var cur by rememberSaveable { mutableStateOf(Tab.Timer.name) }
    val tab = tabs.firstOrNull { it.name == cur } ?: Tab.Timer

    // Full-screen pages (leaderboard, groups, profiles...) are a stack of route strings above the tabs.
    var stack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    fun push(r: String) { stack = ArrayList(stack + r) }
    fun pop() { if (stack.isNotEmpty()) stack = ArrayList(stack.dropLast(1)) }
    fun replace(r: String) { stack = ArrayList(stack.dropLast(1) + r) }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    LaunchedEffect(invite) {
        if (invite != null) {
            val inv = InviteCodec.parse(invite)
            if (inv != null) {
                vm.social.peek(inv)
                push("g:" + inv.gref)
            }
            onInviteHandled()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tabs, tab) { cur = it.name } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val anims = LocalAnimations.current
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (!anims) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val dir = if (tabs.indexOf(targetState) > tabs.indexOf(initialState)) 1 else -1
                        (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { full -> dir * full / 8 } +
                            fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(200)) { full -> -dir * full / 8 } + fadeOut(tween(160)))
                    }
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    Tab.Timer -> TimerScreen(vm) { push("lb") }
                    Tab.Log -> LogScreen(vm) { push("sub") }
                    Tab.Stats -> StatsScreen(vm)
                    Tab.Groups -> GroupsScreen(vm, { push("g:$it") }) { push("find") }
                    Tab.Settings -> SettingsScreen(vm) { push("p:" + vm.social.me) }
                }
            }
        }
    }

    val anims = LocalAnimations.current
    AnimatedContent(
        targetState = Pair(stack.lastOrNull(), stack.size),
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val push = targetState.second > initialState.second
            val root = initialState.second == 0 || targetState.second == 0
            when {
                !anims -> EnterTransition.None togetherWith ExitTransition.None
                root && push ->
                    (slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(300))) togetherWith
                        fadeOut(tween(120))
                root ->
                    fadeIn(tween(1)) togetherWith (slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(240)))
                push ->
                    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 8 } + fadeOut(tween(200)))
                else ->
                    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "pages",
    ) { (route, _) ->
        if (route != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RouteHost(vm, route, ::push, ::pop, ::replace)
            }
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
    }
}

@Composable
private fun RouteHost(vm: MainVM, route: String, push: (String) -> Unit, pop: () -> Unit, replace: (String) -> Unit) {
    val kind = route.substringBefore(':')
    val arg = route.substringAfter(':', "")
    when (kind) {
        "lb" -> LeaderboardScreen(vm, pop, { push("p:$it") }) { push("find") }
        "sub" -> ColumnLayout(Modifier.fillMaxSize()) {
            TopBar("", pop)
            SubjectsScreen(vm)
        }
        "find" -> GroupFinderScreen(vm, pop, { push("g:$it") }) { replace("g:$it") }
        "g" -> GroupScreen(vm, arg, pop) { push("p:$it") }
        "p" -> ProfileScreen(vm, arg, pop) { d -> push("d:$arg:$d") }
        "d" -> {
            val pub = arg.substringBefore(':')
            val date = try { LocalDate.parse(arg.substringAfter(':')) } catch (e: Exception) { LocalDate.now() }
            DayScreen(vm, pub, date, pop)
        }
        else -> Box(Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------------------
// Bottom bar: Timer is the raised centre action, other tabs sit either side.
// ---------------------------------------------------------------------------

@Composable
private fun BottomBar(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    val others = tabs.filter { it != Tab.Timer }
    val half = (others.size + 1) / 2
    val left = others.take(half)
    val right = others.drop(half)
    val slots = maxOf(left.size, right.size)
    val leftSlots: List<Tab?> = List(slots - left.size) { null } + left
    val rightSlots: List<Tab?> = right + List(slots - right.size) { null }
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            leftSlots.forEach { t ->
                if (t != null) NavItem(t, t == current, Modifier.weight(1f)) { onSelect(t) }
                else Spacer(Modifier.weight(1f))
            }
            TimerButton(current == Tab.Timer, Modifier.weight(1.15f)) { onSelect(Tab.Timer) }
            rightSlots.forEach { t ->
                if (t != null) NavItem(t, t == current, Modifier.weight(1f)) { onSelect(t) }
                else Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        animSpec(200), label = "navColor",
    )
    val rule by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent,
        animSpec(200), label = "navRule",
    )
    Column(
        modifier
            .fillMaxHeight()
            .drawBehind { drawRect(rule, size = Size(size.width, 2.dp.toPx())) }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = c)
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = c,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TimerButton(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val anims = LocalAnimations.current
    val spec: AnimationSpec<Float> = if (anims) spring(dampingRatio = 0.65f, stiffness = 300f) else snap()
    val sc by animateFloatAsState(if (selected) 1f else 0.9f, spec, label = "timerScale")
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .offset(y = (-12).dp)
                .size(64.dp)
                .graphicsLayer { scaleX = sc; scaleY = sc }
                .background(MaterialTheme.colorScheme.background, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Ico.Timer, contentDescription = "Timer",
                modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
