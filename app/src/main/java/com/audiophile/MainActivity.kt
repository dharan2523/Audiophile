package com.audiophile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.audiophile.data.AudioRepository
import com.audiophile.data.AudioTrack
import com.audiophile.domain.LyricLine
import com.audiophile.domain.LyricsRepository
import com.audiophile.domain.LocalMusicSource
import com.audiophile.domain.SourceRegistry
import com.audiophile.domain.SourceStatus
import com.audiophile.playback.PlaybackController
import com.audiophile.security.OAuthClient
import com.audiophile.security.OAuthProviders
import com.audiophile.security.GoogleDriveAuth
import com.audiophile.domain.TelegramAuthState
import com.audiophile.domain.TelegramSource
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppIconManager(this).initialize()
        val app = application as AudiophileApplication
        appViewModel = ViewModelProvider(this, MainViewModel.factory(app))[MainViewModel::class.java]
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) appViewModel.scanLibrary()
            }
            val googleSignInLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
                appViewModel.handleGoogleSignInResult(result.data)
            }
            AudiophileTheme {
                Surface(Modifier.fillMaxSize()) {
                    AudiophileApp(
                        appViewModel,
                        requestPermission = { launcher.launch(permission) },
                        openBrowser = { uri -> startActivity(Intent(Intent.ACTION_VIEW, uri)) },
                        startGoogleSignIn = { googleSignInLauncher.launch(app.googleDriveAuth.signInIntent()) }
                    )
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            appViewModel.scanLibrary()
        }
        handleOAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleOAuthIntent(intent) }
    private fun handleOAuthIntent(intent: Intent) { intent.data?.let(appViewModel::handleOAuthCallback) }
}

private data class AppIconOption(val id: String, val label: String, val drawableRes: Int, val aliasName: String)

