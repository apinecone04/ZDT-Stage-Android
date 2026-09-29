package com.zdt.stage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.ui.theme.LedOff

/**
 * 工业 LED 状态指示芯片：圆点指示灯 + 状态标签
 */
@Composable
fun LedChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val dotColor = if (active) activeColor else LedOff
    val textColor = if (active) Color(0xFFE0E0E0) else Color(0xFF6E7887)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(Color(0xFF1E232B), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF333B48), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
                .border(1.dp, if (active) dotColor.copy(alpha = 0.5f) else Color(0xFF444444), CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = textColor
        )
    }
}
