package com.example.a2desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.theme.GlassCard

/**
 * What every data page shows until the phone has sent its data. The desktop never invents habits,
 * workouts or dreams to fill a page: with nothing from the phone there is nothing to show, and this
 * says so and points at the one thing that fixes it.
 */
@Composable
fun NoDataPage(page: Page, link: LinkController, onOpenPhonePage: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.widthIn(max = 580.dp).devRef("0.4", "No-data card"), contentPadding = 32.dp) {
            Text("📱", fontSize = 40.sp)
            Text(
                "${page.label} shows your phone's data",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (link.pairing == null) {
                Text(
                    "This app hasn't been connected to your phone yet, so there is nothing to show. " +
                        "Nothing here is ever made up — everything on these pages comes from the Mimi app on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
                )
                Button(onClick = onOpenPhonePage) { Text("Connect your phone") }
            } else {
                Text(
                    if (link.syncing) "Getting your phone's data…"
                    else "The phone hasn't sent its data yet. This app keeps trying by itself. Check the phone is on the same Wi-Fi and Desktop link is on (in the Mimi app: ☰ → Desktop link), or press Sync now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                link.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
                    Button(onClick = link::syncNow, enabled = !link.syncing) { Text("Sync now") }
                    OutlinedButton(onClick = onOpenPhonePage) { Text("Phone page") }
                }
            }
        }
    }
}