private class AppIconManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun initialize() {
        val selected = preferences.getString(KEY_SELECTED, DEFAULT_ID).orEmpty()
        val option = OPTIONS.firstOrNull { it.id == selected } ?: OPTIONS.first { it.id == DEFAULT_ID }
        preferences.edit().putString(KEY_SELECTED, option.id).apply()
        setEnabledAlias(option)
    }

    fun select(id: String) {
        val option = OPTIONS.firstOrNull { it.id == id } ?: return
        preferences.edit().putString(KEY_SELECTED, option.id).apply()
        setEnabledAlias(option)
    }

    fun selectedId(): String = preferences.getString(KEY_SELECTED, DEFAULT_ID).orEmpty()

    private fun setEnabledAlias(selected: AppIconOption) {
        OPTIONS.forEach { option ->
            appContext.packageManager.setComponentEnabledSetting(
                ComponentName(appContext, option.aliasName),
                if (option.id == selected.id) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    companion object {
        private const val KEY_SELECTED = "selectedAppIcon"
        private const val DEFAULT_ID = "blue_nn"
        val OPTIONS = listOf(
            AppIconOption("icon_1", "Icon 1", R.drawable.app_icon_1, "com.audiophile.AppIcon1Alias"),
            AppIconOption("icon_2", "Icon 2", R.drawable.app_icon_2, "com.audiophile.AppIcon2Alias"),
            AppIconOption("icon_3", "Icon 3", R.drawable.app_icon_3, "com.audiophile.AppIcon3Alias"),
            AppIconOption("icon_4", "Icon 4", R.drawable.app_icon_4, "com.audiophile.AppIcon4Alias"),
            AppIconOption("icon_5", "Icon 5", R.drawable.app_icon_5, "com.audiophile.AppIcon5Alias"),
            AppIconOption("icon_6", "Icon 6", R.drawable.app_icon_6, "com.audiophile.AppIcon6Alias"),
            AppIconOption("icon_7", "Icon 7", R.drawable.app_icon_7, "com.audiophile.AppIcon7Alias"),
            AppIconOption("icon_8", "Icon 8", R.drawable.app_icon_8, "com.audiophile.AppIcon8Alias"),
            AppIconOption("blue_nn", "BlueNN", R.drawable.icon_bluenn, "com.audiophile.AppIconBlueNNAlias"),
            AppIconOption("red_nn", "RedNN", R.drawable.app_icon_red_nn, "com.audiophile.AppIconRedNNAlias")
        )
    }
}

data class AuthUiState(val loading: Boolean = false, val message: String? = null, val success: Boolean = false)

class MainViewModel(private val repository: AudioRepository, private val playback: PlaybackController, private val lyricsRepository: LyricsRepository, private val sourceRegistry: SourceRegistry, private val oauthClient: OAuthClient, private val googleDriveAuth: GoogleDriveAuth, private val telegramSource: TelegramSource) : ViewModel() {
    val tracks = sourceRegistry.observeAllTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent = repository.recentlyPlayed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val current = playback.current
    val isPlaying = playback.isPlaying
    val positionMs = playback.positionMs
    val isShuffleEnabled = playback.shuffleEnabled
    val repeatMode = playback.repeatMode
    val sourceStatuses = sourceRegistry.statuses
    private val _connectingSources = MutableStateFlow<Set<String>>(emptySet())
    val connectingSources: StateFlow<Set<String>> = _connectingSources
    private val _authState = MutableStateFlow(AuthUiState())
    val authState: StateFlow<AuthUiState> = _authState
    val telegramAuthState = telegramSource.authState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelegramAuthState.Disconnected)
    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics
    private val query = MutableStateFlow("")
    val searchResults = query.debounce(300).flatMapLatest { sourceRegistry.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            playback.current.collectLatest { track ->
                _lyrics.value = emptyList()
                if (track != null) _lyrics.value = lyricsRepository.load(track)
            }
        }
    }

    fun scanLibrary() { viewModelScope.launch { repository.scanLocalStorage() } }
    fun setQuery(value: String) { query.value = value }
    fun play(track: AudioTrack, queue: List<AudioTrack>) { viewModelScope.launch { repository.markPlayed(track.id); playback.play(track, queue) } }
    fun refreshLyrics(track: AudioTrack) { viewModelScope.launch { _lyrics.value = lyricsRepository.load(track) } }
    fun saveLyrics(track: AudioTrack, raw: String) {
        if (raw.isNotBlank()) {
            lyricsRepository.save(track, raw)
            viewModelScope.launch { _lyrics.value = lyricsRepository.load(track) }
        }
    }
    fun toggleFavorite(id: Long) { viewModelScope.launch { repository.toggleFavorite(id) } }
    fun togglePlayback() = playback.toggle()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    fun connectSource(id: String) { viewModelScope.launch { _connectingSources.value += id; sourceRegistry.connect(id); _connectingSources.value -= id } }
    fun disconnectSource(id: String) {
        viewModelScope.launch {
            sourceRegistry.disconnect(id)
            if (id == "drive") googleDriveAuth.disconnect()
        }
    }
    fun submitTelegramPhone(phone: String) { viewModelScope.launch { telegramSource.submitPhone(phone) } }
    fun submitTelegramCode(code: String) { viewModelScope.launch { telegramSource.submitCode(code) } }
    fun submitTelegramPassword(password: String) { viewModelScope.launch { telegramSource.submitPassword(password) } }
    fun beginOAuth(sourceId: String): Uri? {
        val provider = OAuthProviders.dropbox
        val clientId = BuildConfig.DROPBOX_CLIENT_ID
        if (clientId.isBlank()) {
            val message = if (BuildConfig.DEBUG) {
                "Dropbox is not configured. Add audiophileDropboxClientId to local.properties and rebuild."
            } else {
                "Google Drive is temporarily unavailable."
            }
            _authState.value = AuthUiState(message = message)
            return null
        }
        _authState.value = AuthUiState(loading = true, message = "Opening Dropbox authorization...")
        return oauthClient.begin(provider, clientId, OAuthProviders.redirectUri)
    }

    fun handleGoogleSignInResult(data: Intent?) {
        viewModelScope.launch {
            _authState.value = AuthUiState(loading = true, message = "Completing Google Drive authorization...")
            val result = googleDriveAuth.completeSignIn(data)
            if (result.isFailure) {
                _authState.value = AuthUiState(message = googleFailureMessage(result.exceptionOrNull()))
                return@launch
            }
            val sourceResult = sourceRegistry.connect("drive")
            _authState.value = if (sourceResult.isSuccess) {
                AuthUiState(message = "Google Drive connected", success = true)
            } else {
                AuthUiState(message = "Google Drive authorization succeeded, but files could not be loaded. Check your connection and try again.")
            }
        }
    }
    fun clearAuthMessage() { _authState.value = AuthUiState() }
    private fun googleFailureMessage(error: Throwable?): String {
        val statusCode = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
        return when (statusCode) {
            com.google.android.gms.common.ConnectionResult.CANCELED -> "Google Drive authorization cancelled."
            com.google.android.gms.common.ConnectionResult.NETWORK_ERROR -> "Google Drive authorization failed because the network is unavailable. Try again."
            else -> "Google Drive authorization failed. Check the Android OAuth client configuration and try again."
        }
    }
    fun handleOAuthCallback(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (error != null) { _authState.value = AuthUiState(message = if (error == "access_denied") "Google Drive sign-in cancelled." else "Google Drive sign-in failed. Try again."); return }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) { _authState.value = AuthUiState(message = "Google Drive sign-in failed. The authorization response was incomplete."); return }
        val state = uri.getQueryParameter("state")
        val provider = when {
            state != null && state == oauthClient.expectedState(OAuthProviders.dropbox) -> OAuthProviders.dropbox
            else -> { _authState.value = AuthUiState(message = "Authorization state could not be verified."); return }
        }
        viewModelScope.launch {
            try {
                _authState.value = AuthUiState(loading = true, message = "Completing authorization...")
                val clientId = BuildConfig.DROPBOX_CLIENT_ID
                if (clientId.isBlank()) {
                    _authState.value = AuthUiState(message = "Dropbox setup is incomplete. Please configure its client ID.")
                    return@launch
                }
                val result = oauthClient.exchangeCode(provider, clientId, OAuthProviders.redirectUri, code)
                if (result.isSuccess) {
                    val sourceResult = sourceRegistry.connect("dropbox")
                    if (sourceResult.isSuccess) {
                        _authState.value = AuthUiState(message = "Dropbox connected", success = true)
                    } else {
                        _authState.value = AuthUiState(message = "Dropbox authorization succeeded, but files could not be loaded. Check your connection and try again.")
                    }
                } else {
                    _authState.value = AuthUiState(message = oauthFailureMessage(provider.id, result.exceptionOrNull()))
                }
            } catch (_: Exception) {
                _authState.value = AuthUiState(message = "Google Drive sign-in failed. Try again or cancel.")
            }
        }
    }

    private fun oauthFailureMessage(providerId: String, error: Throwable?): String {
        val providerName = "Dropbox"
        return when {
            error?.message?.contains("Authentication expired", ignoreCase = true) == true -> "$providerName authorization expired. Connect again."
            error?.message?.contains("failed", ignoreCase = true) == true -> "$providerName authorization failed. Please try again."
            else -> "$providerName authorization could not be completed. Please try again."
        }
    }

    override fun onCleared() { playback.release() }
    companion object {
        fun factory(app: AudiophileApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app.repository, PlaybackController(app), LyricsRepository(app.contentResolver, app), app.sourceRegistry, app.oauthClient, app.googleDriveAuth, app.telegramSource) as T
        }
    }
}

