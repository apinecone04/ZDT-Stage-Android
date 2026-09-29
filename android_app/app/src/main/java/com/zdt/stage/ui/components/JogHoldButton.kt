package com.zdt.stage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.ui.theme.ZdtButton
import com.zdt.stage.ui.theme.ZdtButtonPressed

/**
 * 高灵敏点动按钮（100% 对齐 PyQt 的 pressed / released 机制）：
 * - 按下即发运动指令
 * - 松手或手指滑出即发停止指令
 */
@Composable
fun JogHoldButton(
    text: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val bgColor = when {
        !enabled -> Color(0xFF222831)
        isPressed -> ZdtButtonPressed
        else -> ZdtButton
    }
    val textColor = when {
        !enabled -> Color(0xFF5A5A5A)
        isPressed -> Color(0xFFFFE066)
        else -> Color(0xFFE0E0E0)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, if (isPressed) Color(0xFFFFE066) else Color(0xFF445060), RoundedCornerShape(6.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    onStart()

                    val upOrCancel = waitForUpOrCancellation()
                    isPressed = false
                    onStop()
                }
            }
            .padding(vertical = 14.dp, horizontal = 16.dp)
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
