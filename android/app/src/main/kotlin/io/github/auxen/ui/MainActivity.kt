package io.github.auxen.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

/** Global overlay routes pushed from the top bar, outside the tab back stacks. */
private val OVERLAY_ROUTES = setOf("equalizer", "account", "settings")

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.loadLibrary() }
        permissionLauncher.launch(requiredPermissions())

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = resolveDarkTheme(themeMode, systemDark = isSystemInDarkTheme())
            io.github.auxen.ui.theme.AuxenTheme(darkTheme = darkTheme) {
                MainScreen(viewModel)
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun MainScreen(viewModel: PlayerViewModel) {
    val navController = rememberNavController()
    val destinations = listOf(
        Destination("home", "Home") { Icon(Icons.Filled.Home, contentDescription = null) },
        Destination("library", "Library") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
        Destination("search", "Search") { Icon(Icons.Filled.Search, contentDescription = null) },
        Destination("collection", "Collection") { Icon(Icons.Filled.Favorite, contentDescription = null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = {
            if (currentRoute != "nowplaying") {
                CenterAlignedTopAppBar(
                    // The top bar is the Android analog of the desktop sidebar
                    // brand block; suppressed on "account", which shows its own
                    // full BrandBlock at the top of its content instead, so the
                    // brand appears exactly once per screen.
                    title = { if (currentRoute != "account") BrandBlock(compact = true) },
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
                    NavigationBar {
                        destinations.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    // Equalizer/Account are global overlays pushed from the top
                                    // bar; pop them first so a tab's saved state never captures
                                    // them (restoring such state made tab taps appear dead).
                                    while (navController.currentDestination?.route in OVERLAY_ROUTES) {
                                        navController.popBackStack()
                                    }
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = dest.icon,
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
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
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                )
            }
            composable("artist/{artist}") { backStack ->
                ArtistDetailScreen(
                    viewModel,
                    artist = backStack.arguments?.getString("artist").orEmpty(),
                    onBack = { navController.popBackStack() },
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
            composable("settings") { SettingsScreen(viewModel) }
            composable("account") { AccountScreen(viewModel) }
            composable("nowplaying") { NowPlayingScreen(viewModel, onBack = { navController.popBackStack() }) }
        }
    }
}