@Composable
private fun AudiophileApp(vm: MainViewModel, requestPermission: () -> Unit, openBrowser: (Uri) -> Unit, startGoogleSignIn: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AudioTrack?>(null) }
    var showFullLyrics by remember { mutableStateOf(false) }
    var googleSetupOpen by remember { mutableStateOf(false) }
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recent by vm.recent.collectAsState()
    val search by vm.searchResults.collectAsState()
    val current by vm.current.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val sourceStatuses by vm.sourceStatuses.collectAsState()
    val connectingSources by vm.connectingSources.collectAsState()
    val authState by vm.authState.collectAsState()
    val telegramAuthState by vm.telegramAuthState.collectAsState()
    val shuffleEnabled by vm.isShuffleEnabled.collectAsState()
    val repeatMode by vm.repeatMode.collectAsState()
    if (selected != null) {
        val playingTrack = current ?: selected!!
        Crossfade(targetState = showFullLyrics, label = "lyrics navigation") { fullLyrics ->
            if (fullLyrics) {
                FullLyricsScreen(
                    track = playingTrack,
                    positionMs = positionMs,
                    lyrics = lyrics,
                    onBack = { showFullLyrics = false },
                    onRefreshLyrics = { vm.refreshLyrics(playingTrack) },
                    onSaveLyrics = { vm.saveLyrics(playingTrack, it) }
                )
            } else {
                ImmersiveNowPlaying(
                    track = playingTrack,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    lyrics = lyrics,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onBack = { showFullLyrics = false; selected = null },
                    onOpenLyrics = { showFullLyrics = true },
                    onToggle = vm::togglePlayback,
                    onPrevious = vm::previous,
                    onNext = vm::next,
                    onSeek = vm::seekTo,
                    onShuffle = vm::toggleShuffle,
                    onRepeat = vm::cycleRepeat
                )
            }
        }
        return
    }
    val tabs = listOf("Home", "Library", "Search", "Sources", "Settings")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { NavigationBar { tabs.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(if (index == 0) Icons.Rounded.Home else if (index == 1) Icons.Rounded.LibraryMusic else if (index == 2) Icons.Rounded.Search else if (index == 3) Icons.Rounded.Source else Icons.Rounded.Settings, label) }, label = { Text(label) }) } } }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(padding, recent, favorites, tracks, current, vm, { selected = it }, requestPermission)
            1 -> LibraryScreen(padding, tracks, vm, { selected = it })
            2 -> SearchScreen(padding, search, vm, { selected = it })
            3 -> SourcesScreen(padding, sourceStatuses, connectingSources, authState, telegramAuthState, { id -> if (id == "dropbox") vm.beginOAuth(id)?.let(openBrowser) else vm.connectSource(id) }, vm::disconnectSource, vm::submitTelegramPhone, vm::submitTelegramCode, vm::submitTelegramPassword, onGoogleSetup = { googleSetupOpen = true }, onClearAuthMessage = vm::clearAuthMessage)
            else -> SettingsScreen(padding)
        }
        if (googleSetupOpen) {
            GoogleDriveSetupSheet(onDismiss = { googleSetupOpen = false }, onSignIn = { googleSetupOpen = false; startGoogleSignIn() })
        }
    }
}

