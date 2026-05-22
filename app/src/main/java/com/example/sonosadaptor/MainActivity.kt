package com.example.sonosadaptor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sonosadaptor.ui.theme.SonosAdaptorTheme

class MainActivity : ComponentActivity() {

    private val isRunning = mutableStateOf(false)
    private val isDiscovering = mutableStateOf(false)
    private val discoveredSpeakers = mutableStateOf<List<AudioOutput.SonosSpeaker>>(emptyList())
    private val selectedOutput = mutableStateOf<AudioOutput>(AudioOutput.PhoneSpeaker)
    private val showDeviceSheet = mutableStateOf(false)
    private val hasDiscoveredOnce = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startPassthroughService()
            isRunning.value = true
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonosAdaptorTheme {
                val running by isRunning
                val discovering by isDiscovering
                val speakers by discoveredSpeakers
                val selected by selectedOutput
                val sheetOpen by showDeviceSheet

                val allOutputs: List<AudioOutput> = listOf(AudioOutput.PhoneSpeaker) + speakers
                val outputStatus by AudioPassthroughService.outputStatus

                val selectedLabel = when (val s = selected) {
                    is AudioOutput.PhoneSpeaker -> "Phone Speaker"
                    is AudioOutput.SonosSpeaker -> s.name
                }

                val statusLabel = when (outputStatus) {
                    AudioPassthroughService.OutputStatus.CONNECTING -> "Connecting…"
                    AudioPassthroughService.OutputStatus.CONNECTED -> "Connected"
                    AudioPassthroughService.OutputStatus.IDLE -> "Inactive"
                }
                val statusColor = when (outputStatus) {
                    AudioPassthroughService.OutputStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    AudioPassthroughService.OutputStatus.CONNECTING -> Color(0xFFFF9800)
                    AudioPassthroughService.OutputStatus.CONNECTED -> Color(0xFF4CAF50)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // ── Start / Stop ──────────────────────────────────
                            Button(
                                onClick = {
                                    if (running) {
                                        stopPassthroughService()
                                        isRunning.value = false
                                    } else {
                                        if (hasAudioPermission()) {
                                            startPassthroughService()
                                            isRunning.value = true
                                        } else {
                                            requestPermissionLauncher.launch(
                                                Manifest.permission.RECORD_AUDIO
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                            ) {
                                Text(
                                    text = if (running) "Stop" else "Start",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            // ── Output selector (Spotify-style) ───────────────
                            OutlinedButton(
                                onClick = {
                                    showDeviceSheet.value = true
                                    if (!hasDiscoveredOnce.value) discoverSpeakers()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speaker,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedLabel,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor
                                    )
                                }
                                if (outputStatus == AudioPassthroughService.OutputStatus.CONNECTING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // ── Test tone ─────────────────────────────────────
                            OutlinedButton(
                                onClick = {
                                    sendToService(
                                        Intent(this@MainActivity, AudioPassthroughService::class.java).apply {
                                            action = AudioPassthroughService.ACTION_TEST_TONE
                                        }
                                    )
                                },
                                enabled = running && outputStatus != AudioPassthroughService.OutputStatus.CONNECTING,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Test Output", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    // ── Device sheet ──────────────────────────────────────────
                    if (sheetOpen) {
                        ModalBottomSheet(
                            onDismissRequest = { showDeviceSheet.value = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ) {
                            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        start = 24.dp, end = 16.dp,
                                        top = 8.dp, bottom = 16.dp
                                    )
                                ) {
                                    Text(
                                        text = "Available Devices",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (discovering) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        OutlinedButton(
                                            onClick = { discoverSpeakers() },
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            Text("Refresh", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                HorizontalDivider()

                                LazyColumn {
                                    items(allOutputs) { output ->
                                        val label = when (output) {
                                            is AudioOutput.PhoneSpeaker -> "Phone Speaker"
                                            is AudioOutput.SonosSpeaker -> output.name
                                        }
                                        val isActive = when {
                                            output is AudioOutput.PhoneSpeaker && selected is AudioOutput.PhoneSpeaker -> true
                                            output is AudioOutput.SonosSpeaker && selected is AudioOutput.SonosSpeaker ->
                                                (selected as AudioOutput.SonosSpeaker).host == output.host
                                            else -> false
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectOutput(output)
                                                    showDeviceSheet.value = false
                                                }
                                                .padding(horizontal = 24.dp, vertical = 14.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = if (isActive)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isActive) {
                                                    Text(
                                                        text = statusLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = statusColor
                                                    )
                                                }
                                            }
                                            if (isActive) {
                                                if (outputStatus == AudioPassthroughService.OutputStatus.CONNECTING) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Connected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun discoverSpeakers() {
        if (isDiscovering.value) return
        isDiscovering.value = true
        Thread({
            val found = try {
                SsdpDiscovery.discover(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Discovery failed", t)
                emptyList()
            }
            // Always clear the spinner, even if something unexpected went wrong.
            discoveredSpeakers.value = found
            isDiscovering.value = false
            hasDiscoveredOnce.value = true

            val sel = selectedOutput.value
            if (sel is AudioOutput.SonosSpeaker && found.none { it.host == sel.host }) {
                selectOutput(AudioOutput.PhoneSpeaker)
            }
        }, "ssdp-discovery").start()
    }

    // ── Output selection ──────────────────────────────────────────────────────

    private fun selectOutput(output: AudioOutput) {
        selectedOutput.value = output

        when (output) {
            is AudioOutput.PhoneSpeaker -> {
                // Only tell the service to switch if it's already running audio.
                if (isRunning.value) {
                    sendToService(
                        Intent(this, AudioPassthroughService::class.java).apply {
                            action = AudioPassthroughService.ACTION_SET_OUTPUT_PHONE
                        }
                    )
                }
            }
            is AudioOutput.SonosSpeaker -> {
                // Always initiate the Sonos connection; this starts the service
                // (non-foreground) if it isn't running yet so the SOAP handshake
                // and HTTP stream can begin immediately, independent of whether
                // the audio capture passthrough has been started.
                sendToService(
                    Intent(this, AudioPassthroughService::class.java).apply {
                        action = AudioPassthroughService.ACTION_SET_OUTPUT_SONOS
                        putExtra(AudioPassthroughService.EXTRA_SONOS_NAME, output.name)
                        putExtra(AudioPassthroughService.EXTRA_SONOS_HOST, output.host)
                        putExtra(AudioPassthroughService.EXTRA_SONOS_PORT, output.port)
                        putExtra(AudioPassthroughService.EXTRA_SONOS_CONTROL_URL, output.avTransportControlUrl)
                    }
                )
            }
        }
    }

    // ── Service helpers ───────────────────────────────────────────────────────

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startPassthroughService() {
        sendToService(
            Intent(this, AudioPassthroughService::class.java).apply {
                action = AudioPassthroughService.ACTION_START
            },
            foreground = true
        )
    }

    private fun stopPassthroughService() {
        sendToService(
            Intent(this, AudioPassthroughService::class.java).apply {
                action = AudioPassthroughService.ACTION_STOP
            }
        )
    }

    private fun sendToService(intent: Intent, foreground: Boolean = false) {
        if (foreground) startForegroundService(intent) else startService(intent)
    }
}
