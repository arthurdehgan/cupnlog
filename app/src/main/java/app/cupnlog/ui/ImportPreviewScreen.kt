package app.cupnlog.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cupnlog.data.CoffeeEntry
import app.cupnlog.data.ShareCodec
import app.cupnlog.ui.components.sessionSummary

/**
 * Phase 3 confirm preview: shows exactly what an incoming link / scan / paste
 * carries — host name when known, what would be saved — and asks. Nothing is
 * auto-saved, auto-started or auto-revealed, ever. Blind previews render
 * "Cup 1…N" only — the payload carries identities, but showing them here
 * would spoil the joiner's own blind tasting before it starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewScreen(vm: CuppingViewModel) {
    val incoming by vm.incoming.collectAsState()
    val history by vm.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Incoming set-up") },
                navigationIcon = {
                    IconButton(onClick = { vm.dismissImport() }) {
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            when (val share = incoming) {
                null -> {
                    // Transient (navigating away); nothing to confirm.
                }

                is CuppingViewModel.IncomingShare.Error -> {
                    Text(
                        "Can't open this code",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        share.error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { vm.dismissImport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("OK") }
                }

                is CuppingViewModel.IncomingShare.Setup -> {
                    val setup = share.setup
                    if (setup.hostName.isNotBlank()) {
                        Text(
                            "From ${setup.hostName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        setup.sessionName.ifBlank {
                            "Cupping · ${setup.cupCount} cup" + if (setup.cupCount == 1) "" else "s"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        sessionSummary(setup.cupCount, setup.cupSizeMl),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            if (setup.blind) {
                                // The payload carries identities, but the
                                // preview deliberately shows positions only:
                                // reading the names here would spoil the
                                // joiner's own blind tasting before it starts.
                                Text(
                                    "Blind set-up · ${setup.cupCount} cups (names stay hidden until after the cupping)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                repeat(setup.cupCount) { index ->
                                    Text(
                                        "Cup ${index + 1}",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (index != setup.cupCount - 1) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            } else {
                                setup.coffees.forEachIndexed { index, coffee ->
                                    CupPreviewLine(index = index, coffee = coffee)
                                    if (index != setup.coffees.size - 1) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { vm.confirmImport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to saved setups") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.dismissImport() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel") }
                }

                is CuppingViewModel.IncomingShare.Ratings -> {
                    val ratings = share.ratings
                    val session = history.firstOrNull {
                        it.id == ratings.hostSessionId || it.sessionRef == ratings.hostSessionId
                    }
                    if (session == null) {
                        Text(
                            "Can't add these ratings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            ShareCodec.ShareError.DIFFERENT_EVENT.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { vm.dismissImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("OK") }
                    } else {
                        if (ratings.raterName.isNotBlank()) {
                            Text(
                                "From ${ratings.raterName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            session.plan.sessionName.ifBlank {
                                "Cupping · ${session.plan.cupCount} cup" +
                                    if (session.plan.cupCount == 1) "" else "s"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val cups = ratings.ratings.size
                        Text(
                            "$cups cup" + if (cups == 1) "" else "s" + " rated" +
                                if (ratings.notesShared) " · notes included" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "These scores replace any earlier set from ${ratings.raterName.ifBlank { "this taster" }}.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { vm.confirmImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add to this session") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.dismissImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                }

                is CuppingViewModel.IncomingShare.GroupResults -> {
                    val results = share.results
                    val session = history.firstOrNull {
                        it.id == results.hostSessionId || it.sessionRef == results.hostSessionId
                    }
                    if (session == null) {
                        Text(
                            "Can't add these results",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            ShareCodec.ShareError.DIFFERENT_EVENT.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { vm.dismissImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("OK") }
                    } else {
                        val raterCount = results.ratings.map { it.raterId }.distinct().size
                        Text(
                            "Merged results",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$raterCount rater" + if (raterCount == 1) "" else "s" +
                                " · ${results.ratings.size} score entries" +
                                if (results.revealed) " · identities included" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (results.revealed) {
                                    "Coffee identities will be revealed in your copy."
                                } else {
                                    "Your copy stays anonymous until the host includes a reveal."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { vm.confirmImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add to my session") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.dismissImport() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel") }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** One preview line: "Cup 1 — Ethiopia" (names as carried by the payload). */
@Composable
private fun CupPreviewLine(index: Int, coffee: CoffeeEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Cup ${index + 1}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        Text(
            if (coffee.name.isBlank()) "\u2014" else coffee.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (coffee.name.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
