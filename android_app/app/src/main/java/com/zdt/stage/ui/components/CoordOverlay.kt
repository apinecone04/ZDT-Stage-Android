package com.zdt.stage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 3D 视图左上角悬浮高对比度坐标读数（100% 对齐 Python 版本 _CoordOverlay）：
 * - 实时显示 X/Y/Z 当前 mm 读数及行程上限
 * - 在线/离线彩色标签提示
 */
@Composable
fun CoordOverlay(
    online: Boolean,
    x: Double,
    y: Double,
    z: Double,
    travelX: Double = 600.0,
    travelY: Double = 400.0,
    travelZ: Double = 300.0,
    modifier: Modifier = Modifier
) {
    val tagColor = if (online) Color(0xFFFFE066) else Color(0xFF888888)
    val tagText = if (online) "在线" else "离线"

    Column(
        modifier = modifier
            .background(Color(0xCC11151C), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "XYZ 末端点 [$tagText]",
            color = tagColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = String.format(Locale.US, "X: %7.2f mm / %.0f", x, travelX),
            color = Color(0xFFFF7A4A),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = String.format(Locale.US, "Y: %7.2f mm / %.0f", y, travelY),
            color = Color(0xFF5AD0FF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = String.format(Locale.US, "Z: %7.2f mm / %.0f", z, travelZ),
            color = Color(0xFF9AFF5A),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
