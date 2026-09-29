package com.zdt.stage.ui.view3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 2D 三视图投影回退引擎（100% 对齐 Python View3DFallback）：
 * - XY 俯视图 (Top)
 * - XZ 正视图 (Front, Z 轴向下增大)
 * - YZ 侧视图 (Side, Z 轴向下增大)
 */
@Composable
fun Stage3DCanvasFallback(
    currentX: Double,
    currentY: Double,
    currentZ: Double,
    travelX: Double = 600.0,
    travelY: Double = 400.0,
    travelZ: Double = 300.0,
    trail: List<Triple<Double, Double, Double>> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF10141C))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 俯视 XY (左)
            ProjectionSubView(
                title = "俯视 XY",
                val1 = currentX,
                val2 = currentY,
                max1 = travelX,
                max2 = travelY,
                trailPts = trail.map { Offset(it.first.toFloat(), it.second.toFloat()) },
                invertY = false,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // 2. 正视 XZ (中)
            ProjectionSubView(
                title = "正视 XZ (Z向下)",
                val1 = currentX,
                val2 = currentZ,
                max1 = travelX,
                max2 = travelZ,
                trailPts = trail.map { Offset(it.first.toFloat(), it.third.toFloat()) },
                invertY = false, // 视觉上直接画向下
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            // 3. 侧视 YZ (右)
            ProjectionSubView(
                title = "侧视 YZ (Z向下)",
                val1 = currentY,
                val2 = currentZ,
                max1 = travelY,
                max2 = travelZ,
                trailPts = trail.map { Offset(it.second.toFloat(), it.third.toFloat()) },
                invertY = false,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ProjectionSubView(
    title: String,
    val1: Double,
    val2: Double,
    max1: Double,
    max2: Double,
    trailPts: List<Offset>,
    invertY: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF181D26))
            .padding(6.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFFD0D0D0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart)
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp, bottom = 6.dp, start = 6.dp, end = 6.dp)
        ) {
            val pad = 12f
            val drawW = size.width - pad * 2
            val drawH = size.height - pad * 2
            if (drawW <= 0 || drawH <= 0) return@Canvas

            val scaleX = drawW / max1.toFloat()
            val scaleY = drawH / max2.toFloat()
            val scale = minOf(scaleX, scaleY)

            val boxW = max1.toFloat() * scale
            val boxH = max2.toFloat() * scale
            val left = pad + (drawW - boxW) / 2f
            val top = pad + (drawH - boxH) / 2f

            // 绘制外线框
            drawRect(
                color = Color(0xFF50A0FF),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(width = 2f)
            )

            // 绘制轨迹
            if (trailPts.size > 1) {
                val path = Path()
                trailPts.forEachIndexed { i, pt ->
                    val px = left + pt.x * scale
                    val py = top + pt.y * scale
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color = Color(0xFFFFC800), style = Stroke(width = 2f))
            }

            // 绘制当前末端点
            val curPx = left + val1.toFloat() * scale
            val curPy = top + val2.toFloat() * scale
            drawCircle(
                color = Color(0xFFFF783C),
                radius = 7f,
                center = Offset(curPx, curPy)
            )
        }
    }
}
