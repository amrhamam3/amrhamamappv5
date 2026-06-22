package com.amr3d.preview

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for displaying an STL mesh with simple directional + ambient lighting,
 * touch-based rotation, pinch-zoom, and pan — plus support for drawing measurement markers/lines.
 */
class STLRenderer : GLSurfaceView.Renderer {

    // --- Shaders ---
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        varying vec3 fNormal;
        varying vec3 fPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal = normalize((uNormalMatrix * vec4(vNormal, 0.0)).xyz);
            fPosition = vPosition.xyz;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec3 fNormal;
        varying vec3 fPosition;
        uniform vec4 uColor;
        void main() {
            vec3 lightDir = normalize(vec3(0.5, 0.7, 1.0));
            float diff = max(dot(fNormal, lightDir), 0.0);
            vec3 ambient = uColor.rgb * 0.35;
            vec3 diffuse = uColor.rgb * diff * 0.8;
            float rim = max(dot(fNormal, vec3(-0.3, -0.3, -0.6)), 0.0) * 0.15;
            vec3 result = ambient + diffuse + uColor.rgb * rim;
            gl_FragColor = vec4(result, uColor.a);
        }
    """.trimIndent()

    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = 14.0;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    private var meshProgram = 0
    private var lineProgram = 0

    private var vertexBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var wireframeBuffer: FloatBuffer? = null
    private var wireframeVertexCount = 0
    private var vertexCountToDraw = 0

    @Volatile var wireframeMode = false

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    @Volatile var rotationX = -25f
    @Volatile var rotationY = 35f
    @Volatile var scaleFactor = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    private var modelCenter = floatArrayOf(0f, 0f, 0f)
    private var modelRadius = 1f

    private val measurementPoints = ArrayList<FloatArray>()
    var onModelTapped: ((screenX: Float, screenY: Float) -> Unit)? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    var modelColor = floatArrayOf(0.45f, 0.75f, 0.95f, 1.0f)

    fun setModelColor(r: Float, g: Float, b: Float) {
        modelColor = floatArrayOf(r, g, b, 1.0f)
    }

    // Expose the matrices/dimensions used in the last drawn frame so the Activity
    // can perform ray-picking (screen tap -> 3D surface point) using the same camera state.
    fun getCurrentModelMatrix(): FloatArray = modelMatrix.copyOf()
    fun getCurrentViewMatrix(): FloatArray = viewMatrix.copyOf()
    fun getCurrentProjectionMatrix(): FloatArray = projectionMatrix.copyOf()
    fun getSurfaceWidth(): Int = surfaceWidth
    fun getSurfaceHeight(): Int = surfaceHeight

    private var currentModel: STLModel? = null
    fun getModel(): STLModel? = currentModel

    fun setModel(model: STLModel) {
        currentModel = model
        val verts = model.vertices
        val norms = model.normals

        val vbb = ByteBuffer.allocateDirect(verts.size * 4)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer().apply {
            put(verts)
            position(0)
        }

        val nbb = ByteBuffer.allocateDirect(norms.size * 4)
        nbb.order(ByteOrder.nativeOrder())
        normalBuffer = nbb.asFloatBuffer().apply {
            put(norms)
            position(0)
        }

        vertexCountToDraw = verts.size / 3

        // Build a line-segment buffer for wireframe mode: 3 edges per triangle
        // (v0-v1, v1-v2, v2-v0), so we can draw with GL_LINES regardless of GPU/driver
        // support for polygon-mode wireframe (which OpenGL ES doesn't expose at all).
        val triCount = vertexCountToDraw / 3
        val wireData = FloatArray(triCount * 6 * 3) // 6 vertices (3 edges x 2 endpoints) * xyz
        var wIdx = 0
        var vSrc = 0
        for (t in 0 until triCount) {
            val ax = verts[vSrc]; val ay = verts[vSrc + 1]; val az = verts[vSrc + 2]
            val bx = verts[vSrc + 3]; val by = verts[vSrc + 4]; val bz = verts[vSrc + 5]
            val cx = verts[vSrc + 6]; val cy = verts[vSrc + 7]; val cz = verts[vSrc + 8]

            // edge a-b
            wireData[wIdx++] = ax; wireData[wIdx++] = ay; wireData[wIdx++] = az
            wireData[wIdx++] = bx; wireData[wIdx++] = by; wireData[wIdx++] = bz
            // edge b-c
            wireData[wIdx++] = bx; wireData[wIdx++] = by; wireData[wIdx++] = bz
            wireData[wIdx++] = cx; wireData[wIdx++] = cy; wireData[wIdx++] = cz
            // edge c-a
            wireData[wIdx++] = cx; wireData[wIdx++] = cy; wireData[wIdx++] = cz
            wireData[wIdx++] = ax; wireData[wIdx++] = ay; wireData[wIdx++] = az

            vSrc += 9
        }
        val wbb = ByteBuffer.allocateDirect(wireData.size * 4)
        wbb.order(ByteOrder.nativeOrder())
        wireframeBuffer = wbb.asFloatBuffer().apply {
            put(wireData)
            position(0)
        }
        wireframeVertexCount = wireData.size / 3

        modelCenter = floatArrayOf(
            (model.minBounds[0] + model.maxBounds[0]) / 2f,
            (model.minBounds[1] + model.maxBounds[1]) / 2f,
            (model.minBounds[2] + model.maxBounds[2]) / 2f
        )
        val dx = model.maxBounds[0] - model.minBounds[0]
        val dy = model.maxBounds[1] - model.minBounds[1]
        val dz = model.maxBounds[2] - model.minBounds[2]
        modelRadius = (maxOf(dx, dy, dz) / 2f).let { if (it <= 0f) 1f else it }

        rotationX = -25f
        rotationY = 35f
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        measurementPoints.clear()
        updateProjection()
    }

    fun addMeasurementPoint(point: FloatArray) {
        measurementPoints.add(point)
        if (measurementPoints.size > 2) {
            measurementPoints.removeAt(0)
        }
    }

    fun clearMeasurementPoints() {
        measurementPoints.clear()
    }

    fun getMeasurementPoints(): List<FloatArray> = measurementPoints

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        updateClearColor()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        meshProgram = createProgram(vertexShaderCode, fragmentShaderCode)
        lineProgram = createProgram(lineVertexShaderCode, lineFragmentShaderCode)
    }

    // Background color, changeable by the user
    var bgColor = floatArrayOf(0.10f, 0.11f, 0.13f)

    fun setBackgroundColor(r: Float, g: Float, b: Float) {
        bgColor = floatArrayOf(r, g, b)
        updateClearColor()
    }

    private fun updateClearColor() {
        GLES20.glClearColor(bgColor[0], bgColor[1], bgColor[2], 1f)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateProjection()
    }

    /**
     * Uses an Orthographic projection so the model never looks distorted when zooming.
     * In perspective mode, zooming by moving the camera closer widens the apparent FOV
     * and makes the model look "fish-eyed". With ortho, only the ortho scale changes,
     * which is exactly what CAD/3D-print tools do.
     */
    private fun updateProjection() {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val safeRadius = if (modelRadius > 0f) modelRadius else 1f

        // Orthographic half-extents scale with zoom (scaleFactor) so pinching in/out
        // scales the visible area without changing the projection angle.
        val orthoHalf = safeRadius * 1.4f / scaleFactor
        val near = -safeRadius * 10f
        val far  =  safeRadius * 10f

        Matrix.orthoM(projectionMatrix, 0,
            -orthoHalf * ratio,  orthoHalf * ratio,
            -orthoHalf,          orthoHalf,
            near, far)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        updateClearColor()

        if (vertexBuffer == null || vertexCountToDraw == 0) return

        // Rebuild ortho projection every frame so scaleFactor (changed by pinch in GLViewerView)
        // is always reflected immediately without needing a surface size change.
        updateProjection()

        // With ortho projection the camera stays at a fixed distance; zoom is handled
        // entirely by the ortho extents in updateProjection() which is called every time
        // scaleFactor changes (via setModel or from GLViewerView on pinch).
        val camDistance = (if (modelRadius > 0f) modelRadius else 1f) * 5f
        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 0f, camDistance,
            0f, 0f, 0f,
            0f, 1f, 0f
        )

        // Model matrix: rotate only (no translation for zoom — that's the ortho extents)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, -modelCenter[0], -modelCenter[1], -modelCenter[2])

        // Pan: translate in view space after applying the view matrix
        val panScale = (if (modelRadius > 0f) modelRadius else 1f) * 1.4f / scaleFactor
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, panX * panScale, panY * panScale, -camDistance)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        Matrix.invertM(normalMatrix, 0, modelMatrix, 0)
        Matrix.transposeM(normalMatrix, 0, normalMatrix, 0)

        drawMesh()
        if (measurementPoints.isNotEmpty()) {
            drawMeasurementOverlay()
        }
    }

    private fun drawMesh() {
        if (wireframeMode) {
            drawWireframe()
        } else {
            drawSolidMesh()
        }
    }

    /**
     * Captures the current OpenGL framebuffer as a Bitmap. Must be called from the GL
     * thread (e.g. via GLSurfaceView.queueEvent), since it reads pixels directly from
     * the active GL context.
     */
    fun captureFrame(width: Int, height: Int): android.graphics.Bitmap {
        val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // glReadPixels returns the image flipped vertically compared to Bitmap's
        // top-left origin, so flip it back.
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    private fun drawWireframe() {
        val buffer = wireframeBuffer ?: return
        GLES20.glUseProgram(lineProgram)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, modelColor[0], modelColor[1], modelColor[2], 1f)
        GLES20.glLineWidth(1.5f)

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, wireframeVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawSolidMesh() {
        GLES20.glUseProgram(meshProgram)

        val positionHandle = GLES20.glGetAttribLocation(meshProgram, "vPosition")
        val normalHandle = GLES20.glGetAttribLocation(meshProgram, "vNormal")
        val mvpHandle = GLES20.glGetUniformLocation(meshProgram, "uMVPMatrix")
        val normalMatrixHandle = GLES20.glGetUniformLocation(meshProgram, "uNormalMatrix")
        val colorHandle = GLES20.glGetUniformLocation(meshProgram, "uColor")

        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer!!.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(normalHandle)
        normalBuffer!!.position(0)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normalMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, modelColor, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCountToDraw)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private fun drawMeasurementOverlay() {
        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val flat = FloatArray(measurementPoints.size * 3)
        measurementPoints.forEachIndexed { i, p ->
            flat[i * 3] = p[0]; flat[i * 3 + 1] = p[1]; flat[i * 3 + 2] = p[2]
        }
        val bb = ByteBuffer.allocateDirect(flat.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer().apply { put(flat); position(0) }

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, measurementPoints.size)

        if (measurementPoints.size == 2) {
            GLES20.glUniform4f(colorHandle, 1f, 0.85f, 0.2f, 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
