package io.neta.music.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import io.neta.music.data.TopList
import io.neta.music.ui.adaptive.BottomPlayerBar
import io.neta.music.ui.adaptive.BottomPlayerBarHeight
import io.neta.music.ui.adaptive.HomeTopBar
import io.neta.music.ui.adaptive.PhoneMiniPlayerBar
import io.neta.music.ui.adaptive.phoneOverlayHeight
import io.neta.music.ui.charts.discoverRoutes
import io.neta.music.ui.components.LocalAppSnackbar
import io.neta.music.ui.components.LocalCoverVisibilityScope
import io.neta.music.ui.components.LocalSharedTransitionScope
import io.neta.music.ui.components.LocalAppWindow
import io.neta.music.ui.components.LocalOverlayPadding
import io.neta.music.ui.components.LocalPageChromeScheme
import io.neta.music.ui.components.PageChromeScheme
import io.neta.music.ui.components.rememberAppWindow
import io.neta.music.ui.library.libraryRoutes
import io.neta.music.ui.player.PlayerScreen
import io.neta.music.ui.player.QueueSheet
import io.neta.music.ui.player.SleepTimerSheet
import io.neta.music.ui.poster.PosterWallBottomChrome
import io.neta.music.ui.poster.PosterWallScreen
import io.neta.music.ui.search.searchRoutes
import io.neta.music.ui.settings.settingsRoutes
import io.neta.music.ui.theme.PlayerTheme
import io.neta.music.ui.tv.TvNavRail
import io.neta.music.widget.SearchRequest
import io.neta.music.widget.WidgetTarget

private const val EXIT_BACK_WINDOW_MS = 2_000L