@Composable
private fun HomeScreen(padding: PaddingValues, recent: List<AudioTrack>, favorites: List<AudioTrack>, tracks: List<AudioTrack>, current: AudioTrack?, vm: MainViewModel, open: (AudioTrack) -> Unit, requestPermission: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Good evening", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text("Your listening space", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (tracks.isEmpty()) item { EmptyLibrary(requestPermission) }
        if (recent.isNotEmpty()) { item { SectionTitle("Recently played") }; items(recent.take(5), key = { it.id }) { TrackRow(it, it.id == current?.id, vm, recent, { open(it) }) } }
        item { SectionTitle("Your library") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("${tracks.size}", "Tracks", Icons.Rounded.LibraryMusic, Modifier.weight(1f)); StatCard("${favorites.size}", "Favorites", Icons.Rounded.Favorite, Modifier.weight(1f)); StatCard("${tracks.map { it.album }.distinct().size}", "Albums", Icons.Rounded.Album, Modifier.weight(1f)) } }
    }
}

@Composable
private fun LibraryScreen(padding: PaddingValues, tracks: List<AudioTrack>, vm: MainViewModel, open: (AudioTrack) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text("${tracks.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)) }; itemsIndexed(tracks, key = { _, it -> it.id }) { _, track -> TrackRow(track, false, vm, tracks, { open(track) }) } }
}

@Composable
private fun SearchScreen(padding: PaddingValues, tracks: List<AudioTrack>, vm: MainViewModel, open: (AudioTrack) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it; vm.setQuery(it) }, Modifier.fillMaxWidth().padding(top = 16.dp), placeholder = { Text("Songs, artists, albums") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(tracks, key = { it.id }) { TrackRow(it, false, vm, tracks, { open(it) }) } }
    }
}

