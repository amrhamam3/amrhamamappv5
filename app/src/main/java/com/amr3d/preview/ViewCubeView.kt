package com.amr3d.preview

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * ViewCube: مكعب ثلاثي الأبعاد مع بوصلة N/S/E/W وسهام دوران
 * مشابه لـ ViewCube الموجود في 3ds Max وبرامج CAD
 */
class ViewCubeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Face(val label: String, val rotX: Float, val rotY: Float) {
        TOP("TOP", -89f, 0f),
        FRONT("FRONT", -10f, 0f),
        BACK("BACK", -10f, 180f),
        LEFT("LEFT", -10f, -90f),
        RIGHT("RIGHT", -10f, 90f),
        BOTTOM("BOTTOM", 89f, 0f),
        ISO("ISO", -25f, 35f)
    }

    var onFaceSelected: ((Face) -> Unit)? = null

    // ألوان المكعب
    private val colorTop = Color.parseColor("#3D4251")
    private val colorFront = Color.parseColor("#2E3340")
    private val colorRight = Color.parseColor("#252830")
    private val colorEdge = Color.parseColor("#FF8A1E")
    private val colorText = Color.WHITE
    private val colorCompass = Color.parseColor("#9CA3AF")
    private val colorCompassN = Color.parseColor("#FF8A1E")
    private val colorArrow = Color.parseColor("#FF8A1E")
    private val colorCubeBg = Color.parseColor("#1A1D24")

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = colorEdge
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = colorCompass
    }
    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorArrow
    }
    private val circleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A1D24CC".toInt(16).let { Color.argb(200, 26, 29, 36) })
    }

    // مناطق الضغط
    private val topRect = RectF()
    private val frontRect = RectF()
    private val rightRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        // الأبعاد الأساسية
        val cubeSize = minOf(w, h) * 0.38f
        val cx = w * 0.5f
        val cy = h * 0.42f
        val compassR = minOf(w, h) * 0.47f
        val arrowLen = minOf(w, h) * 0.06f

        // خلفية دائرية شفافة
        canvas.drawCircle(cx, cy, compassR * 0.98f, circleBgPaint)

        // رسم البوصلة
        drawCompass(canvas, cx, cy, compassR, arrowLen)

        // رسم المكعب
        drawCube(canvas, cx, cy, cubeSize)
    }

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, r: Float, arrowLen: Float) {
        // دائرة البوصلة
        canvas.drawCircle(cx, cy, r * 0.92f, compassPaint)

        // الخطوط الأربعة الرئيسية
        val dirs = listOf(0f to "N", 90f to "E", 180f to "S", 270f to "W")
        for ((angle, label) in dirs) {
            val rad = Math.toRadians(angle.toDouble() - 90)
            val x1 = cx + (r * 0.70f * cos(rad)).toFloat()
            val y1 = cy + (r * 0.70f * sin(rad)).toFloat()
            val x2 = cx + (r * 0.88f * cos(rad)).toFloat()
            val y2 = cy + (r * 0.88f * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, compassPaint)

            val tx = cx + (r * 1.05f * cos(rad)).toFloat()
            val ty = cy + (r * 1.05f * sin(rad)).toFloat()
            compassTextPaint.textSize = r * 0.22f
            compassTextPaint.color = if (label == "N") colorCompassN else colorCompass
            canvas.drawText(label, tx, ty + compassTextPaint.textSize * 0.35f, compassTextPaint)
        }

        // سهام الدوران الأربعة (بين الاتجاهات الرئيسية)
        val arrowAngles = listOf(45f, 135f, 225f, 315f)
        for (angle in arrowAngles) {
            drawRotationArrow(canvas, cx, cy, r * 0.79f, angle, arrowLen)
        }
    }

    private fun drawRotationArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float, size: Float) {
        val rad = Math.toRadians(angle.toDouble() - 90)
        val mx = cx + (r * cos(rad)).toFloat()
        val my = cy + (r * sin(rad)).toFloat()

        // رسم سهم صغير مقوس (مثلث صغير)
        val path = Path()
        val perpRad = rad + Math.PI / 2
        val tip = floatArrayOf(
            mx + (size * 0.6f * cos(rad)).toFloat(),
            my + (size * 0.6f * sin(rad)).toFloat()
        )
        val base1 = floatArrayOf(
            mx + (size * 0.35f * cos(perpRad)).toFloat(),
            my + (size * 0.35f * sin(perpRad)).toFloat()
        )
        val base2 = floatArrayOf(
            mx - (size * 0.35f * cos(perpRad)).toFloat(),
            my - (size * 0.35f * sin(perpRad)).toFloat()
        )
        path.moveTo(tip[0], tip[1])
        path.lineTo(base1[0], base1[1])
        path.lineTo(base2[0], base2[1])
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawCube(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        // نقاط المكعب الإيزومتري (3 وجوه مرئية)
        val h2 = size * 0.5f  // نصف الارتفاع

        // الوجه العلوي (parallelogram)
        val topPath = Path().apply {
            moveTo(cx, cy - size * 0.85f)          // أعلى
            lineTo(cx + size, cy - size * 0.35f)   // يمين
            lineTo(cx, cy + size * 0.15f)           // وسط
            lineTo(cx - size, cy - size * 0.35f)   // يسار
            close()
        }
        topRect.set(cx - size, cy - size * 0.85f, cx + size, cy + size * 0.15f)

        // الوجه الأمامي (يسار أسفل)
        val frontPath = Path().apply {
            moveTo(cx - size, cy - size * 0.35f)
            lineTo(cx, cy + size * 0.15f)
            lineTo(cx, cy + size * 1.15f)
            lineTo(cx - size, cy + size * 0.65f)
            close()
        }
        frontRect.set(cx - size, cy - size * 0.35f, cx, cy + size * 1.15f)

        // الوجه الأيمن
        val rightPath = Path().apply {
            moveTo(cx, cy + size * 0.15f)
            lineTo(cx + size, cy - size * 0.35f)
            lineTo(cx + size, cy + size * 0.65f)
            lineTo(cx, cy + size * 1.15f)
            close()
        }
        rightRect.set(cx, cy - size * 0.35f, cx + size, cy + size * 1.15f)

        // رسم الوجوه
        facePaint.color = colorTop
        canvas.drawPath(topPath, facePaint)

        facePaint.color = colorFront
        canvas.drawPath(frontPath, facePaint)

        facePaint.color = colorRight
        canvas.drawPath(rightPath, facePaint)

        // الحواف
        canvas.drawPath(topPath, edgePaint)
        canvas.drawPath(frontPath, edgePaint)
        canvas.drawPath(rightPath, edgePaint)

        // النصوص
        textPaint.textSize = size * 0.32f

        // TOP
        canvas.drawText("TOP", cx, cy - size * 0.40f, textPaint)

        // FRONT
        textPaint.textSize = size * 0.26f
        canvas.drawText("FRONT", cx - size * 0.42f, cy + size * 0.60f, textPaint)

        // RIGHT
        canvas.drawText("RIGHT", cx + size * 0.42f, cy + size * 0.30f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            when {
                topRect.contains(x, y) -> onFaceSelected?.invoke(Face.TOP)
                frontRect.contains(x, y) -> onFaceSelected?.invoke(Face.FRONT)
                rightRect.contains(x, y) -> onFaceSelected?.invoke(Face.RIGHT)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
