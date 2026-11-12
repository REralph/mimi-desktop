package com.example.a2desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkStorage

/**
 * Phone link — connect this PC to the Mimi app on the phone. The phone is the source of truth and keeps its data; this app shows a copy
 * and sends its changes (they wait here, and are shown, while the phone is out of reach). Pairing is one step: the page shows a QR code
 * and the phone scans it; the app then finds the phone by itself, and again whenever its address changes. Typing the address and key
 * is still possible, folded away.
 */
@Composable
fun PhonePage(store: DesktopStore, link: LinkController) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader("Phone link", "Show your phone's data on this PC", modifier = Modifier.devRef("6.1", "Page header"))
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ConnectionCard(store, link, Modifier.weight(1f).fillMaxHeight())
            SharedDataCard(store, link, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ConnectionCard(store: DesktopStore, link: LinkController, modifier: Modifier) {
    val paired = link.pairing
    var address by remember { mutableStateOf(paired?.address ?: "") }
    var key by remember { mutableStateOf("") }
    var formProblem by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var pairAgain by remember { mutableStateOf(false) }
    var byHand by remember { mutableStateOf(false) }

    // The pairing code is on show while there is no phone, or the user asked to pair again; leaving the page takes it down.
    val showCode = paired == null || pairAgain
    LaunchedEffect(paired) { if (paired != null) pairAgain = false }
    DisposableEffect(showCode) {
        if (showCode) link.startQrPairing()
        onDispose { link.stopQrPairing() }
    }

    ScrollColumn(modifier = modifier) {
        com.example.a2desktop.theme.GlassCard(modifier = Modifier.fillMaxWidth().devRef("6.2", "Connection card"), contentPadding = 24.dp) {
            val dot = when {
                link.syncing -> Color(0xFFF2C94C)
                link.reachable -> Color(0xFF6FCF97)
                paired != null -> Color(0xFFF2C94C)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(dot, size = 14.dp)
                Text(
                    "  " + when {
                        link.syncing -> "Syncing…"
                        link.reachable -> "Connected"
                        paired != null -> "Phone not reachable"
                        else -> "Not connected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val problem = formProblem ?: link.error
            if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            if (link.vpnHint) {
                Text("A VPN is running on this PC — it may be blocking your home network.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }

            if (link.pendingCount > 0) {
                Column(modifier = Modifier.padding(top = 14.dp).devRef("6.2.1", "Waiting for the phone")) {
                    Text(
                        if (link.pendingCount == 1) "1 change waiting for the phone" else "${link.pendingCount} changes waiting for the phone",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    link.pendingLabels.take(5).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (link.pendingCount > 5) Text("…and ${link.pendingCount - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (link.notes.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 14.dp).devRef("6.2.2", "Changes the phone kept its own way")) {
                    link.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = link::dismissNotes) { Text("Dismiss") }
                }
            }

            if (showCode) {
                Text("Pair with your phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                link.qrSession?.let { session ->
                    Box(modifier = Modifier.padding(top = 12.dp).devRef("6.2.3", "Pairing code")) { QrCodeImage(session.payload, size = 200.dp) }
                }
                Text(
                    "On the phone: ☰ → Desktop link → Scan PC code.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { byHand = !byHand }) { Text(if (byHand) "Hide address and key" else "Enter address and key instead") }
                    if (paired != null) TextButton(onClick = { pairAgain = false }) { Text("Cancel") }
                }
                if (byHand) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; formProblem = null },
                        label = { Text("Phone address, e.g. 192.168.0.16:47321") },
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.uppercase(); formProblem = null },
                        label = { Text("Pairing key, e.g. ABCD-EFGH-JKLM-NPQR-STUV") },
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { formProblem = link.connect(address, key) },
                        enabled = !link.syncing && address.isNotBlank() && key.isNotBlank(),
                    ) { Text("Connect and sync") }
                }
            } else if (paired != null) {
                Text("Paired with ${store.deviceName ?: paired.address}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                link.lastSyncedMillis?.let {
                    Text(
                        "Last data from the phone: ${clockText(it, store.today)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (!link.reachable && !link.syncing) {
                    Text(
                        "It keeps trying by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
                    Button(onClick = link::syncNow, enabled = !link.syncing) { Text("Sync now") }
                    OutlinedButton(onClick = { pairAgain = true }) { Text("Pair again") }
                    OutlinedButton(onClick = { confirmForget = true }) { Text("Forget") }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this phone?") },
            text = { Text("This deletes the pairing, the copy of the phone's data on this PC and any changes still waiting for the phone. The phone is not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    link.forget()
                    address = ""
                    key = ""
                    confirmForget = false
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SharedDataCard(store: DesktopStore, link: LinkController, modifier: Modifier) {
    val fromPhone = store.readOnly
    com.example.a2desktop.theme.GlassCard(modifier = modifier.devRef("6.3", "Shared data card"), contentPadding = 24.dp) {
        CardTitle(if (fromPhone) "What the phone has shared" else "What the phone will share")
        Text(
            when (store.source) {
                com.example.a2desktop.data.DataSource.PHONE -> "From the last sync."
                com.example.a2desktop.data.DataSource.SAMPLE -> "Demo data — not from your phone."
                com.example.a2desktop.data.DataSource.NONE -> "Fills in once your phone has synced."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val rows = listOf(
            Triple("Habits and daily records", "${store.habits.size} habits · ${store.logs.values.sumOf { it.size }} records", "Calendar dots, streaks, totals, goals"),
            Triple("Calendar events", "${store.events.size} events", "Dates and times"),
            Triple("Upcoming", "${store.upcoming.size} items", "Tasks and events due soon"),
            Triple("Gym", "${store.sessions.size} workouts", "Sessions with their sets"),
            Triple("Dreams", "${store.dreams.size} dreams", "Drafts and saved dreams"),
            Triple("Date of birth", if (store.dateOfBirth != null) "Set" else "Not set on the phone", "For the Life in Months page"),
        )
        rows.forEachIndexed { index, (name, count, detail) ->
            if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(26.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}
