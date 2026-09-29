package com.zdt.stage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 精密工业数值步进输入框（对标 QDoubleSpinBox 与 QSpinBox）
 */
@Composable
fun NumberStepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    min: Double,
    max: Double,
    step: Double = 1.0,
    decimals: Int = 2,
    suffix: String = "",
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var textValue by remember(value) {
        mutableStateOf(if (decimals == 0) value.toLong().toString() else String.format(Locale.US, "%.${decimals}f", value))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color(0xFF262B34), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF445060), RoundedCornerShape(4.dp))
            .padding(2.dp)
    ) {
        // - 步进按钮
        Button(
            onClick = {
                val next = (value - step).coerceIn(min, max)
                onValueChange(next)
            },
            enabled = enabled && value > min,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(32.dp)
        ) {
            Text("-", fontSize = 16.sp, color = Color.White)
        }

        // 数值输入
        BasicTextField(
            value = textValue,
            onValueChange = { str ->
                textValue = str
                val parsed = str.toDoubleOrNull()
                if (parsed != null) {
                    onValueChange(parsed.coerceIn(min, max))
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(
                color = if (enabled) Color(0xFFE0E0E0) else Color(0xFF666666),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(Color(0xFFFFE066)),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )

        if (suffix.isNotEmpty()) {
            Text(
                text = suffix,
                color = Color(0xFFA0A8B4),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // + 步进按钮
        Button(
            onClick = {
                val next = (value + step).coerceIn(min, max)
                onValueChange(next)
            },
            enabled = enabled && value < max,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(32.dp)
        ) {
            Text("+", fontSize = 16.sp, color = Color.White)
        }
    }
}