@Composable
private fun SourcesScreen(
    padding: PaddingValues,
    statuses: List<SourceStatus>,
    connecting: Set<String>,
    authState: AuthUiState,
    telegramAuthState: TelegramAuthState,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onTelegramPhone: (String) -> Unit,
    onTelegramCode: (String) -> Unit,
    onTelegramPassword: (String) -> Unit,
    onGoogleSetup: () -> Unit,
    onClearAuthMessage: () -> Unit
) {
    var telegramInput by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sources", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("One library, wherever your music lives.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        authState.message?.let { message ->
            Text(message, color = if (authState.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (!authState.success && !authState.loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onGoogleSetup) { Text("Try again") }
                    TextButton(onClick = onClearAuthMessage) { Text("Cancel") }
                }
            }
        }
        statuses.forEach { status ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Source, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(status.name, fontWeight = FontWeight.Medium); Text(status.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    if (status.connected) TextButton(onClick = { onDisconnect(status.id) }) { Text("Disconnect") }
                    else TextButton(onClick = { if (status.id == "drive") onGoogleSetup() else onConnect(status.id) }, enabled = status.id !in connecting && !authState.loading) { Text(if (status.id in connecting || authState.loading) "Connecting..." else "Connect") }
                }
            }
        }
        if (statuses.any { it.id == "telegram" }) {
            Text("Telegram authentication", style = MaterialTheme.typography.titleMedium)
            when (telegramAuthState) {
                TelegramAuthState.WaitingForPhone -> TelegramCredentialField("Phone number", telegramInput, { telegramInput = it }, "Continue", onTelegramPhone)
                TelegramAuthState.WaitingForCode -> TelegramCredentialField("Telegram code", telegramInput, { telegramInput = it }, "Verify", onTelegramCode)
                TelegramAuthState.WaitingForPassword -> TelegramCredentialField("2FA password", telegramInput, { telegramInput = it }, "Verify", onTelegramPassword, true)
                TelegramAuthState.Ready -> Text("Telegram account connected", color = MaterialTheme.colorScheme.primary)
                is TelegramAuthState.Error -> Text(telegramAuthState.message, color = MaterialTheme.colorScheme.error)
                TelegramAuthState.Disconnected -> Text("Connect to browse authorized chats and channels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GoogleDriveSetupSheet(onDismiss: () -> Unit, onSignIn: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var showHelp by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Google Drive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Sign in with the Google account that contains your music. This app uses the Android OAuth client configured for com.audiophile and does not require a client secret.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign in with Google") }
            TextButton(onClick = { showHelp = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("How to create your own client ID & secret ↗")
            }
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Create Google credentials") },
            text = {
                Text("1. Open Google Cloud Console.\n2. Enable the Google Drive API.\n3. Configure the OAuth consent screen and add your account as a test user if the app is in testing.\n4. Create an Android OAuth client.\n5. Set the package name to com.audiophile.\n6. Add the SHA-1 fingerprint for the APK you install.\n7. Return here and tap Sign in with Google.")
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Done") } }
        )
    }
}

@Composable
private fun TelegramCredentialField(label: String, value: String, onValueChange: (String) -> Unit, action: String, onSubmit: (String) -> Unit, password: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(value, onValueChange, modifier = Modifier.weight(1f), label = { Text(label) }, singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        Button(onClick = { onSubmit(value) }, enabled = value.isNotBlank()) { Text(action) }
    }
}

