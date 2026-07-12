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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.auxen.ui.components.MiniPlayerBar

/** A bottom-nav tab: its route, label, and icon. */
private data class Destination(val route: String, val label: String, val icon: @Composable () -> Unit)

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.loadLibrary() }
        permissionLauncher.launch(requiredPermissions())

        setContent {
            io.github.auxen.ui.theme.AuxenTheme {
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
                    title = { Text("Auxen", style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        IconButton(onClick = { navController.navigate("equalizer") { launchSingleTop = true } }) {
                            Icon(Icons.Filled.Equalizer, contentDescription = "Equalizer")
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
                    MiniPlayerBar(viewModel, onOpen = { navController.navigate("nowplaying") })
                    NavigationBar {
                        destinations.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
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
                    album = Uri.decode(backStack.arguments?.getString("album") ?: ""),
                    artist = Uri.decode(backStack.arguments?.getString("artist") ?: ""),
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                )
            }
            composable("artist/{artist}") { backStack ->
                ArtistDetailScreen(
                    viewModel,
                    artist = Uri.decode(backStack.arguments?.getString("artist") ?: ""),
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
                DetailPlaceholder(
                    title = "Playlist ${backStack.arguments?.getString("playlistId")}",
                    onBack = { navController.popBackStack() },
                )
            }
            composable("equalizer") { EqualizerScreen() }
            composable("account") { AccountScreen(viewModel) }
            composable("nowplaying") { NowPlayingScreen(viewModel, onBack = { navController.popBackStack() }) }
        }
    }
}

/** Replaced by real detail screens in Task 7. */
@Composable
private fun DetailPlaceholder(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(title, style = MaterialTheme.typography.displaySmall)
    }
}
