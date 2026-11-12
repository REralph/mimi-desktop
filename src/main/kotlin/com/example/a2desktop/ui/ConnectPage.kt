package com.example.a2desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.JobAccount
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.data.connectionSummary
import com.example.a2desktop.theme.GlassCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The colour of "connected": the same green the Phone tab's dot uses. */
internal val MAIL_CONNECTED_GREEN = Color(0xFF6FCF97)
private val NOT_CONNECTED_GREY = Color(0xFF9AA0A6)

private val connectedAtFmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")

/** The Google pages the one-time setup goes through. */
private const val URL_NEW_PROJECT = "https://console.cloud.google.com/projectcreate"
private const val URL_GMAIL_API = "https://console.cloud.google.com/apis/library/gmail.googleapis.com"
private const val URL_CONSENT = "https://console.cloud.google.com/apis/credentials/consent"
private const val URL_CREDENTIALS = "https://console.cloud.google.com/apis/credentials"

/**
 * The Connect Gmail view of the Jobs page (opened by the button in the Jobs header, PC7.1; **Back to Jobs** returns): where the Gmail mailboxes
 * are connected, and where it says plainly whether Mimi is connected and to which address. A banner at the top says it; under it come the
 * one-time Google setup (until it is done), one card per mailbox with its Connect / Scan now / Disconnect buttons, and a box to add another
 * address. What is found in the mail is shown on the Jobs page. Lives on this PC only; needs no phone.
 */
@Composable
fun ConnectPage(store: JobsStore, onBack: () -> Unit = {}) {
    ScrollColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(title = "Connect Gmail", subtitle = connectionSummary(store.accounts), modifier = Modifier.devRef("8.1", "Page header")) {
            OutlinedButton(onClick = onBack, modifier = Modifier.devRef("8.1.1", "Back to Jobs")) { Text("← Back to Jobs") }
        }
        StatusLine(store)
        StatusBanner(store)
        if (store.online && !store.clientReady) SetupCard(store)
        Column(modifier = Modifier.fillMaxWidth().devRef("8.4", "Mailboxes"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            store.accounts.forEachIndexed { index, account -> MailboxCard(index + 1, account, store) }
        }
        AddMailboxRow(store)
    }
}

/** What is happening or went wrong. */
@Composable
internal fun StatusLine(store: JobsStore) {
    val text = store.progress ?: store.message ?: return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (store.progress != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
}

/** The answer to "am I connected?", big: a green dot and the address(es), or a grey dot and what to do next. */
@Composable
private fun StatusBanner(store: JobsStore) {
    val connected = store.accounts.filter { it.connected }
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("8.2", "Connection status")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (connected.isNotEmpty()) MAIL_CONNECTED_GREEN else NOT_CONNECTED_GREY, size = 16.dp, modifier = Modifier.padding(end = 14.dp))
            Column {
                if (connected.isNotEmpty()) {
                    Text("Connected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MAIL_CONNECTED_GREEN)
                    connected.forEach { Text(it.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                    Text(
                        "Mimi can read this mailbox (read-only: it can't send, delete or change anything). What it finds is on the Jobs page.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text("Not connected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !store.online -> "Connecting to Gmail isn't available here."
                            !store.clientReady -> "First finish the one-time setup below, then press Connect on your mailbox."
                            else -> "Press Connect on your mailbox below: your browser opens Google's page, you sign in there and press Allow."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- the one-time setup ----

@Composable
private fun SetupCard(store: JobsStore) {
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("8.3", "Gmail setup")) {
        CardTitle("One-time setup")
        Text(
            "Mimi reads your email through Google's own sign-in, read-only: it can't send, delete or change anything, and your password never goes through Mimi. " +
                "Google asks you to make a small \"client file\" for that — about five minutes, once. The pages open in your browser.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupStep("1", "Create a project (any name, e.g. Mimi) — or use one you already have.", "Open Google Cloud") { store.openUrl(URL_NEW_PROJECT) }
            SetupStep("2", "Turn on the Gmail API for that project.", "Open the Gmail API page") { store.openUrl(URL_GMAIL_API) }
            SetupStep("3", "Set up the consent screen: choose External, and add your Gmail address as a test user.", "Open the consent screen") { store.openUrl(URL_CONSENT) }
            SetupStep("4", "Create credentials → OAuth client ID → Application type \"Desktop app\", then download its JSON file.", "Open Credentials") { store.openUrl(URL_CREDENTIALS) }
            SetupStep("5", "Leave the downloaded file in your Downloads folder — Mimi finds it below — or put it in Mimi's folder as gmail-client.json.", "Open Mimi's folder") { store.openFolder(store.appFolder) }
        }
        store.downloadedClients.forEach { found ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Found in Downloads: ${found.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { store.importClient(found) }) { Text("Use this file") }
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { store.checkSetup() }) { Text("Check again") }
        }
        Text(
            "Heads-up: while the app is in Google's \"Testing\" state, Google ends the connection after about a week — press Connect again if the page says so. " +
                "Publishing the app on the same consent screen stops that.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SetupStep(number: String, text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}

// ---- a mailbox ----

@Composable
private fun MailboxCard(number: Int, account: JobAccount, store: JobsStore) {
    val working = store.busy && store.busyAccount.equals(account.email, ignoreCase = true)
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("8.4.$number", "Mailbox ${account.email}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (account.connected) MAIL_CONNECTED_GREEN else NOT_CONNECTED_GREY, size = 12.dp, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (account.connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (account.connected) MAIL_CONNECTED_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        working -> store.progress ?: "Working…"
                        account.connected && account.lastScanMillis > 0 -> "Last read ${connectedAt(account.lastScanMillis)} · ${account.emailsFound} application emails kept"
                        account.connected -> "Not read yet — press Scan now"
                        account.emailsFound > 0 -> "${account.emailsFound} application emails kept from ${connectedAt(account.lastScanMillis)} — connect again to read new ones"
                        !store.online -> "Connecting isn't available here."
                        !store.clientReady -> "Finish the one-time setup first."
                        else -> "Press Connect: your browser opens Google's page — sign in as ${account.email} and press Allow."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (account.connected) {
                OutlinedButton(onClick = { store.scan(account.email) }, enabled = !store.busy) { Text("Scan now") }
                TextButton(onClick = { store.disconnect(account.email) }, enabled = !store.busy) { Text("Disconnect") }
            } else {
                OutlinedButton(onClick = { store.connect(account.email) }, enabled = store.online && store.clientReady && !store.busy) { Text("Connect") }
            }
            if (number > 1) TextButton(onClick = { store.removeAccount(account.email) }, enabled = !store.busy) { Text("Remove") }
        }
    }
}

private fun connectedAt(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(connectedAtFmt)

// ---- adding another mailbox ----

@Composable
private fun AddMailboxRow(store: JobsStore) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth().devRef("8.5", "Add a mailbox"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Another Gmail address") }, singleLine = true, modifier = Modifier.width(340.dp))
        OutlinedButton(onClick = { if (store.addAccount(text)) text = "" }, enabled = text.isNotBlank()) { Text("Add") }
    }
}