@Composable
private fun SettingsScreen(padding: PaddingValues) { Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Text("Playback", style = MaterialTheme.typography.titleMedium); Text("Audiophile keeps playback in a dedicated Media3 service so music continues while the app is closed.", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Version 1.0", color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun EmptyLibrary(requestPermission: () -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Your library is waiting", style = MaterialTheme.typography.titleLarge); Text("Allow audio access to scan music stored on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = requestPermission) { Text("Allow access") } } } }

@Composable
private fun TrackRow(track: AudioTrack, playing: Boolean, vm: MainViewModel, queue: List<AudioTrack>, open: () -> Unit) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.play(track, queue); open() }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Artwork(track.artworkUri, Modifier.size(54.dp)); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(track.title.ifBlank { "Untitled track" }, maxLines = 1, fontWeight = FontWeight.Medium); Text("${track.artist}  ·  ${track.album}", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { vm.toggleFavorite(track.id) }) { Icon(if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (track.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (playing) Icons.Rounded.QueueMusic else Icons.Rounded.MoreHoriz, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun NowPlaying(track: AudioTrack, isPlaying: Boolean, positionMs: Long, lyrics: List<LyricLine>, shuffleEnabled: Boolean, repeatMode: Int, onBack: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit) { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }; Spacer(Modifier.height(20.dp)); Artwork(track.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))); Spacer(Modifier.height(24.dp)); Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(track.album, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)); androidx.compose.material3.Slider(value = positionMs.toFloat().coerceIn(0f, track.durationMs.coerceAtLeast(1L).toFloat()), onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..track.durationMs.coerceAtLeast(1L).toFloat()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(34.dp)) }; IconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", Modifier.size(42.dp)) }; IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(34.dp)) }; IconButton(onClick = onRepeat) { Icon(if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) } }; Spacer(Modifier.height(24.dp)); Text("Lyrics", style = MaterialTheme.typography.titleLarge); LyricsPanel(lyrics, positionMs) } }

