package com.example.healthcheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.healthcheck.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/*
 * Debug-only previews of the adaptive launcher icon, the way launchers draw it: each 108dp layer is
 * scaled so its central 72dp fills the icon, then cut by the launcher's mask.
 */

private val Squircle: Shape = GenericShape { size, _ ->
    // Superellipse |x|^4 + |y|^4 = 1 (the common "squircle" mask).
    val n = 4.0
    val steps = 120
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = (sign(c) * abs(c).pow(2 / n) + 1) / 2 * size.width
        val y = (sign(s) * abs(s).pow(2 / n) + 1) / 2 * size.height
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
}

private val masks = listOf("Circle" to CircleShape, "Squircle" to Squircle, "Rounded square" to RoundedCornerShape(22))

/** One masked icon. [themed] draws the monochrome layer tinted, as Android 13+ themed icons do. */
@Composable
private fun LauncherIcon(size: Dp, mask: Shape, themed: Boolean = false, showSafeZone: Boolean = false) {
    val layer = size * (108f / 72f)
    Box(Modifier.size(size).clip(mask), contentAlignment = Alignment.Center) {
        if (themed) {
            Box(Modifier.requiredSize(layer).background(Color(0xFFDCE4F5)))
            Image(
                painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(layer),
                colorFilter = ColorFilter.tint(Color(0xFF2B3A55)),
            )
        } else {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(layer))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(layer))
        }
        if (showSafeZone) {
            // The 66dp safe zone: everything inside it is never cut by any mask.
            Canvas(Modifier.requiredSize(layer)) {
                drawCircle(
                    Color.Red, radius = this.size.width * 33f / 108f,
                    style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
                )
            }
        }
    }
}

@Composable
private fun IconSheet(textColor: Color) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        masks.forEach { (name, shape) ->
            Text(name, color = textColor, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(48.dp, 96.dp, 192.dp).forEach { LauncherIcon(it, shape) }
            }
        }
        Text("Themed (monochrome) and safe zone", color = textColor, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            masks.forEach { (_, shape) -> LauncherIcon(96.dp, shape, themed = true) }
            LauncherIcon(96.dp, Squircle, showSafeZone = true)
        }
    }
}

@Preview(name = "Launcher icon – light", showBackground = true, backgroundColor = 0xFFF4F4F4, widthDp = 460)
@Composable
private fun LauncherIconLightPreview() = IconSheet(Color(0xFF12161C))

@Preview(name = "Launcher icon – dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 460)
@Composable
private fun LauncherIconDarkPreview() = IconSheet(Color(0xFFEEF1F6))
