package io.github.auxen.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.auxen.R
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.components.MiniPlayerBar
import io.github.auxen.ui.theme.resolveDarkTheme

/** A bottom-nav tab: its route, label, and icon. */
private data class Destination(val route: String, val label: String, val icon: @Composable () -> Unit)

/** The four primary bottom-nav destinations that show the brand top bar. */
private val TAB_ROUTES = setOf("home", "library", "search", "collection")

/** Global overlay routes pushed from the top bar, outside the tab back stacks. */
private val OVERLAY_ROUTES = setOf("equalizer", "account", "settings", "queue", "tidal-official-debug")

/**
 * Comfortable reading width for the main content on medium/expanded widths
 * (unfolded foldable, tablet). Content is capped to this and centered so it
 * doesn't stretch edge-to-edge on wide screens; on compact widths (folded
 * phone) content stays full width and this cap never engages.
 */
private val MAX_CONTENT_WIDTH = 840.dp

/**
 * Short, static label for the context-aware back top bar on detail/overlay
 * routes. Route strings keep their argument placeholders (e.g.
 * `album/{album}/{artist}`) so they classify by pattern, not by filled value;
 * the entity name is shown in each screen's own content header, not here.
 */
private fun topBarTitle(route: String?): String = when (route) {
    "album/{album}/{artist}" -> "Album"
    "artist/{artist}" -> "Artist"
    "playlist/{playlistId}" -> "Playlist"
    "settings" -> "Settings"
    "queue" -> "Queue"
    "equalizer" -> "Equalizer"
    "account" -> "Account"
    "tidal-official-debug" -> "Tidal Debug"
    else -> ""
}

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.loadLibrary() }
        permissionLauncher.launch(requiredPermissions())

        setContent {
            // Computed from the current window; recomposes on configuration
            // changes (including foldable fold/unfold, which resizes the
            // window), so the nav layout adapts live between phone and tablet.
            val windowSizeClass = calculateWindowSizeClass(this)
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = resolveDarkTheme(themeMode, systemDark = isSystemInDarkTheme())
            io.github.auxen.ui.theme.AuxenTheme(darkTheme = darkTheme) {
                MainScreen(viewModel, widthSizeClass = windowSizeClass.widthSizeClass)
            }
        }

        handleTidalOfficialRedirect(intent)
    }

    /**
     * `singleTask` (see the manifest) means the official-API PKCE redirect
     * lands here, not a fresh [onCreate] -- see the `auxen://auth-callback`
     * intent-filter (Tidal official-API migration, Task 1).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTidalOfficialRedirect(intent)
    }

    private fun handleTidalOfficialRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "auxen" && uri.host == "auth-callback") {
            viewModel.completeTidalOfficialLogin(uri)
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@UnstableApi
@Composable
private fun MainScreen(viewModel: PlayerViewModel, widthSizeClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    val destinations = listOf(
        Destination("home", "Home") { Icon(Icons.Filled.Home, contentDescription = null) },
        Destination("library", "Library") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
        Destination("search", "Search") { Icon(Icons.Filled.Search, contentDescription = null) },
        Destination("collection", "Collection") { Icon(Icons.Filled.Favorite, contentDescription = null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Compact width (folded phone / narrow) keeps the bottom NavigationBar;
    // medium/expanded (unfolded foldable, tablet) moves nav to a leading
    // NavigationRail in the content area instead.
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    // Shared tab navigation, used identically by the bottom bar and the rail so
    // a tab tap behaves the same in either layout: pop any global overlay first
    // (so a tab's saved state never captures one — restoring such state made
    // tab taps appear dead), then navigate with the same
    // saveState/launchSingleTop/restoreState back-stack handling.
    val onSelectTab: (String) -> Unit = { route ->
        while (navController.currentDestination?.route in OVERLAY_ROUTES) {
            navController.popBackStack()
        }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = {
            when {
                // Now Playing has its own in-screen top bar with a back arrow.
                currentRoute == "nowplaying" -> Unit
                // Primary bottom-nav tabs (and the initial null route before the
                // start destination resolves): brand block + global overlay
                // actions. The top bar is the Android analog of the desktop
                // sidebar brand block.
                currentRoute == null || currentRoute in TAB_ROUTES -> {
                    CenterAlignedTopAppBar(
                        title = { BrandBlock(compact = true) },
                        actions = {
                            IconButton(onClick = { navController.navigate("equalizer") { launchSingleTop = true } }) {
                                Icon(Icons.Filled.Equalizer, contentDescription = "Equalizer")
                            }
                            IconButton(onClick = { navController.navigate("settings") { launchSingleTop = true } }) {
                                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_gear_a11y))
                            }
                            IconButton(onClick = { navController.navigate("account") { launchSingleTop = true } }) {
                                Icon(Icons.Filled.Person, contentDescription = "Account")
                            }
                        },
                    )
                }
                // Detail (album/artist/playlist) + overlay (settings/queue/
                // equalizer/account/tidal-debug) screens are pushed onto a tab's
                // back stack: give them a persistent back affordance and a short
                // static route label, and none of the tab-level actions -- they
                // don't belong on a pushed screen, and each screen already shows
                // the entity name in its own content header.
                else -> {
                    CenterAlignedTopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        title = { Text(topBarTitle(currentRoute)) },
                    )
                }
            }
        },
        bottomBar = {
            if (currentRoute != "nowplaying") {
                Column {
                    val playbackError by viewModel.playbackError.collectAsState()
                    playbackError?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 4,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                )
                                IconButton(onClick = { viewModel.dismissPlaybackError() }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Dismiss error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                    MiniPlayerBar(viewModel, onOpen = { navController.navigate("nowplaying") })
                    // Bottom NavigationBar only on compact width; medium/expanded
                    // widths use the leading NavigationRail in the content area
                    // (the MiniPlayerBar above stays a full-width bottom bar in
                    // both layouts). Tab taps use the same onSelectTab as the rail.
                    if (!useRail) {
                        NavigationBar {
                            destinations.forEach { dest ->
                                NavigationBarItem(
                                    selected = currentRoute == dest.route,
                                    onClick = { onSelectTab(dest.route) },
                                    icon = dest.icon,
                                    label = { Text(dest.label) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        // Show the rail (and cap content width) on medium/expanded widths,
        // except on Now Playing, which hides all global chrome and manages its
        // own full-bleed layout. The NavHost stays at a single call site inside
        // the Box so toggling the rail (e.g. fold/unfold) never tears it down.
        val showRail = useRail && currentRoute != "nowplaying"
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (showRail) {
                // Insets already consumed by the Scaffold's innerPadding above,
                // so the rail adds none of its own (avoids double padding).
                NavigationRail(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    destinations.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { onSelectTab(dest.route) },
                            icon = dest.icon,
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.TopCenter,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    // Wide screens: cap to a comfortable reading width, centered
                    // by the Box. Compact/Now Playing: full width.
                    modifier = if (showRail) {
                        Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH)
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                    composable("home") { HomeScreen(viewModel) }
                    composable("library") {
                        LibraryScreen(
                            viewModel,
                            onOpenAlbum = { album ->
                                navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                            },
                            onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                        )
                    }
                    composable("album/{album}/{artist}") { backStack ->
                        AlbumDetailScreen(
                            viewModel,
                            album = backStack.arguments?.getString("album").orEmpty(),
                            artist = backStack.arguments?.getString("artist").orEmpty(),
                            onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                        )
                    }
                    composable("artist/{artist}") { backStack ->
                        ArtistDetailScreen(
                            viewModel,
                            artist = backStack.arguments?.getString("artist").orEmpty(),
                            onOpenAlbum = { album ->
                                navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                            },
                        )
                    }
                    composable("search") { SearchScreen(viewModel) }
                    composable("collection") {
                        CollectionScreen(
                            viewModel,
                            onOpenPlaylist = { id -> navController.navigate("playlist/$id") },
                            onOpenAlbum = { album ->
                                navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                            },
                            onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                        )
                    }
                    composable("playlist/{playlistId}") { backStack ->
                        PlaylistDetailScreen(
                            viewModel,
                            playlistId = backStack.arguments?.getString("playlistId")?.toLongOrNull() ?: -1L,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("equalizer") { EqualizerScreen() }
                    composable("settings") {
                        SettingsScreen(
                            viewModel,
                            onOpenTidalOfficialDebug = { navController.navigate("tidal-official-debug") { launchSingleTop = true } },
                        )
                    }
                    composable("tidal-official-debug") { TidalOfficialDebugScreen(viewModel) }
                    composable("queue") { QueueScreen(viewModel) }
                    composable("account") { AccountScreen(viewModel) }
                    composable("nowplaying") {
                        NowPlayingScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onOpenQueue = { navController.navigate("queue") { launchSingleTop = true } },
                        )
                    }
                }
            }
        }
    }
}
