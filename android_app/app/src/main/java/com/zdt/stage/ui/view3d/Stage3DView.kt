package com.zdt.stage.ui.view3d

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zdt.stage.ui.components.CoordOverlay
import kotlin.math.abs

/**
 * 3D 空间监视器主容器（100% 对齐 Python View3DOpenGL）：
 * - 集成 OpenGL ES 2.0 渲染视图
 * - 左上角坐标读数浮层
 * - 工具按钮条：清除轨迹、复位视角、使能全部、失能全部（带安全提示）、3D/2D 模式切换
 */
@Composable
fun Stage3DView(
    online: Boolean,
    currentX: Double,
    currentY: Double,
    currentZ: Double,
    travelX: Double = 600.0,
    travelY: Double = 400.0,
    travelZ: Double = 300.0,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDisableConfirmDialog by remember { mutableStateOf(false) }
    var use2DFallback by remember { mutableStateOf(false) }

    val renderer = remember(travelX, travelY, travelZ) {
        Stage3DGlRenderer(travelX.toFloat(), travelY.toFloat(), travelZ.toFloat())
    }

    // 状态更新联动
    LaunchedEffect(currentX, currentY, currentZ) {
        if (online) {
            renderer.updatePosition(currentX.toFloat(), currentY.toFloat(), currentZ.toFloat())
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF10141C))
            .padding(2.dp)
    ) {
        // 工具按键栏（与 PC 版一致：清除轨迹、复位视角、使能全部、失能全部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { renderer.clearTrail() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("清除轨迹", fontSize = 12.sp, color = Color(0xFFE0E0E0))
            }

            Button(
                onClick = { renderer.resetView() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("复位视角", fontSize = 12.sp, color = Color(0xFFE0E0E0))
            }

            Button(
                onClick = onEnableAll,
                enabled = online,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2F6D2A),
                    disabledContainerColor = Color(0xFF222831)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("使能全部", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = { showDisableConfirmDialog = true },
                enabled = online,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5A2020),
                    disabledContainerColor = Color(0xFF222831)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("失能全部", fontSize = 12.sp, color = Color(0xFFFF8888))
            }

            // 3D / 2D 视图切换小按钮
            OutlinedButton(
                onClick = { use2DFallback = !use2DFallback },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(if (use2DFallback) "3D 视口" else "2D 三视图", fontSize = 11.sp, color = Color(0xFF5AD0FF))
            }
        }

        // 视口区域与浮层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (use2DFallback) {
                Stage3DCanvasFallback(
                    currentX = currentX,
                    currentY = currentY,
                    currentZ = currentZ,
                    travelX = travelX,
                    travelY = travelY,
                    travelZ = travelZ,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        createGlSurfaceView(ctx, renderer)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 左上角悬浮高对比度坐标读数
            CoordOverlay(
                online = online,
                x = currentX,
                y = currentY,
                z = currentZ,
                travelX = travelX,
                travelY = travelY,
                travelZ = travelZ,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
    }

    // Z 轴防重力下坠安全确认弹窗
    if (showDisableConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmDialog = false },
            title = { Text("⚠️ 重力坠落风险警告") },
            text = {
                Text("竖直 Z 轴去使能（断电）后将失去电磁保持力矩，滑块及负载可能因自身重力急速下坠撞毁工件！\n\n是否确认断电去使能全部电机？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisableConfirmDialog = false
                        onDisableAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB02020))
                ) {
                    Text("确认断电")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisableConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createGlSurfaceView(context: Context, renderer: Stage3DGlRenderer): GLSurfaceView {
    val glView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    var lastX = 0f
    var lastY = 0f

    val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.onScale(detector.scaleFactor)
            return true
        }
    })

    glView.setOnTouchListener { _, event ->
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.onDrag(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        true
    }
    return glView
}
