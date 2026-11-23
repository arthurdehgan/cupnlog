package app.cupnlog.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.cupnlog.data.NearbyExchange
import app.cupnlog.data.ShareCodec
import app.cupnlog.data.nearbyRuntimePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 4 host side: collect each taster's ratings over the wireless exchange
 * (Nearby). The host advertises, tasters connect and send their ratings once at
 * the end of the cupping, and the host confirms each one into the session.
 * The QR scanner / paste is always available as the fallback, and "Show merged
 * results" broadcasts the comparison back to any still-connected tasters (plus
 * a QR for those that dropped off).
 *
 * Confirm rule (same invariant as the ImportPreview path, different surface):
 * a wireless payload is never applied on arrival; it lands in a card and is
 * merged only when the host taps Add. Re-submission replaces the pending set
 * for that rater (replace-per-`raterId`, matching the merge itself).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectRatingsScreen(vm: CuppingViewModel, sessionId: String, returnTo: Screen) {
    val context = LocalContext.current
    val history by vm.history.collectAsState()
    val myName by vm.myName.collectAsState()
    val myId by vm.myId.collectAsState()
    val shareNotes by vm.shareNotes.collectAsState()
    val session = history.firstOrNull { it.id == sessionId }

    val state = vm.hostExchangeState
    val exchange = remember { NearbyExchange(context) }
    val status by state.status.collectAsState()
    val pending by state.pending.collectAsState()
    val results by state.resultsEncode.collectAsState()

    val listener = remember {
        object : NearbyExchange.Listener {
            override fun onEndpointConnected(endpointId: String, name: String) {
                state.connected.update { it + 1 }
                state.status.value = "Tasters connected: ${state.connected.value}"
            }

            override fun onEndpointDisconnected(endpointId: String) {
                state.connected.update { (it - 1).coerceAtLeast(0) }
            }

            override fun onPayload(endpointId: String, bytes: ByteArray) {
                when (val decoded = ShareCodec.decode(String(bytes, Charsets.UTF_8))) {
                    is ShareCodec.DecodeResult.Ratings -> {
                        val incoming = decoded.ratings
                        val activeSessionId =
                            (vm.screen.value as? Screen.CollectRatings)?.sessionId
                        val selected = vm.history.value.firstOrNull { it.id == sessionId }
                        // A nearby host accepts only ratings for the session
                        // this screen is currently collecting.
                        if (activeSessionId != sessionId ||
                            selected?.sharedSessionId != incoming.hostSessionId
                        ) {
                            state.status.value = ShareCodec.ShareError.DIFFERENT_EVENT.message
                            return
                        }
                        val replaces = state.pending.value.any { it.raterId == incoming.raterId }
                        // Replace-first: a re-submission is the taster's latest
                        // set, so it wins over the earlier one.
                        state.pending.update { list ->
                            list.filterNot { it.raterId == incoming.raterId } + incoming
                        }
                        if (replaces) {
                            state.status.value =
                                "${incoming.raterName.ifBlank { "A taster" }} sent updated ratings."
                        }
                    }
                    else -> Unit
                }
            }

            override fun onFailure(message: String) {
                state.status.value = message
            }
        }
    }

    val permissions = remember { nearbyRuntimePermissions() }
    var permissionNote by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (permissions.all { result[it] == true }) {
            exchange.startHosting(myName, listener)
            state.status.value = "Waiting for tasters…"
        } else {
            permissionNote = true
            state.status.value = "Wireless sharing needs these permissions."
        }
    }

    LaunchedEffect(Unit) {
        vm.ensureMyId()
        val granted = permissions.isEmpty() ||
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        if (granted) {
            exchange.startHosting(myName, listener)
            state.status.value = "Waiting for tasters…"
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    DisposableEffect(Unit) { onDispose { exchange.stop() } }

    if (session == null) {
        LaunchedEffect(Unit) { vm.handleBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collect ratings") },
                navigationIcon = {
                    IconButton(onClick = { vm.handleBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Keep this screen open. Each taster sends their ratings once, at the " +
                    "end of the cupping: nothing is shared until then.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (pending.isNotEmpty()) {
                Text(
                    "Ratings received",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                pending.forEach { ratings ->
                    PendingRatingsCard(
                        ratings = ratings,
                        onAdd = {
                            vm.confirmNearbyRatings(
                                selectedSessionId = sessionId,
                                ratings = ratings,
                                onDone = {
                                    state.pending.update { list ->
                                        list.filterNot { it.raterId == ratings.raterId }
                                    }
                                },
                                onRejected = {
                                    state.status.value = ShareCodec.ShareError.DIFFERENT_EVENT.message
                                }
                            )
                        },
                        onSkip = {
                            state.pending.update { list ->
                                list.filterNot { it.raterId == ratings.raterId }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (permissionNote) {
                Text(
                    "Some wireless permissions weren't granted. You can still collect by " +
                        "scanning each taster's QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            NotesToggle(checked = shareNotes, onCheckedChange = { vm.setShareNotes(it) })
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { vm.openScanner(Screen.CollectRatings(sessionId, returnTo)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan a taster's QR instead") }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val current = vm.history.value.firstOrNull { it.id == sessionId }
                    if (current != null) {
                        // The host's own notes follow the same per-person toggle:
                        // off strips them from the broadcast (others keep theirs).
                        val shared = if (shareNotes) {
                            current
                        } else {
                            current.copy(
                                ratings = current.ratings.map {
                                    if (it.raterId == myId) it.copy(notes = "") else it
                                },
                            )
                        }
                        val full = ShareCodec.encodeGroupResults(shared, includeNotes = true)
                        exchange.broadcast(full.code.toByteArray(Charsets.UTF_8))
                        state.resultsEncode.value =
                            if (full.fitsQr) full else ShareCodec.encodeGroupResults(shared, includeNotes = false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show merged results") }

            results?.let { encode ->
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        vm.openQrShare(
                            encode = encode,
                            title = "Merged results",
                            subtitle = "Tasters still connected get this wirelessly; " +
                                "others can scan this QR.",
                            returnTo = Screen.CollectRatings(sessionId, returnTo),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show merged-results QR") }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = { vm.handleBack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

internal class HostExchangeState {
    val status = MutableStateFlow("Starting…")
    val connected = MutableStateFlow(0)
    val pending = MutableStateFlow<List<ShareCodec.SharedRatings>>(emptyList())
    val resultsEncode = MutableStateFlow<ShareCodec.ShareEncode?>(null)

    fun reset() {
        status.value = "Starting…"
        connected.value = 0
        pending.value = emptyList()
        resultsEncode.value = null
    }
}

/** The per-person notes toggle, shared by the collect and send screens. */
@Composable
internal fun NotesToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Include my notes when sharing",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Others see your tasting notes with your scores. You always see your own " +
                    "notes regardless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PendingRatingsCard(
    ratings: ShareCodec.SharedRatings,
    onAdd: () -> Unit,
    onSkip: () -> Unit,
) {
    val cups = ratings.ratings.size
    val cupLine = buildString {
        append(cups).append(" cup").append(if (cups == 1) "" else "s").append(" rated")
        if (ratings.notesShared) append(" · notes included")
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                ratings.raterName.ifBlank { "A taster" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                cupLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Add") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip") }
            }
        }
    }
}
