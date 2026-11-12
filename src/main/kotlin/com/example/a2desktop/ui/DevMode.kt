package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.logic.ClipboardAccess
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Developer mode: a switch in the top bar that puts a small numbered label on every group of information on
// screen ("PC1.5 Selected day card"), so a change can be asked for by its number. Click a label and its
// reference is copied. The labels are drawn in ONE separate layer on top of the whole app ([DevOverlay]); the
// groups only report where they are ([devRef] adds a position callback and nothing else), so turning it on
// can never move, resize or restyle anything underneath. With the switch off, [devRef] returns the modifier
// unchanged: no cost at all.
//
// Numbering is by page, in the order of the pages in the top bar: PC0 the app frame, PC1 Calendar, PC2 Gym, PC3 Dreams,
// PC4 Skin care, PC5 Life, PC6 Phone. "PC" keeps these apart from the phone app's own numbers (0.14, 2.1 ...).

/** One labelled group on screen: its number, its name, and where it is (window pixels). */
internal data class DevGroup(val id: String, val name: String, val bounds: Rect, val fullWidthContent: Boolean = false)

/** Whether developer mode is on, and the groups currently on screen. */
class DevMode {
    var enabled by mutableStateOf(false)

    internal val groups = mutableStateMapOf<String, DevGroup>()

    /** Where each label was drawn (window pixels), by group id — only so tests can find and click them. */
    internal var badgeBounds: Map<String, Rect> = emptyMap()

    fun toggle() {
        enabled = !enabled
        if (!enabled) groups.clear()
    }

    /** Every group on screen as "PC1.5 — Calendar › Selected day card" lines, in number order. */
    internal fun listText(): String = groups.values.sortedWith(idOrder).joinToString("\n") { devClipboardText(it.id, it.name) }
}

/** The developer mode of the app being composed; a switched-off one when there is none (previews, other screens). */
val LocalDevMode = staticCompositionLocalOf { DevMode() }

/** Number order: 1.2 before 1.10, and 1.5 before 1.5.1. */
private val idOrder = Comparator<DevGroup> { a, b ->
    val x = a.id.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.id.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
    x.size.compareTo(y.size)
}

/**
 * Marks this as the information group [id] ([name] says what it is). Put it on the group's own modifier chain.
 * It only reports the group's position to developer mode; it changes nothing about layout or drawing.
 * [fullWidthContent] is for a part whose content reaches its right-hand edge (a row of buttons): its label then
 * goes above it rather than inside its corner.
 */
@Composable
fun Modifier.devRef(id: String, name: String, fullWidthContent: Boolean = false): Modifier {
    val dev = LocalDevMode.current
    if (!dev.enabled) return this
    DisposableEffect(id) { onDispose { dev.groups.remove(id) } }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        if (bounds.isEmpty) {
            dev.groups.remove(id) // scrolled completely out of view
        } else {
            val group = DevGroup(id, name, bounds, fullWidthContent)
            if (dev.groups[id] != group) dev.groups[id] = group
        }
    }
}

/** The page a number belongs to, for the copied text. */
internal fun devPageName(id: String): String = when (id.substringBefore('.')) {
    "0" -> "App"
    "1" -> "Calendar"
    "2" -> "Gym"
    "3" -> "Dreams"
    "4" -> "Skin care"
    "5" -> "Life in Months"
    "6" -> "Phone link"
    "7" -> "Jobs"
    "8" -> "Connect"
    else -> "?"
}

/** What a label copies: "PC1.5 — Calendar › Selected day card". */
internal fun devClipboardText(id: String, name: String): String = "PC$id — ${devPageName(id)} › $name"

/**
 * A label's position and size, in window pixels. [text] is what it normally says; a label for a part inside a
 * group is just its number, and pointing at it widens it to [fullText] (growing leftwards from its right end).
 */
internal data class BadgePlacement(
    val id: String,
    val text: String,
    val fullText: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fullWidth: Float,
    val copiedWidth: Float,
)

/** Whether [other] lies entirely inside this rectangle (give or take a pixel). */
private fun Rect.containsRect(other: Rect): Boolean =
    left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

/** The area where two rectangles overlap (0 if they only touch or are apart). */
private fun overlapArea(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 0f && h > 0f) w * h else 0f
}

