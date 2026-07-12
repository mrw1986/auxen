package io.github.auxen.ui

import android.Manifest
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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi

private data class Tab(val label: String, val icon: @Composable () -> Unit)

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

@UnstableApi
@Composable
private fun MainScreen(viewModel: PlayerViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Library") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
        Tab("Search") { Icon(Icons.Filled.Search, contentDescription = null) },
        Tab("Favorites") { Icon(Icons.Filled.Favorite, contentDescription = null) },
        Tab("Equalizer") { Icon(Icons.Filled.Equalizer, contentDescription = null) },
        Tab("Account") { Icon(Icons.Filled.Person, contentDescription = null) },
    )

    Scaffold(
        bottomBar = {
            Column {
                NowPlayingBar(viewModel)
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = tab.icon,
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            0 -> LibraryScreen(viewModel, contentModifier)
            1 -> SearchScreen(viewModel, contentModifier)
            2 -> FavoritesScreen(viewModel, contentModifier)
            3 -> EqualizerScreen(contentModifier)
            4 -> AccountScreen(viewModel, contentModifier)
        }
    }
}
