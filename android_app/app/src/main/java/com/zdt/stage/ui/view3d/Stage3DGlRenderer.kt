package com.zdt.stage.ui.view3d

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenGL ES 2.0 滑台三维空间监视器渲染器（100% 对齐 Python pyqtgraph.opengl 逻辑）：
 * - 行程线框立体几何 (600x400x300 mm)
 * - 地板网格
 * - 实时末端橙色圆点 + 垂直吊线 + 地板投影点
 * - 空间连续移动轨迹黄线（去抖、最大 3000 点环形缓冲）
 * - 手势滑动旋转与捏合缩放
 */
class Stage3DGlRenderer(
    private val travelX: Float = 600.0f,
    private val travelY: Float = 400.0f,
    private val travelZ: Float = 300.0f
) : GLSurfaceView.Renderer {

    companion object {
        const val MAX_TRAIL = 3000

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            uniform float uPointSize;
            attribute vec4 vPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                gl_PointSize = uPointSize;
                vColor = aColor;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
    }

    // 相机参数
    private var azimuth = 40.0f
    private var elevation = 24.0f
    private var distance = maxOf(travelX, travelY, travelZ) * 2.6f

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var program = 0
    private var uMVPMatrixHandle = 0
    private var uPointSizeHandle = 0
    private var vPositionHandle = 0
    private var aColorHandle = 0

    // 顶点缓冲区
    private lateinit var boxVertexBuffer: FloatBuffer
    private lateinit var gridVertexBuffer: FloatBuffer
    private val dynamicVertexBuffer = ByteBuffer.allocateDirect(128 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val trailVertices = FloatArray(MAX_TRAIL * 3)
    private var trailCount = 0
    private val trailBuffer = ByteBuffer.allocateDirect(MAX_TRAIL * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // 实时位置
    @Volatile
    private var currentX = 0.0f
    @Volatile
    private var currentY = 0.0f
    @Volatile
    private var currentZ = 0.0f // 机械 mm

    private val centerX = travelX / 2.0f
    private val centerY = travelY / 2.0f
    private val centerZ = travelZ / 2.0f

    private val thresholdSq = (travelX * 0.002f) * (travelX * 0.002f)

    init {
        initBoxBuffers()
        initGridBuffers()
    }

    private fun initBoxBuffers() {
        // 12 条边，24 个顶点
        val edges = floatArrayOf(
            0f, 0f, 0f, travelX, 0f, 0f,
            travelX, 0f, 0f, travelX, travelY, 0f,
            travelX, travelY, 0f, 0f, travelY, 0f,
            0f, travelY, 0f, 0f, 0f, 0f,
            0f, 0f, travelZ, travelX, 0f, travelZ,
            travelX, 0f, travelZ, travelX, travelY, travelZ,
            travelX, travelY, travelZ, 0f, travelY, travelZ,
            0f, travelY, travelZ, 0f, 0f, travelZ,
            0f, 0f, 0f, 0f, 0f, travelZ,
            travelX, 0f, 0f, travelX, 0f, travelZ,
            travelX, travelY, 0f, travelX, travelY, travelZ,
            0f, travelY, 0f, 0f, travelY, travelZ
        )
        boxVertexBuffer = ByteBuffer.allocateDirect(edges.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(edges)
                position(0)
            }
    }

    private fun initGridBuffers() {
        val lines = mutableListOf<Float>()
        val stepX = travelX / 10f
        val stepY = travelY / 10f
        for (i in 0..10) {
            val x = i * stepX
            lines.addAll(listOf(x, 0f, 0f, x, travelY, 0f))
        }
        for (j in 0..10) {
            val y = j * stepY
            lines.addAll(listOf(0f, y, 0f, travelX, y, 0f))
        }
        val gridArray = lines.toFloatArray()
        gridVertexBuffer = ByteBuffer.allocateDirect(gridArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(gridArray)
                position(0)
            }
    }

    fun updatePosition(x: Float, y: Float, z: Float) {
        currentX = x
        currentY = y
        currentZ = z

        // 垂直 Z 轴：0mm 为顶部，z_box = travelZ - z
        val zBox = travelZ - z

        // 轨迹去抖与添加
        synchronized(this) {
            var needAdd = false
            if (trailCount == 0) {
                needAdd = true
            } else {
                val lastIdx = (trailCount - 1) * 3
                val lx = trailVertices[lastIdx]
                val ly = trailVertices[lastIdx + 1]
                val lz = trailVertices[lastIdx + 2]
                val distSq = (x - lx) * (x - lx) + (y - ly) * (y - ly) + (zBox - lz) * (zBox - lz)
                if (distSq > thresholdSq) {
                    needAdd = true
                }
            }

            if (needAdd) {
                if (trailCount >= MAX_TRAIL) {
                    System.arraycopy(trailVertices, 3, trailVertices, 0, (MAX_TRAIL - 1) * 3)
                    trailCount--
                }
                val idx = trailCount * 3
                trailVertices[idx] = x
                trailVertices[idx + 1] = y
                trailVertices[idx + 2] = zBox
                trailCount++

                trailBuffer.clear()
                trailBuffer.put(trailVertices, 0, trailCount * 3)
                trailBuffer.position(0)
            }
        }
    }

    fun clearTrail() {
        synchronized(this) {
            trailCount = 0
            trailBuffer.clear()
        }
    }

    fun resetView() {
        elevation = 24.0f
        azimuth = 40.0f
        distance = maxOf(travelX, travelY, travelZ) * 2.6f
    }

    fun onDrag(dx: Float, dy: Float) {
        azimuth = (azimuth + dx * 0.4f) % 360f
        elevation = (elevation - dy * 0.4f).coerceIn(5.0f, 85.0f)
    }

    fun onScale(factor: Float) {
        distance = (distance / factor).coerceIn(100f, 5000f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.063f, 0.078f, 0.11f, 1.0f) // #10141c
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uPointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
        vPositionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        aColorHandle = GLES20.glGetAttribLocation(program, "aColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45.0f, ratio, 10.0f, 10000.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 计算相机视口
        val radElev = Math.toRadians(elevation.toDouble())
        val radAzim = Math.toRadians(azimuth.toDouble())

        val eyeX = (centerX + distance * cos(radElev) * sin(radAzim)).toFloat()
        val eyeY = (centerY - distance * cos(radElev) * cos(radAzim)).toFloat()
        val eyeZ = (centerZ + distance * sin(radElev)).toFloat()

        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, centerX, centerY, centerZ, 0f, 0f, 1f)
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, vPMatrix, 0)
        GLES20.glEnableVertexAttribArray(vPositionHandle)

        // 1. 绘制地板网格 (深灰蓝)
        GLES20.glVertexAttrib4f(aColorHandle, 0.27f, 0.35f, 0.47f, 0.47f)
        GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, gridVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, (11 + 11) * 2)

        // 2. 绘制行程线框 (浅亮蓝)
        GLES20.glVertexAttrib4f(aColorHandle, 0.35f, 0.63f, 1.0f, 0.86f)
        GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, boxVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)

        val x = currentX
        val y = currentY
        val zBox = travelZ - currentZ

        // 3. 绘制连续历史轨迹 (金黄色 GL_LINE_STRIP)
        synchronized(this) {
            if (trailCount > 1) {
                GLES20.glVertexAttrib4f(aColorHandle, 1.0f, 0.78f, 0.24f, 0.8f)
                GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, trailBuffer)
                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, trailCount)
            }
        }

        // 4. 绘制垂直吊线 (末端点 -> 地板 (x, y, 0))
        dynamicVertexBuffer.clear()
        dynamicVertexBuffer.put(floatArrayOf(x, y, 0f, x, y, zBox))
        dynamicVertexBuffer.position(0)
        GLES20.glVertexAttrib4f(aColorHandle, 1.0f, 0.47f, 0.24f, 0.8f)
        GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, dynamicVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)

        // 5. 绘制地板投影点
        dynamicVertexBuffer.clear()
        dynamicVertexBuffer.put(floatArrayOf(x, y, 0f))
        dynamicVertexBuffer.position(0)
        GLES20.glUniform1f(uPointSizeHandle, 14.0f)
        GLES20.glVertexAttrib4f(aColorHandle, 0.6f, 0.6f, 0.6f, 0.9f)
        GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, dynamicVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        // 6. 绘制末端执行器点 (橙红大点)
        dynamicVertexBuffer.clear()
        dynamicVertexBuffer.put(floatArrayOf(x, y, zBox))
        dynamicVertexBuffer.position(0)
        GLES20.glUniform1f(uPointSizeHandle, 28.0f)
        GLES20.glVertexAttrib4f(aColorHandle, 1.0f, 0.45f, 0.2f, 1.0f)
        GLES20.glVertexAttribPointer(vPositionHandle, 3, GLES20.GL_FLOAT, false, 12, dynamicVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        GLES20.glDisableVertexAttribArray(vPositionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