/**
 * Where each label goes. A group's label has a few spots to try, and takes the one that covers the least: it
 * must be inside the window and not land on another label, and among those the winner is the spot that overlaps
 * the fewest pixels of NEIGHBOURING groups (a group that contains it, or that it contains, isn't a neighbour) and
 * of the group's own content. Ties go to the earlier spot.
 *  - A whole block (a card, a list): number and name, just above its top-left corner — in the gap — else above its
 *    top-right, below its bottom-left or bottom-right, or (last choice) inside its top corners.
 *  - A part inside a block (a line, a row of buttons): just its number — small enough to sit in the empty space
 *    inside the right end of the part's top edge, where short lines of text never reach.
 */
internal fun placeBadges(groups: Collection<DevGroup>, area: Rect, height: Float, widthOf: (String) -> Float, gap: Float = 4f): List<BadgePlacement> {
    val placed = mutableListOf<Rect>()
    return groups.sortedWith(idOrder).map { group ->
        val b = group.bounds
        val part = group.id.count { it == '.' } >= 2
        val fullText = "PC${group.id} ${group.name}"
        val text = if (part) "PC${group.id}" else fullText
        val w = widthOf(text)
        val insideRight = Rect(b.right - w - 2f, b.top + 1f, b.right - 2f, b.top + 1f + height) // short lines are empty there
        val straddle = Rect(b.right - w - 2f, b.top - height / 2f, b.right - 2f, b.top + height / 2f)
        val aboveRight = Rect(b.right - w - 2f, b.top - height - 1f, b.right - 2f, b.top - 1f)
        val aboveLeft = Rect(b.left + 6f, b.top - height - 1f, b.left + 6f + w, b.top - 1f)
        val candidates = if (part) {
            if (group.fullWidthContent) listOf(aboveRight, straddle, aboveLeft, insideRight) else listOf(insideRight, straddle, aboveRight, aboveLeft)
        } else listOf(
            Rect(b.left + 6f, b.top - height - 1f, b.left + 6f + w, b.top - 1f), // above, left end
            Rect(b.right - w - 6f, b.top - height - 1f, b.right - 6f, b.top - 1f), // above, right end
            Rect(b.left + 6f, b.bottom + 1f, b.left + 6f + w, b.bottom + 1f + height), // below, left end
            Rect(b.right - w - 6f, b.bottom + 1f, b.right - 6f, b.bottom + 1f + height), // below, right end
            Rect(b.right - w - 6f, b.top + 2f, b.right - 6f, b.top + 2f + height), // inside, top-right
            Rect(b.left + 6f, b.top + 2f, b.left + 6f + w, b.top + 2f + height), // inside, top-left
        )
        val neighbours = groups.filter { it.id != group.id && !it.bounds.containsRect(b) && !b.containsRect(it.bounds) }
        // What a spot would cover. A part inside a block only minds neighbours (its own right-hand end is empty space);
        // a whole block minds its own content twice as much, and neighbours a quarter as much (their bounds are often
        // padding), and each step down its list of spots costs a little so "just above the corner" wins ties.
        fun cost(index: Int, r: Rect): Float {
            val near = neighbours.sumOf { overlapArea(r, it.bounds).toDouble() }.toFloat()
            return if (part) near + index * 50f else near * 0.25f + 2f * overlapArea(r, b) + index * 150f
        }
        val usable = candidates.withIndex().filter { (_, r) ->
            r.left >= area.left && r.right <= area.right && r.top >= area.top && r.bottom <= area.bottom && placed.none { it.overlaps(r) }
        }
        var spot = usable.minByOrNull { (index, r) -> cost(index, r) }?.value
        if (spot == null) {
            // Nowhere is clear of the other labels: keep the first choice, nudged off them and into the window.
            var r = candidates.first()
            repeat(24) {
                val hit = placed.firstOrNull { it.overlaps(r) } ?: return@repeat
                val x = hit.right + gap
                r = if (x + w > area.right) Rect(candidates.first().left, hit.bottom + gap, candidates.first().left + w, hit.bottom + gap + height)
                else Rect(x, r.top, x + w, r.bottom)
            }
            val x = r.left.coerceIn(area.left, maxOf(area.left, area.right - w))
            val y = r.top.coerceIn(area.top, maxOf(area.top, area.bottom - height))
            spot = Rect(x, y, x + w, y + height)
        }
        placed += spot
        BadgePlacement(group.id, text, fullText, spot.left, spot.top, w, height, widthOf(fullText), widthOf("Copied PC${group.id}"))
    }
}
private val DevAmber = Color(0xFFF2C94C)
private val DevGreen = Color(0xFF6FCF97)

