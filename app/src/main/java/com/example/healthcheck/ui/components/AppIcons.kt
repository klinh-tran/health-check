package com.example.healthcheck.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Thin line icons not in material-icons-core. */
object AppIcons {
    val Trends: ImageVector by lazy {
        ImageVector.Builder("Trends", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 17f)
                lineTo(9f, 11f)
                lineTo(13f, 15f)
                lineTo(21f, 7f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 7f)
                lineTo(21f, 7f)
                lineTo(21f, 13f)
            }
        }.build()
    }

    val Log: ImageVector by lazy {
        ImageVector.Builder("Log", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(6f, 21f); close()
                moveTo(9f, 8f); lineTo(15f, 8f)
                moveTo(9f, 12f); lineTo(15f, 12f)
                moveTo(9f, 16f); lineTo(13f, 16f)
            }
        }.build()
    }
}
