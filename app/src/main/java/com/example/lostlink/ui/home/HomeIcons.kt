package com.example.lostlink.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

enum class HomeIcon { HOME, SEARCH, ADD, CHAT, PERSON, BOX, SCAN, CHEVRON, CHECK, PIN, CLOCK, BACK, BOOKMARK, SHARE, PHOTO, CALENDAR, DESK }

@Composable
fun LineIcon(icon: HomeIcon, color: Color = Color(0xFF85858D), modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(vararg points: Float) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(path, color, style = Stroke(1.5f, cap = StrokeCap.Round))
            }
            fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r, Offset(x, y), style = Stroke(1.5f))
            when (icon) {
                HomeIcon.HOME -> { line(3f,10f,12f,3f,21f,10f); line(5f,9f,5f,21f,10f,21f,10f,14f,14f,14f,14f,21f,19f,21f,19f,9f) }
                HomeIcon.SEARCH -> { circle(10f,10f,6.5f); line(15f,15f,21f,21f) }
                HomeIcon.ADD -> { circle(12f,12f,9f); line(8f,12f,16f,12f); line(12f,8f,12f,16f) }
                HomeIcon.CHAT -> { val p = Path().apply { moveTo(4f,17f); cubicTo(-2f,2f,22f,-1f,21f,12f); cubicTo(21f,19f,13f,22f,7f,19f); lineTo(2f,21f); close() }; drawPath(p,color,style=Stroke(1.5f)) }
                HomeIcon.PERSON -> { circle(12f,7f,3.5f); val p=Path().apply { moveTo(5f,21f); cubicTo(5f,9f,19f,9f,19f,21f) }; drawPath(p,color,style=Stroke(1.5f)) }
                HomeIcon.BOX -> { line(4f,7f,12f,3f,20f,7f,12f,11f,4f,7f,4f,17f,12f,21f,12f,11f); line(8f,5f,16f,9f,16f,13f); line(20f,7f,20f,13f); line(17f,16f,22f,21f); line(22f,16f,17f,21f) }
                HomeIcon.SCAN -> { line(8f,3f,3f,3f,3f,8f); line(16f,3f,21f,3f,21f,8f); line(3f,16f,3f,21f,8f,21f); line(16f,21f,21f,21f,21f,16f); circle(12f,10f,2.5f); line(8f,17f,9f,15f,15f,15f,16f,17f) }
                HomeIcon.CHEVRON -> line(10f,6f,16f,12f,10f,18f)
                HomeIcon.CHECK -> line(6f,12f,10f,16f,18f,7f)
                HomeIcon.PIN -> { val p=Path().apply { moveTo(12f,22f); cubicTo(-7f,2f,30f,-5f,19f,14f); close() }; drawPath(p,color,style=Stroke(1.5f)); circle(12f,9f,2.5f) }
                HomeIcon.CLOCK -> { circle(12f,12f,9f); line(12f,6f,12f,12f,16f,14f) }
                HomeIcon.BACK -> { line(13f,5f,6f,12f,13f,19f); line(6f,12f,21f,12f) }
                HomeIcon.BOOKMARK -> line(6f,21f,6f,3f,18f,3f,18f,21f,12f,17f,6f,21f)
                HomeIcon.SHARE -> { circle(18f,4f,2.5f); circle(5f,12f,2.5f); circle(18f,20f,2.5f); line(7f,11f,16f,5f); line(7f,13f,16f,19f) }
                HomeIcon.PHOTO -> { line(3f,5f,21f,5f,21f,20f,3f,20f,3f,5f); circle(8f,10f,2f); line(4f,19f,10f,13f,14f,16f,18f,11f,21f,14f) }
                HomeIcon.CALENDAR -> { line(4f,5f,20f,5f,20f,21f,4f,21f,4f,5f); line(8f,2f,8f,8f); line(16f,2f,16f,8f); line(4f,10f,20f,10f); line(8f,15f,10f,15f) }
                HomeIcon.DESK -> { line(2f,8f,12f,3f,22f,8f,2f,8f); line(3f,21f,21f,21f); line(5f,11f,5f,18f); line(12f,11f,12f,18f); line(19f,11f,19f,18f); line(2f,18f,22f,18f) }
            }
        }
    }
}