@Composable
private fun ImmersiveNowPlaying(
    track: AudioTrack,
    isPlaying: Boolean,
    positionMs: Long,
    lyrics: List<LyricLine>,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUri, Modifier.size(92.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(track.album.ifBlank { "Audiophile" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                AudioQualityBadge(track)
            }
        }
        Spacer(Modifier.height(12.dp))
        LyricPreview(lyrics, positionMs, onOpenLyrics, Modifier.weight(1f).fillMaxWidth())
        Slider(
            value = positionMs.toFloat().coerceIn(0f, track.durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..track.durationMs.coerceAtLeast(1L).toFloat()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
            IconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", Modifier.size(42.dp)) }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
            IconButton(onClick = onRepeat) { Icon(if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FullLyricsScreen(
    track: AudioTrack,
    positionMs: Long,
    lyrics: List<LyricLine>,
    onBack: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onSaveLyrics: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(track.title, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onBack) { Text("Now Playing") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUri, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LyricsPanel(lyrics, positionMs, onRefreshLyrics, onSaveLyrics)
        }
    }
}

@Composable
private fun LyricPreview(lines: List<LyricLine>, positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val preview = currentLyricText(lines, positionMs)
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preview ?: "Lyrics unavailable",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = if (preview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (preview == null) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1
        )
    }
}

private fun currentLyricText(lines: List<LyricLine>, positionMs: Long): String? {
    if (lines.isEmpty()) return null
    val synced = lines.withIndex().filter { it.value is LyricLine.Synced }
    if (synced.isEmpty()) return lines.first().text
    return synced.lastOrNull { (it.value as LyricLine.Synced).atMs <= positionMs }?.value?.text
        ?: synced.first().value.text
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun AudioQualityBadge(track: AudioTrack) {
    val quality = audioQuality(track)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
            Text(quality.label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text(quality.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

private data class AudioQuality(val label: String, val detail: String)

private fun audioQuality(track: AudioTrack): AudioQuality {
    val codec = track.codec.lowercase()
    val mime = track.mimeType.lowercase()
    val lossless = codec in setOf("flac", "wav", "x-wav", "alac", "ape", "wavpack", "aiff", "x-aiff") ||
        mime in setOf("audio/flac", "audio/wav", "audio/x-wav", "audio/alac", "audio/aiff", "audio/x-aiff")
    val rate = track.sampleRate
    val depth = track.bitDepth
    if (lossless) {
        val detail = if (depth > 0 && rate > 0) "$depth-bit · ${rate / 1000f} kHz" else if (rate > 0) "${rate / 1000f} kHz" else "Lossless audio"
        val label = when {
            depth >= 24 && rate >= 48_000 -> "HI-RES"
            depth == 16 && rate == 44_100 -> "CD AUDIO"
            else -> "LOSSLESS"
        }
        return AudioQuality(label, detail)
    }
    val label = when {
        codec.contains("mpeg") || codec == "mp3" || mime.contains("mpeg") -> "MP3"
        codec.contains("aac") || codec == "mp4" || mime.contains("aac") || mime.contains("mp4") -> "AAC"
        codec.contains("opus") || mime.contains("opus") -> "OPUS"
        codec.contains("ogg") || mime.contains("ogg") -> "OGG"
        else -> codec.ifBlank { mime.substringAfter('/', "AUDIO") }.uppercase()
    }
    val detail = if (track.bitrate > 0) "${track.bitrate / 1000} kbps" else "Compressed audio"
    return AudioQuality(label, detail)
}

@Composable
private fun LyricsPanel(lines: List<LyricLine>, positionMs: Long, onRefresh: () -> Unit = {}, onSave: (String) -> Unit = {}) {
    var editorOpen by remember { mutableStateOf(false) }
    var editorText by remember { mutableStateOf("") }
    var editorTitle by remember { mutableStateOf("Add Lyrics Manually") }

    if (lines.isEmpty()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lyrics unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editorTitle = "Search Lyrics"; editorOpen = true }) { Text("Search Lyrics") }
                TextButton(onClick = { editorTitle = "Add Lyrics Manually"; editorOpen = true }) { Text("Add Lyrics Manually") }
            }
            TextButton(onClick = onRefresh) { Text("Refresh Lyrics") }
        }
        if (editorOpen) LyricsEditorDialog(editorTitle, editorText, { editorText = it }, { onSave(editorText); editorOpen = false }, { editorOpen = false })
        return
    }

    val listState = rememberLazyListState()
    var manualScroll by remember(lines) { mutableStateOf(false) }
    var automaticScroll by remember { mutableStateOf(false) }
    val hasSyncedLines = lines.any { it is LyricLine.Synced }
    val active = if (hasSyncedLines) {
        lines.indexOfLast { it is LyricLine.Synced && it.atMs <= positionMs }
    } else {
        -1
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .drop(1)
            .collect { (scrolling, _) ->
                if (scrolling && !automaticScroll) manualScroll = true
            }
    }

    LaunchedEffect(active, manualScroll) {
        if (!manualScroll && active >= 0) {
            automaticScroll = true
            listState.animateScrollToItem(active, scrollOffset = -88)
            automaticScroll = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRefresh) { Text("Refresh Lyrics") }
            TextButton(onClick = { editorTitle = "Save Lyrics"; editorOpen = true }) { Text("Save Lyrics") }
            TextButton(onClick = { editorTitle = "Translate Lyrics"; editorOpen = true }) { Text("Translate Lyrics") }
        }
        if (manualScroll && hasSyncedLines) {
            TextButton(
                onClick = { manualScroll = false },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Sync to current lyric") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(280.dp),
            contentPadding = PaddingValues(vertical = 112.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val isActive = index == active
                Text(
                    text = line.text.ifBlank { " " },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    style = if (isActive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
    if (editorOpen) LyricsEditorDialog(editorTitle, editorText, { editorText = it }, { onSave(editorText); editorOpen = false }, { editorOpen = false })
}

@Composable
private fun LyricsEditorDialog(title: String, value: String, onValueChange: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), minLines = 6, placeholder = { Text("Paste lyrics or LRC timestamps") }) },
        confirmButton = { TextButton(onClick = onSave, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Artwork(uri: String?, modifier: Modifier = Modifier) { Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF25282D)), contentAlignment = Alignment.Center) { AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop); if (uri == null) Icon(Icons.Rounded.LibraryMusic, null, tint = Color(0xFFC6A76A), modifier = Modifier.size(28.dp)) } }

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
@Composable private fun StatCard(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) { Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(14.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } } }

@Composable private fun AudiophileTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Color(0xFFC6A76A), background = Color(0xFF0D0E10), surface = Color(0xFF17191D), surfaceVariant = Color(0xFF202329), onSurface = Color(0xFFF1F0ED), onSurfaceVariant = Color(0xFF9B9B9B)), content = content) }
