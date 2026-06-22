package com.amr3d.preview

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * GLSurfaceView wrapper that wires up touch gestures:
 * - One finger drag  -> rotate model
 * - Two finger pinch  -> zoom
 * - Two finger drag   -> pan
 * - Single tap        -> forwarded for measurement point picking
 *
 * Pan and pinch-zoom are computed manually (instead of via ScaleGestureDetector) because
 * Android's ScaleGestureDetector reports isInProgress=true as soon as a second finger
 * touches down, even if the user is only panning (fingers moving together, not apart).
 * That made two-finger pan effectively dead. Tracking the average point (for pan) and the
 * distance between fingers (for zoom) independently lets both gestures work together.
 */
class GLViewerView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    val stlRenderer = STLRenderer()

    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var lastTouchCount = 0

    private var moved = false

    var onSingleTap: ((Float, Float) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(stlRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                moved = false
                lastTouchCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastTouchCount = event.pointerCount
                previousX = averageX(event)
                previousY = averageY(event)
                previousSpan = currentSpan(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val curX = averageX(event)
                val curY = averageY(event)
                val dx = curX - previousX
                val dy = curY - previousY

                if (abs(dx) > 1f || abs(dy) > 1f) moved = true

                if (event.pointerCount >= 2) {
                    // Pan: move with the average finger position
                    stlRenderer.panX += dx * 0.0025f
                    stlRenderer.panY -= dy * 0.0025f

                    // Zoom: scale with the change in distance between fingers
                    val curSpan = currentSpan(event)
                    if (previousSpan > 10f && curSpan > 10f) {
                        val spanRatio = curSpan / previousSpan
                        val newScale = stlRenderer.scaleFactor * spanRatio
                        stlRenderer.scaleFactor = newScale.coerceIn(0.2f, 8f)
                    }
                    previousSpan = curSpan
                } else {
                    // One-finger rotate
                    stlRenderer.rotationY += dx * 0.5f
                    stlRenderer.rotationX += dy * 0.5f
                    stlRenderer.rotationX = stlRenderer.rotationX.coerceIn(-90f, 90f)
                }
                previousX = curX
                previousY = curY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastTouchCount = (event.pointerCount - 1).coerceAtLeast(1)
                // Re-anchor to the remaining pointer(s) to avoid a jump on the next move
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && lastTouchCount == 1) {
                    onSingleTap?.invoke(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun currentSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun averageX(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getX(i)
        return total / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount
    }
}
