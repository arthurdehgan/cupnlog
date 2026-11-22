package app.cupnlog.ui

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cupnlog.data.ShareCodec

/**
 * Phase 3 share: the saved setup as a QR encoding the https App Link (the
 * whole payload rides in the link — no backend). Falls back to share-as-text /
 * copy-link / bare code when the link exceeds the ~2 KB QR guard. The screen
 * never shows cup identity for blind setups: the payload itself carries none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSetupScreen(vm: CuppingViewModel, sessionId: String) {
    val history by vm.history.collectAsState()
    val myName by vm.myName.collectAsState()
    val session = history.firstOrNull { it.id == sessionId }

    // Keep the screen on while the QR shows (the shell only runs for a live
    // session; a shared setup is shown from Home, so the screen holds it here).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share set-up") },
                navigationIcon = {
                    IconButton(onClick = { vm.handleBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (session == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Text("This set-up no longer exists.")
            }
            return@Scaffold
        }

        val encode = remember(sessionId, session.plan, myName) {
            ShareCodec.encodeSetup(session, myName)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            // Summary line above the QR — names/colors never leak here: the
            // blind payload carries none.
            Text(
                if (session.blind) {
                    "Blind set-up · ${session.plan.cupCount} cups (names stay hidden until after the cupping)"
                } else {
                    "Full set-up · ${session.plan.cupCount} cups"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (session.plan.sessionName.isNotBlank()) {
                Text(
                    session.plan.sessionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Same block as the phase-4 ratings / merged-results screens.
            ShareQrBlock(
                encode = encode,
                title = "Set-up code",
                subtitle = "The whole set-up rides in the link — no server involved.",
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { vm.handleBack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

