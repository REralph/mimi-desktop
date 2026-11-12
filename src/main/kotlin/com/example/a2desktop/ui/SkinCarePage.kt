package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.WeatherStore
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.planRoutine
import com.example.a2desktop.theme.GlassCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val planDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** Nights worked out ahead on the page. */
private const val PLAN_NIGHTS = 14

/** One product the user has: what it is, and when it is used. */
private class OwnedProduct(
    val chip: String,
    val color: Color,
    val name: String,
    val kind: String,
    val whenToUse: String,
    val note: String,
)

private val ownedProducts = listOf(
    OwnedProduct(
        "G", GlycolicColor, "Findley Glycolic acid + Vitamin C", "Exfoliating treatment",
        "Evenings only. One of at most 2 exfoliating nights a week, taking turns with the toner — never both on one night, never two exfoliating nights in a row. A blank night follows. Planned for nights when tomorrow's sun is low or you aren't out in it.",
        "Some Findley glycolic serums also list retinol — check your bottle. If yours does, it is a strong night on its own.",
    ),
    OwnedProduct(
        "T", TonerColor, "The Ordinary Glycolic Acid 7% Exfoliating Toner", "Exfoliating toner",
        "Evenings only, taking turns with the Findley treatment and counting towards the same 2 a week. The Ordinary says: start at 2–3 times a week, patch test first, don't combine it with other direct acids in the same routine, and use sunscreen and limit sun while using it and for a week after.",
        "Skip it on sensitive, peeling or sore skin.",
    ),
    OwnedProduct(
        "C", BrightenColor, "Minimalist Vitamin C 10%", "Brightening serum",
        "Evenings, on serum nights — taking turns with the niacinamide, and together with it on your heavier night (code NC). Never on an exfoliating night, and the night after a serum night is blank.",
        "Usually a morning serum; kept to evenings because that's when you use your products.",
    ),
    OwnedProduct(
        "N", CalmColor, "The Ordinary Niacinamide 10% + Zinc 1%", "Calming, tone-evening serum",
        "Evenings, on serum nights — taking turns with the Vitamin C, and together with it on your heavier night (code NC).",
        "Fine in the morning too, but your mornings stay light.",
    ),
    OwnedProduct(
        "M", RestColor, "CeraVe Moisturising Cream", "Moisturiser",
        "The last step on every evening that has something on — and the only thing in your light morning. Rest nights are left blank; add it then only if your skin feels dry.",
        "",
    ),
)

/**
 * The Skin care page: the products the user has and when each is used, any extra products they add, the
 * weather forecast setting that makes the plan follow the real sun, and the next evenings worked out. The
 * routine is for EVENINGS only — mornings stay light. Lives on this PC only; the forecast is the one
 * thing that uses the internet, and only once a town has been chosen here.
 */
@Composable
fun SkinCarePage(skin: SkinCareStore, weather: WeatherStore, work: WorkSchedule, today: LocalDate) {
    Row(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ScrollColumn(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader(title = "Skin care", subtitle = "Evenings only — mornings stay light", modifier = Modifier.devRef("4.1", "Page header"))
            ProductsCard()
            OtherProductsCard(skin)
        }
        ScrollColumn(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GentleCard()
            WeatherCard(weather)
            NightsCard(skin, weather, work, today)
        }
    }
}