/** 顶层入口。触摸设备共用顶部 Tab，并在右上角保留固定设置入口。 */
enum class TopTab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Charts("charts", "榜单", Icons.Outlined.Insights, Icons.Filled.Insights),
    Playlists("playlists", "歌单", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
    Search("search", "搜索", Icons.Outlined.Search, Icons.Filled.Search),
    Library("library", "我的", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    Settings("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** 各功能的路由文件通过它拿到导航、ViewModel 和公共跳转。 */
class AppNav(
    val nav: NavHostController,
    val model: MusicViewModel,
    val actions: MusicActions,
    val openTab: (String) -> Unit,
    val openSettings: (String) -> Unit,
) {
    fun back() {
        nav.popBackStack()
    }
}

/** 设置里存的启动页路由 → 顶层入口；空或认不出来就回默认「榜单」。 */
private fun String?.toTopTab(): TopTab =
    TopTab.entries.firstOrNull { it.route == this } ?: TopTab.Charts

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MyMusicApp(model: MusicViewModel) {
    val state by model.state.collectAsState()
    // DataStore 第一帧没到之前整个外壳都不组合：启动页、主题、外观都要等设置落地，
    // 不然冷启动会先按默认值画一帧再跳走
    val ready by model.settingsReady.collectAsState()
    if (!ready) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    val favorites by model.favorites.collectAsState()
    val search by model.searchState.collectAsState()
    val sleep by model.sleepTimer.collectAsState()
    // 不在这里读 value：进度每 100ms 变一次，读了会让整棵界面跟着重组
    val position = model.position.collectAsState()
    val update by model.updateDetails.collectAsState()
    val updateProgress = model.updateState.collectAsState()

    val actions = remember(model) { MusicActions(model) }
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()
    val window = rememberAppWindow(state.appearance.layout)
    val pageChrome = remember { PageChromeScheme() }
    // 启动页只在建图时定一次；记住的是用户选的那个，切走的tab由返回栈同步
    val startupTab = state.startupTab.toTopTab()
    var selectedTab by rememberSaveable { mutableStateOf(startupTab.route) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var timerOpen by rememberSaveable { mutableStateOf(false) }

    fun openTab(tab: String) {
        val currentTab = nav.currentBackStack.value.lastOrNull { entry ->
            TopTab.entries.any { it.route == entry.destination.route }
        }?.destination?.route
        if (currentTab == tab) {
            selectedTab = tab
            nav.popBackStack(tab, inclusive = false)
            return
        }
        selectedTab = tab
        nav.navigate(tab) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openSettings(route: String) {
        // 设置子页归到设置 Tab，避免切回原入口时恢复出设置页面。
        openTab(TopTab.Settings.route)
        if (route != TopTab.Settings.route) {
            nav.navigate(route) { launchSingleTop = true }
        }
    }

    val app = remember(nav, model, actions) { AppNav(nav, model, actions, ::openTab, ::openSettings) }

    // 桌面小组件点进来要去的页面：冷启动和已在前台都从这里走（MainActivity 把 Intent 里的目标交给 ViewModel）
    val widgetTarget by model.widgetTarget.collectAsState()
    val lyricsRequest by model.lyricsViewRequest.collectAsState()
    LaunchedEffect(widgetTarget) {
        val target = widgetTarget ?: return@LaunchedEffect
        model.consumeWidgetTarget()
        // 播放页、定时面板之外的目标都在主界面上：先收起盖在上面的播放页和海报墙
        val overPlayer = target == WidgetTarget.Player || target == WidgetTarget.Lyrics || target == WidgetTarget.Timer
        if (!overPlayer) {
            actions.closePlayer()
            actions.closePosterWall()
        }
        when (target) {
            WidgetTarget.Player -> model.openPlayer(true)
            WidgetTarget.Lyrics -> {
                model.openPlayer(true)
                model.requestLyricsView()
            }
            WidgetTarget.Timer -> {
                model.openPlayer(true)
                timerOpen = true
            }
            WidgetTarget.Search -> {
                openTab(TopTab.Search.route)
                model.requestSearch(SearchRequest.Focus)
            }
            WidgetTarget.Recognize -> {
                openTab(TopTab.Search.route)
                model.requestSearch(SearchRequest.Recognize)
            }
            WidgetTarget.Login -> openSettings("settings/accounts")
            WidgetTarget.Favorites -> nav.navigate("favorites") { launchSingleTop = true }
            WidgetTarget.History -> nav.navigate("history") { launchSingleTop = true }
            WidgetTarget.Modes -> {
                openTab(TopTab.Playlists.route)
                actions.setPlaylistMode(PlaylistMode.Listening)
            }
            WidgetTarget.Charts -> openTab(TopTab.Charts.route)
            is WidgetTarget.Chart -> {
                openTab(TopTab.Charts.route)
                actions.openChart(TopList(id = target.id, name = target.name, source = target.source))
            }
            is WidgetTarget.LocalPlaylist -> nav.navigate("localPlaylist/${target.id}") { launchSingleTop = true }
            WidgetTarget.LocalMusic -> nav.navigate(io.neta.music.ui.local.LOCAL_MUSIC_ROUTE) { launchSingleTop = true }
            WidgetTarget.Updates -> openSettings("settings/about")
        }
    }

    // 选中的入口跟着返回栈走：详情页这类不是入口的页面归到栈里它下面最近的入口，
    // 设置子页统一选中“设置”，其余详情归到栈中最近的顶层入口。
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    LaunchedEffect(route) {
        if (route == null) return@LaunchedEffect
        val owner = nav.currentBackStack.value.asReversed().firstNotNullOfOrNull { tabOf(it.destination.route) }
        if (owner != null) selectedTab = owner
    }

    // 每次回到前台都通知一次：从“安装未知来源”设置页授权返回后要靠它继续安装
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onAppForeground() }
    LaunchedEffect(Unit) {
        // 两个互不相关的收集器合并成一个 effect，少一套协程与 effect 节点
        launch { model.syncEvents.collect { event -> snackbar.showSnackbar(event.message) } }
        model.events.collect { event ->
            when (event) {
                is UiEvent.OpenCollection -> nav.navigate("collection") { launchSingleTop = true }
                // showSnackbar 会挂起到提示消失，放在 collect 里会把后面的跳转事件卡住几秒
                is UiEvent.Message -> launch { snackbar.showSnackbar(event.text) }
                // 只在本机的本地歌加进了同步的歌单：给“上传”，点了打开上传面板；只提示，不自动传
                is UiEvent.UploadPrompt -> launch {
                    val result = snackbar.showSnackbar(
                        message = event.text,
                        actionLabel = "上传",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) model.openUploadSheet(event.ids)
                }
            }
        }
    }
    LaunchedEffect(state.error) {
        val message = state.error
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            actions.clearError()
        }
    }
    // 播放页打开时换源结果写在音源 chip 下面；播放页没打开、或者需要用户决定（去平台打开）时才用 Snackbar
    LaunchedEffect(state.deepLink, state.playbackNotice, state.playerOpen) {
        val message = state.playbackNotice
        if (message.isBlank()) return@LaunchedEffect
        if (state.deepLink.isNotBlank()) {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = "在平台打开",
                withDismissAction = true,
                // 电视播放页锁住了焦点，遥控器够不到这条提示，不能让它一直挡在控制条上
                duration = if (window.isTv && state.playerOpen) SnackbarDuration.Long else SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) actions.openDeepLink()
            actions.clearNotice()
        } else if (!state.playerOpen) {
            snackbar.showSnackbar(message)
            actions.clearNotice()
        }
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val overlayBottom = when {
        window.isPhone -> phoneOverlayHeight(hasMini = state.current != null) + navInset
        window.showBottomPlayerBar && state.current != null -> 0.dp
        else -> navInset
    }
    val playerVisible = state.playerOpen && state.current != null
    // 队列海报墙在主界面之上、播放页之下；播放页可以盖在墙上，关掉回到墙
    val posterVisible = state.posterOpen && state.current != null
    val covered = playerVisible || posterVisible
    val showHomeTopBar = !window.isTv && (
        route == null || TopTab.entries.any { it.route == route } || route.startsWith("settings/")
    )
    // 播放页还在组合里（含退场动画）：墙等它完全退场再拿遥控器焦点（播放页的焦点组退场途中不放焦点出去）
    val playerComposed = remember { mutableStateOf(false) }
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    CompositionLocalProvider(
        LocalAppWindow provides window,
        LocalAppSnackbar provides snackbar,
        LocalPageChromeScheme provides pageChrome,
    ) {
        // 共享元素：手机上正在播放的封面从迷你播放器飞到播放页。
        // 底色跟着页面的外壳配色走，深色页面（海报视图）时状态栏图标是浅色的
        val shellSurface = MaterialTheme.colorScheme.surface
        SharedTransitionLayout(Modifier.fillMaxSize().drawBehind { drawRect(pageChrome.scheme?.surface ?: shellSurface) }) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        Box(Modifier.fillMaxSize()) {
            // 手机横屏（≥600dp 走平板布局）时导航栏、挖孔在左右两侧，内容不能伸到下面
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    // 播放页或海报墙盖在上面时，下面的页面不让读屏读到、也不让遥控器焦点进入
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                if (window.isTv) {
                    TvNavRail(
                        selected = selectedTab,
                        current = state.current,
                        onSelect = ::openTab,
                        onSettings = { openSettings("settings") },
                        onOpenNowPlaying = actions::openNowPlaying,
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .then(if (showHomeTopBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
                ) {
                    if (showHomeTopBar) {
                        HomeTopBar(
                            selected = selectedTab,
                            onSelect = ::openTab,
                            onSettings = { openSettings("settings") },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalOverlayPadding provides PaddingValues(bottom = overlayBottom)) {
                            NavHost(
                                navController = nav,
                                startDestination = startupTab.route,
                                modifier = Modifier.fillMaxSize(),
                                enterTransition = { if (state.reduceMotion) EnterTransition.None else fadeIn(enterSpec) },
                                exitTransition = { if (state.reduceMotion) ExitTransition.None else fadeOut(exitSpec) },
                                popEnterTransition = { if (state.reduceMotion) EnterTransition.None else fadeIn(enterSpec) },
                                popExitTransition = { if (state.reduceMotion) ExitTransition.None else fadeOut(exitSpec) },
                            ) {
                                discoverRoutes(app)
                                searchRoutes(app)
                                libraryRoutes(app)
                                settingsRoutes(app)
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            // 海报墙开着时也收起：迷你播放器的封面不能在墙底下和播放页的封面配成共享元素飞出来
                            visible = window.isPhone && !covered,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            CompositionLocalProvider(LocalCoverVisibilityScope provides this) {
                                ShellChromeTheme(pageChrome) {
                                    PhoneMiniPlayerBar(
                                        state = state,
                                        position = position,
                                        actions = actions,
                                        onOpenQueue = { queueOpen = true },
                                    )
                                }
                            }
                        }
                    }
                    if (window.showBottomPlayerBar && state.current != null) {
                        ShellChromeTheme(pageChrome) {
                            BottomPlayerBar(state, position, actions, onOpenQueue = { queueOpen = true }, visible = !covered)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = posterVisible,
                enter = fadeIn(enterSpec),
                exit = fadeOut(exitSpec),
            ) {
                PlayerTheme {
                    val playlists by model.localPlaylists.collectAsState()
                    PosterWallScreen(
                        state = state,
                        position = position,
                        favorites = favorites,
                        playlists = playlists,
                        actions = actions,
                        active = !playerVisible,
                        focusReady = { !playerComposed.value },
                        onOpenQueue = { queueOpen = true },
                    )
                }
            }
            AnimatedVisibility(
                visible = playerVisible,
                // 手机上封面走共享元素，页面本身只淡入淡出；平板和电视没有迷你播放器，仍从下方滑入
                enter = if (state.reduceMotion || window.isPhone) fadeIn() else slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 3 } + fadeIn(),
                exit = if (state.reduceMotion || window.isPhone) fadeOut() else slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 3 } + fadeOut(),
            ) {
                DisposableEffect(Unit) {
                    playerComposed.value = true
                    onDispose { playerComposed.value = false }
                }
                CompositionLocalProvider(LocalCoverVisibilityScope provides this) {
                PlayerTheme {
                    PlayerScreen(
                        state = state,
                        position = position,
                        favorites = favorites,
                        sleep = sleep,
                        actions = actions,
                        onClose = actions::closePlayer,
                        onOpenQueue = { queueOpen = true },
                        onOpenTimer = { timerOpen = true },
                        sheetOpen = queueOpen || timerOpen,
                        lyricsRequest = lyricsRequest,
                        onLyricsRequestHandled = model::consumeLyricsView,
                    )
                }
                }
            }
            // 放在播放页之上：播放页打开时的错误、“在平台打开”等提示也要看得见；同时让开底部播放条
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(
                    bottom = 8.dp + when {
                        playerVisible -> navInset
                        // 让开海报墙右下角的定位和设置按钮
                        posterVisible -> navInset + if (window.isTv) 0.dp else PosterWallBottomChrome
                        window.showBottomPlayerBar && state.current != null -> BottomPlayerBarHeight + navInset
                        else -> overlayBottom
                    },
                ),
            )
        }
        }
        }

        if (queueOpen) {
            PlayerTheme { QueueSheet(state, actions, onDismiss = { queueOpen = false }) }
        }
        if (timerOpen) {
            PlayerTheme { SleepTimerSheet(state, sleep, actions, onDismiss = { timerOpen = false }) }
        }
        // 本地歌的上传面板（设计稿第 ⑥ 张）：本地音乐页、歌曲信息、加进歌单后的提示都从这里打开
        io.neta.music.ui.local.LocalUploadSheetHost(
            onOpenAccounts = { openSettings("settings/accounts") },
            onConnectWebDav = { nav.navigate(io.neta.music.ui.local.localWebDavRoute(app)) { launchSingleTop = true } },
        )
        if (state.sourcePickerTrack != null) {
            // 从播放页、海报墙打开时沿用它们的深色配色
            if (covered) PlayerTheme { SourcePickerSheet(state, actions) } else SourcePickerSheet(state, actions)
        }
        state.mergeTrack?.let { track ->
            val candidates = remember(search.aggregateGroups, state.canonicalQueue, track.id) {
                (search.aggregateGroups + state.canonicalQueue)
                    .distinctBy { it.id }
                    .filterNot { it.id == track.id }
            }
            MergeDialog(
                track = track,
                candidates = candidates,
                onMerge = { target -> actions.mergeTo(target) },
                onDismiss = actions::closeMerge,
            )
        }
        UpdateDialog(
            update = update,
            suppressed = route == "settings/about",
            actions = actions,
            progress = { updateProgress.value.progress },
        )
    }
}

/**
 * 电视顶层入口的返回键：第一次提示，2 秒内再按退出。
 * 放在各入口路由里，页面自己的返回处理（编辑、菜单、播放页）比它晚注册，仍然优先。
 */
@Composable
fun TvExitBackHandler() {
    if (!LocalAppWindow.current.isTv) return
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var lastBackAt by remember { mutableStateOf(0L) }
    // 电视没有手势，按键返回时事件流直接完成；统一走预测性返回，和触摸设备同一套分发
    PredictiveBackHandler { events ->
        try {
            events.collect { }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt in 1L..EXIT_BACK_WINDOW_MS) {
                (context as? Activity)?.finish()
            } else {
                lastBackAt = now
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("再按一次返回键退出应用")
                }
            }
        } catch (_: CancellationException) {
        }
    }
}

/** 设置子页归到设置 Tab，其余详情由返回栈中的顶层入口确定。 */
private fun tabOf(route: String?): String? = when {
    route == null -> null
    TopTab.entries.any { it.route == route } -> route
    route.startsWith("settings/") -> TopTab.Settings.route
    else -> null
}

/** 底部播放控件沿用页面登记的配色；配色变化不重建控件。 */
@Composable
private fun ShellChromeTheme(page: PageChromeScheme, content: @Composable () -> Unit) {
    val scheme = page.scheme
    val outerContent = LocalContentColor.current
    MaterialExpressiveTheme(
        colorScheme = scheme ?: MaterialTheme.colorScheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides (scheme?.onSurface ?: outerContent), content = content)
    }
}
