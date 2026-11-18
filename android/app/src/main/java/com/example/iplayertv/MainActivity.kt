package com.example.iplayertv

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val CHANNELS = listOf(
    "All" to "", "BBC One" to "BBC One", "BBC Two" to "BBC Two", "BBC Three" to "BBC Three",
    "BBC Four" to "BBC Four", "CBBC" to "CBBC", "CBeebies" to "CBeebies", "News" to "BBC News",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(prefs) } }
    }
}

@Composable
fun App(prefs: SharedPreferences) {
    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var settings by remember { mutableStateOf(server.isBlank()) }
    var playing by remember { mutableStateOf<Programme?>(null) }
    val api = remember(server) { Api(server) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val p = playing
        if (p != null) PlayerScreen(api, p, prefs) { playing = null }
        else HomeScreen(api, server.isNotBlank(), { playing = it }, { settings = true })
    }
    if (settings) {
        ServerDialog(server, { settings = false }) { v ->
            server = v.trim()
            prefs.edit().putString("server", server).apply()
            settings = false
        }
    }
}

@Composable
fun HomeScreen(api: Api, configured: Boolean, onPlay: (Programme) -> Unit, onSettings: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, query, channel) {
        if (!configured) return@LaunchedEffect
        loading = true
        error = null
        try {
            results = api.search(query, channel)
        } catch (e: Exception) {
            error = e.message ?: "Request failed"
            results = emptyList()
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("Search BBC iPlayer") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { query = text.trim() }),
            )
            Button(onClick = { query = text.trim() }) { Text("Search") }
            OutlinedButton(onClick = onSettings) { Text("Server") }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CHANNELS) { ch ->
                if (channel == ch.second) Button(onClick = { channel = ch.second }) { Text(ch.first) }
                else OutlinedButton(onClick = { channel = ch.second }) { Text(ch.first) }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp),
        ) {
            items(results) { ProgrammeCard(it, onPlay) }
        }
    }
}

@Composable
fun ProgrammeCard(p: Programme, onPlay: (Programme) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent), shape)
            .clip(shape)
            .clickable { onPlay(p) }
            .padding(4.dp)
    ) {
        AsyncImage(
            model = p.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
        )
        Text(p.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        if (p.episode.isNotBlank()) {
            Text(p.episode, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(p.channel, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(current.ifBlank { "http://192.168.1.10:8000" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server address") },
        text = { OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onSave(v) }) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlayerScreen(api: Api, p: Programme, prefs: SharedPreferences, onBack: () -> Unit) {
    var message by remember { mutableStateOf("Preparing…") }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(p.pid) {
        try {
            api.prepare(p.pid)
            while (true) {
                val (s, e) = api.status(p.pid)
                if (s == "ready") { ready = true; return@LaunchedEffect }
                if (s == "error") { error = e ?: "Download failed"; return@LaunchedEffect }
                message = if (s == "downloading") "Fetching from BBC… this can take a minute or two" else "Queued…"
                delay(2000)
            }
        } catch (e: Exception) {
            error = e.message ?: "Request failed"
        }
    }

    if (ready) {
        val ctx = LocalContext.current
        val player = remember { ExoPlayer.Builder(ctx).build() }
        DisposableEffect(Unit) {
            player.setMediaItem(MediaItem.fromUri(api.mediaUrl(p.pid)))
            player.prepare()
            val saved = prefs.getLong("pos_" + p.pid, 0L)
            if (saved > 0) player.seekTo(saved)
            player.playWhenReady = true
            onDispose {
                val pos = player.currentPosition
                val dur = player.duration
                val key = "pos_" + p.pid
                if (pos > 10_000 && dur > 0 && pos < dur - 30_000) prefs.edit().putLong(key, pos).apply()
                else prefs.edit().remove(key).apply()
                player.release()
            }
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                    isFocusable = true
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.title, style = MaterialTheme.typography.headlineSmall)
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onBack) { Text("Back") }
                } else {
                    CircularProgressIndicator()
                    Text(message)
                }
            }
        }
    }
}