@Composable
private fun ProductsCard() {
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("4.2", "Your products")) {
        CardTitle("Your products")
        ownedProducts.forEachIndexed { index, product ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.padding(top = 2.dp).size(22.dp).background(product.color.copy(alpha = 0.9f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(product.chip, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101018))
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(product.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(product.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(product.whenToUse, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (product.note.isNotEmpty()) {
                        Text(product.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text("☀", fontSize = 18.sp, modifier = Modifier.width(34.dp).padding(top = 1.dp))
            Column {
                Text("Sunscreen SPF 30+", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Not in your list", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    "Glycolic acid makes skin more sun-sensitive for up to a week (FDA), so one is worth having — the morning after glycolic and whenever UV is 3 or more.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** How the plan keeps to a gentle pace, what that is based on, and what it can't know. */
@Composable
private fun GentleCard() {
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("4.4", "Is it gentle enough?")) {
        CardTitle("Is it gentle enough?")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What the plan does", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Bullet("At most 2 exfoliating nights in any week. The Findley treatment and the toner are both glycolic acid, so they count together and never share a night.")
            Bullet("Never two exfoliating nights in a row — and every exfoliating night is followed by a blank night with nothing active on.")
            Bullet("The serum nights go round niacinamide (N), Vitamin C (C), then both together (NC — your heavier night). Each serum night is followed by a blank night too. That works out at about 3 blank nights a week.")
            Bullet("No exfoliating night before a day you'd be out in strong sun (UV 3+), and when tomorrow's work isn't known it plays safe.")
            Text("What it's based on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Bullet("AAD, Cleveland Clinic and Harvard Health: exfoliate at most 2–3 times a week — skin needs time to repair between — and stop if it stings, reddens, flakes or feels tight.")
            Bullet("The Ordinary: start the toner at 2–3 times a week, patch test, don't combine it with other direct acids, and use sunscreen while using it and for a week after. FDA says the same about the sun for glycolic acid.")
            Text("What it can't promise", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Bullet("Nobody publishes an exact number of rest nights. The blank nights are a cautious choice by Mimi, not a medical standard.")
            Bullet("You never have to tick anything: a day you don't tick counts as followed. If you used something different, tap its code on that day in the Calendar and the routine carries on from what you used.")
            Bullet("It can't see your skin. If it feels sore, press on, or look red — skip the actives until it settles. Patch test anything new, follow each bottle's own directions, and ask a pharmacist or dermatologist if you're unsure. General information, not medical advice.")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OtherProductsCard(skin: SkinCareStore) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("4.3", "Other products you have")) {
        CardTitle("Other products you have")
        Text(
            "Anything else on your shelf. These are only listed here — the plan doesn't schedule them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (skin.customProducts.isEmpty()) {
            Text("Nothing added yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        skin.customProducts.forEach { product ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(product.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (product.note.isNotEmpty()) {
                        Text(product.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { skin.removeProduct(product.name) }) { Text("Remove", style = MaterialTheme.typography.bodySmall) }
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Product name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("What it's for (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedButton(
            enabled = name.isNotBlank(),
            onClick = {
                skin.addProduct(name, note)
                name = ""
                note = ""
            },
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("Add product") }
    }
}

@Composable
private fun WeatherCard(weather: WeatherStore) {
    var query by remember { mutableStateOf("") }
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("4.5", "Weather & UV forecast")) {
        CardTitle("Weather & UV forecast")
        val place = weather.place
        if (place == null) {
            Text(
                "Tell Mimi your town and it will use the real UV forecast to pick the best evenings for glycolic. Until then it uses a seasonal estimate.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Privacy: only the town name (to find it) and then its coordinates, rounded to about a kilometre, are sent to Open-Meteo.com — no account, nothing else. Nothing is sent until you pick a town.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Your town") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = query.isNotBlank() && !weather.busy && weather.online,
                    onClick = { weather.search(query) },
                    modifier = Modifier.padding(start = 10.dp),
                ) { Text(if (weather.busy) "Searching…" else "Search") }
            }
            weather.matches.forEach { match ->
                TextButton(onClick = { weather.choose(match) }, modifier = Modifier.fillMaxWidth()) {
                    Text(match.label, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Text("Forecast for ${place.label}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val held = weather.forecast
            Text(
                when {
                    weather.busy -> "Updating…"
                    held != null -> "Updated ${clockText(held.fetchedAtMillis)} · ${held.uvByDay.size} days"
                    else -> "No forecast yet — using the seasonal estimate"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !weather.busy && weather.online, onClick = { weather.refresh(force = true) }) { Text("Refresh now") }
                OutlinedButton(onClick = { weather.clear() }) { Text("Use a different town / stop") }
            }
        }
        weather.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "Weather data by Open-Meteo.com (CC BY 4.0).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun NightsCard(skin: SkinCareStore, weather: WeatherStore, work: WorkSchedule, today: LocalDate) {
    val log = skin.log
    val forecast = weather.forecast
    val uv = remember(forecast) { weather.uvLookup() }
    val plan = remember(today, skin.startedOn, skin.confirmFrom, log, work, uv) { planRoutine(today, skin.startedOn, EVENINGS_AHEAD_DAYS - 1, log, work, uv, skin.confirmFrom) }
    val rows = remember(today, log, work, plan, uv) { eveningRows(today, log, work, plan, uv) }
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("4.6", "Evenings list (scrolls)")) {
        CardTitle("Evenings — past and next ${EVENINGS_AHEAD_DAYS}")
        Text(
            "Opens at today. Scroll up for the days before. Tick each evening in the Skin care card on the Calendar once you've done it: tonight shows faded until you do, and one you never tick counts as not done — it drops off the next day and the plan moves on without it. Days before confirming began are still counted as followed. Each coming day is chosen from your work times and the UV. Mornings: just the moisturiser.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        EveningsList(rows = rows, today = today, selected = null, height = 460.dp, showReason = true)
    }
}