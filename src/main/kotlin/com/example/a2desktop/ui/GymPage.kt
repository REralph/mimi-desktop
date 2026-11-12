package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.model.WorkoutSession
import com.example.a2desktop.theme.GlassCard
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val sessionDateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

private fun kg(value: Double): String =
    if (value >= 1000) String.format("%,.0f kg", value) else String.format("%.1f kg", value).replace(".0 kg", " kg")

private fun minutes(value: Int?): String = if (value == null) "—" else "$value min"

/**
 * Gym — the phone's Gym Home / Workout History on one wide screen: summary tiles across the top,
 * the list of sessions on the left, and the selected session's exercises and sets on the right.
 */
@Composable
fun GymPage(store: DesktopStore) {
    var selectedId by remember { mutableStateOf(store.sessions.firstOrNull()?.id) }
    val selected = store.sessions.firstOrNull { it.id == selectedId }
    val recent = store.sessions.filter { ChronoUnit.DAYS.between(it.date, store.today) <= 30 }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageHeader("Gym", "Workout history", modifier = Modifier.devRef("2.1", "Page header"))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().devRef("2.2", "Summary tiles")) {
            StatTile(recent.size.toString(), "Sessions in the last 30 days", Modifier.weight(1f))
            StatTile(kg(recent.sumOf { it.volumeKg }), "Total volume (30 days)", Modifier.weight(1f))
            StatTile(
                minutes(recent.mapNotNull { it.durationMin }.let { if (it.isEmpty()) null else it.sum() / it.size }),
                "Average session length",
                Modifier.weight(1f),
            )
            StatTile(store.sessions.maxOfOrNull { it.volumeKg }?.let(::kg) ?: "—", "Best session volume", Modifier.weight(1f))
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ScrollColumn(modifier = Modifier.width(400.dp).fillMaxHeight().devRef("2.3", "Session list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (store.sessions.isEmpty()) Text("No workouts recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                store.sessions.forEach { session ->
                    SessionCard(session, selected = session.id == selectedId) { selectedId = session.id }
                }
            }
            GlassCard(modifier = Modifier.weight(1f).fillMaxHeight().devRef("2.4", "Session detail"), contentPadding = 20.dp) {
                if (selected == null) {
                    Text("Select a session to see its sets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SessionDetail(selected)
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: WorkoutSession, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface.copy(alpha = 0.85f), shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row {
            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(session.date.format(sessionDateFmt), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Text(
            listOfNotNull(session.runSummary ?: "${session.exercises.size} exercises", minutes(session.durationMin), if (session.volumeKg > 0) kg(session.volumeKg) else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SessionDetail(session: WorkoutSession) {
    ScrollColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(session.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                session.date.format(sessionDateFmt),
                minutes(session.durationMin),
                if (session.volumeKg > 0) "${kg(session.volumeKg)} total" else null,
                session.runSummary,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (session.exercises.isEmpty() && session.runSummary == null) {
            Text("No exercises were recorded for this session.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        session.exercises.forEachIndexed { index, exercise ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.08f))
            Row {
                Text(exercise.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (exercise.volumeKg > 0) {
                    Text(kg(exercise.volumeKg), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (exercise.sets.isEmpty()) {
                // Older entries were typed as free text rather than logged set by set.
                val planned = listOf(exercise.plannedWeight, exercise.plannedSets).filter { it.isNotBlank() }.joinToString("  ·  ")
                Text(
                    planned.ifBlank { "No sets recorded" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            exercise.sets.forEachIndexed { i, set ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Set ${i + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
                    Text(set.reps?.let { "$it reps" } ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
                    Text(
                        when (set.weightKg) {
                            null -> "—"
                            0.0 -> "Bodyweight"
                            else -> kg(set.weightKg)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }
            }
            if (exercise.notes.isNotBlank()) {
                Text(exercise.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
