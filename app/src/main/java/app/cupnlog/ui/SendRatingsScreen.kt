package app.cupnlog.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Phase 4 guest side: send this device's own ratings to the host over the
 * wireless exchange, then receive the host's merged results. A guest's ratings
 * stay on its own device until it taps send, at the end of the cupping. The QR
 * fallback ("share my ratings code") is always available below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendRatingsScreen(vm: CuppingViewModel, sessionId: String, returnTo: Screen) {
    val context = LocalContext.current
    val history by vm.history.collectAsState()
    val session = history.firstOrNull { it.id == sessionId }

    val state = vm.guestExchangeState
    val exchange = remember { NearbyExchange(context) }
    val status by state.status.collectAsState()
    val sent by state.sent.collectAsState()
    val received by state.received.collectAsState()

    val listener = remember {
        object : NearbyExchange.Listener {
            override fun onEndpointConnected(endpointId: String, name: String) {
                // Send this device's ratings as soon as the host is connected.
                if ((vm.screen.value as? Screen.SendRatings)?.sessionId != sessionId) return
                val current = vm.history.value.firstOrNull { it.id == sessionId }
                if (current == null) {
                    state.status.value = "This session no longer exists."
                    return
                }
                val raterId = vm.myId.value.ifBlank {
                    current.ratings.firstOrNull()?.raterId.orEmpty()
                }
                if (raterId.isBlank()) {
                    state.status.value = "Rate at least one cup first."
                    return
                }
                val payload = ShareCodec.encodeRatings(
                    session = current,
                    raterId = raterId,
                    raterName = vm.myName.value,
                    notesShared = vm.shareNotes.value,
                )
                exchange.send(endpointId, payload.code.toByteArray(Charsets.UTF_8))
                state.sent.value = true
                state.status.value = "Ratings sent. Waiting for the host's results…"
            }

            override fun onPayload(endpointId: String, bytes: ByteArray) {
                when (val decoded = ShareCodec.decode(String(bytes, Charsets.UTF_8))) {
                    is ShareCodec.DecodeResult.GroupResults -> {
                        val activeSessionId =
                            (vm.screen.value as? Screen.SendRatings)?.sessionId
                        val selected = vm.history.value.firstOrNull { it.id == sessionId }
                        if (activeSessionId != sessionId ||
                            selected?.sharedSessionId != decoded.results.hostSessionId
                        ) {
                            state.status.value = ShareCodec.ShareError.DIFFERENT_EVENT.message
                            return
                        }
                        state.received.value = decoded.results
                        state.status.value = "Results received."
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
            exchange.startDiscovery(listener)
            state.status.value = "Looking for the host…"
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
            exchange.startDiscovery(listener)
            state.status.value = "Looking for the host…"
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
                title = { Text("Send my ratings") },
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
                "Send once, at the end of the cupping. Your ratings stay private until you " +
                    "tap send, and you'll get the group comparison back from the host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (permissionNote) {
                Text(
                    "Some wireless permissions weren't granted. Share your ratings QR below " +
                        "instead, and ask the host for the merged-results QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            received?.let { results ->
                val raterCount = results.ratings.map { it.raterId }.distinct().size
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Results from the host",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$raterCount rater" + if (raterCount == 1) "" else "s" +
                                " · " + results.ratings.size + " score entries" +
                                if (results.revealed) " · identities included" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                vm.confirmNearbyGroupResults(
                                    selectedSessionId = sessionId,
                                    results = results,
                                    onDone = { vm.handleBack() },
                                    onRejected = {
                                        state.status.value =
                                            ShareCodec.ShareError.DIFFERENT_EVENT.message
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add results to my session") }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { state.received.update { null } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Not now") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (sent && received == null) {
                Text(
                    "Waiting for the host to broadcast the merged results. Keep this screen open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // QR fallback: this device's ratings as a code the host can scan.
            // The QR itself lives on its own screen, opened on demand.
            val myId by vm.myId.collectAsState()
            val myName by vm.myName.collectAsState()
            val shareNotes by vm.shareNotes.collectAsState()
            val myRatingsEncode = remember(session, myId, myName, shareNotes) {
                val raterId = myId.ifBlank { session.ratings.firstOrNull()?.raterId.orEmpty() }
                if (raterId.isBlank()) null
                else ShareCodec.encodeRatings(session, raterId, myName, shareNotes)
            }
            if (myRatingsEncode != null) {
                OutlinedButton(
                    onClick = {
                        vm.openQrShare(
                            encode = myRatingsEncode,
                            title = "My ratings",
                            subtitle = "The host scans this to collect your ratings " +
                                "without wireless.",
                            returnTo = Screen.SendRatings(sessionId, returnTo),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show my ratings QR") }
                Spacer(modifier = Modifier.height(16.dp))
            }

            NotesToggle(checked = shareNotes, onCheckedChange = { vm.setShareNotes(it) })
            Spacer(modifier = Modifier.height(8.dp))

            // QR fallback for the broadcast: scan the host's merged-results
            // code and confirm it through the shared preview.
            OutlinedButton(
                onClick = { vm.openScanner(Screen.SendRatings(sessionId, returnTo)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan the host's merged-results QR") }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { vm.handleBack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

internal class GuestExchangeState {
    val status = MutableStateFlow("Starting…")
    val sent = MutableStateFlow(false)
    val received = MutableStateFlow<ShareCodec.SharedGroupResults?>(null)

    fun reset() {
        status.value = "Starting…"
        sent.value = false
        received.value = null
    }
}
