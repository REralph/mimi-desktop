package com.example.a2desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The modules of the QR code for [text]: `[row][column]`, true = dark. No border (the drawing adds the quiet zone). */
fun qrModules(text: String): List<List<Boolean>> {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    return List(matrix.height) { y -> List(matrix.width) { x -> matrix[x, y] } }
}

/** [qrModules] as pixels (0xFFFFFF/0x000000 luminance), [scale] pixels per module and a [quiet]-module white border — what a camera would see. */
fun qrPixels(modules: List<List<Boolean>>, scale: Int = 4, quiet: Int = 4): Triple<IntArray, Int, Int> {
    val size = (modules.size + 2 * quiet) * scale
    val pixels = IntArray(size * size) { 0xFFFFFF }
    for (y in modules.indices) for (x in modules[y].indices) if (modules[y][x]) {
        for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx] = 0x000000
    }
    return Triple(pixels, size, size)
}

/** The pairing code: dark modules on a white card with a quiet border, [size] square (white and black regardless of the app's theme, so a phone camera can read it). */
@Composable
fun QrCodeImage(text: String, size: Dp = 220.dp, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier = modifier.size(size)) {
        drawRect(Color.White)
        val quiet = 2
        val cell = this.size.width / (modules.size + 2 * quiet)
        for (y in modules.indices) for (x in modules[y].indices) if (modules[y][x]) {
            drawRect(Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
