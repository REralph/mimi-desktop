package com.example.a2desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.a2desktop.theme.DarkPrimary
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws the app icon with the app's own theme colours and writes the files the app needs
 * (`./gradlew renderIcon`): `src/main/resources/icon.png` (the window icon) and
 * `packaging/a2-desktop.ico` (the .exe / shortcut icon, with 16–256 px sizes so it stays crisp).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val projectDir = File(args.firstOrNull() ?: ".")
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.associateWith { size -> renderPng(size) }

    File(projectDir, "src/main/resources").apply { mkdirs() }.resolve("icon.png").writeBytes(pngs.getValue(256))
    File(projectDir, "packaging").apply { mkdirs() }.resolve("a2-desktop.ico").writeBytes(packIco(pngs))
    println("Wrote icon.png and a2-desktop.ico to ${projectDir.absolutePath}")
}

@Composable
private fun IconArt(size: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape((size * 0.22f).dp()))
            .background(Brush.linearGradient(listOf(Color(0xFF3B4696), Color(0xFF0B0C12)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("M", color = DarkPrimary, fontSize = (size * 0.62f).sp, fontWeight = FontWeight.Bold)
    }
}

// The scene runs at density 1, so dp == px here.
private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

@OptIn(ExperimentalComposeUiApi::class)
private fun renderPng(size: Int): ByteArray {
    val scene = ImageComposeScene(width = size, height = size, density = Density(1f)) { IconArt(size) }
    try {
        scene.render(0L)
        val image = scene.render(100_000_000L)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed for $size px")
    } finally {
        scene.close()
    }
}

/** An .ico file holding PNG-compressed images (supported since Windows Vista). */
private fun packIco(pngBySize: Map<Int, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0).putShort(1).putShort(pngBySize.size.toShort())
    out.write(header.array())
    var offset = 6 + 16 * pngBySize.size
    pngBySize.forEach { (size, png) ->
        val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        entry.put((if (size >= 256) 0 else size).toByte()).put((if (size >= 256) 0 else size).toByte())
        entry.put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(offset)
        out.write(entry.array())
        offset += png.size
    }
    pngBySize.values.forEach { out.write(it) }
    return out.toByteArray()
}