/**
 * The labels, drawn above everything else. Not part of the page's layout at all: it only reads where the
 * groups report themselves to be. Clicking a label copies its reference; pointing at one outlines its group.
 */
@Composable
internal fun DevOverlay(dev: DevMode, clipboard: ClipboardAccess) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var copied by remember { mutableStateOf<String?>(null) }
    var hovered by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1_200)
            copied = null
        }
    }

    val padding = with(density) { 6.dp.toPx() }
    val badgeHeight = with(density) { 16.dp.toPx() }
    val groups = dev.groups.values.toList()
    val area = Rect(origin, Size(size.width.toFloat(), size.height.toFloat()))
    val placements = placeBadges(groups, area, badgeHeight, widthOf = { text ->
        measurer.measure(text, style, softWrap = false, maxLines = 1).size.width + padding * 2
    })
    SideEffect { dev.badgeBounds = placements.associate { it.id to Rect(Offset(it.x, it.y), Size(it.width, it.height)) } }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow(); size = it.size }) {
        // Pointing at a label outlines the group it names (an outline only: nothing underneath changes).
        groups.firstOrNull { it.id == hovered }?.let { group ->
            Box(
                modifier = Modifier
                    .offset { IntOffset((group.bounds.left - origin.x).roundToInt(), (group.bounds.top - origin.y).roundToInt()) }
                    .size(with(density) { group.bounds.width.toDp() }, with(density) { group.bounds.height.toDp() })
                    .border(1.5.dp, DevAmber.copy(alpha = 0.9f), RoundedCornerShape(4.dp)),
            )
        }
        val byId = groups.associateBy { it.id }
        placements.forEach { place ->
          val group = byId[place.id] ?: return@forEach
          key(place.id) {
            val source = remember { MutableInteractionSource() }
            val isHovered by source.collectIsHoveredAsState()
            LaunchedEffect(isHovered) {
                if (isHovered) hovered = place.id else if (hovered == place.id) hovered = null
            }
            val justCopied = copied == place.id
            val accent = if (justCopied) DevGreen else DevAmber
            // Pointed at (or just copied), a small number-only label widens to say what it is, growing leftwards.
            val width = when {
                justCopied -> maxOf(place.width, place.copiedWidth)
                isHovered -> maxOf(place.width, place.fullWidth)
                else -> place.width
            }
            val left = (place.x + place.width - width).coerceAtLeast(origin.x) // widens leftwards, never off the window
            Box(
                modifier = Modifier
                    .offset { IntOffset(((if (width > place.width) left else place.x) - origin.x).roundToInt(), (place.y - origin.y).roundToInt()) }
                    .size(with(density) { width.toDp() }, 16.dp)
                    .background(Color(0xEE15110A), RoundedCornerShape(4.dp))
                    .border(1.dp, accent.copy(alpha = if (isHovered || justCopied) 1f else 0.7f), RoundedCornerShape(4.dp))
                    .hoverable(source)
                    .clickable {
                        if (clipboard.write(devClipboardText(group.id, group.name))) copied = place.id
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        justCopied -> "Copied PC${group.id}"
                        isHovered -> place.fullText
                        else -> place.text
                    },
                    style = style,
                    color = accent,
                    maxLines = 1,
                    softWrap = false,
                )
            }
          }
        }
    }
}

/** The switch in the top bar, just before Phone — and, while it is on, a button beside it that copies every label on screen. */
@Composable
internal fun DevToggle(dev: DevMode, clipboard: ClipboardAccess) {
    Row(
        modifier = Modifier.devRef("0.2", "Developer mode switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = dev.enabled, onCheckedChange = { dev.toggle() }, modifier = Modifier.scale(0.72f).height(24.dp))
            Text(
                "Dev mode",
                fontSize = 10.sp,
                color = if (dev.enabled) DevAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The space for "Copy all" is always kept, so the switch itself never moves when it is turned on or off.
        Box(modifier = Modifier.width(72.dp).height(26.dp), contentAlignment = Alignment.Center) {
            if (dev.enabled) {
                Text(
                    "Copy all",
                    fontSize = 10.sp,
                    color = DevAmber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { clipboard.write(dev.listText()) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
