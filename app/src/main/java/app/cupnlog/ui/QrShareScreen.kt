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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 4 QR fallback surface. The collect/send screens show only a button;
 * the actual QR (plus share-as-text / copy-link / bare-code options) lives
 * here so the live exchange screens stay uncluttered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShareScreen(vm: CuppingViewModel, screen: Screen.QrShare) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share code") },
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
            ShareQrBlock(
                encode = screen.encode,
                title = screen.title,
                subtitle = screen.subtitle,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { vm.handleBack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}