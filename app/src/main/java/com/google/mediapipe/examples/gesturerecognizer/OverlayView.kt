package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.gesturerecognizer.fragment.CameraFragment
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: GestureRecognizerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var thumbUp = false
    private var isNextImageCalled = false

    init {
        initPaints()
    }


    fun setThumbUp(value: Boolean) {
        thumbUp = value
        if (thumbUp) {
            isNextImageCalled = false
        }
    }

    fun setNextColor(color: Int) {
        linePaint.color = color
        pointPaint.color = color
        invalidate()
    }
    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color = ContextCompat.getColor(context!!, R.color.htwGreen)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = ContextCompat.getColor(context!!, R.color.htwBlue)
        pointPaint.strokeWidth = LANDMARK_POINT_WIDTH
        pointPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.let { gestureRecognizerResult ->
            for (landmark in gestureRecognizerResult.landmarks()) {
                for (i in 0 until landmark.size) {
                    val normalizedLandmark = landmark.get(i)
                    val x = normalizedLandmark.x() * imageWidth * scaleFactor
                    val y = normalizedLandmark.y() * imageHeight * scaleFactor
                    var radius = LANDMARK_POINT_RADIUS //default radius

                    // Check if it's the 4th landmark, and change the color for it
                    if (i == 4) {
                        pointPaint.color = ContextCompat.getColor(context!!, R.color.htwOrange)
                        radius = LARGE_LANDMARK_POINT_RADIUS
                    } else {
                        // Set the color for all other landmarks as green
                        pointPaint.color = ContextCompat.getColor(context!!, R.color.htwGreen)
                    }

                    canvas.drawCircle(x, y, radius, pointPaint)
                }
                // draw-Methode (drawRect o.ä.) mit if Bedingung, canvas.draw...
                if(thumbUp) {
                    //canvas.drawRect(200.0f, 200.0f, 2000.0f, 2000.0f, pointPaint)
                    isNextImageCalled = true
                }

                HandLandmarker.HAND_CONNECTIONS.forEach {
                    val startX = gestureRecognizerResult.landmarks().get(0).get(it!!.start()).x() * imageWidth * scaleFactor
                    val startY = gestureRecognizerResult.landmarks().get(0).get(it.start()).y() * imageHeight * scaleFactor
                    val endX = gestureRecognizerResult.landmarks().get(0).get(it.end()).x() * imageWidth * scaleFactor
                    val endY = gestureRecognizerResult.landmarks().get(0).get(it.end()).y() * imageHeight * scaleFactor
                    canvas.drawLine(startX, startY, endX, endY, linePaint)
                }
            }
        }
    }

    fun setResults(
        gestureRecognizerResult: GestureRecognizerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = gestureRecognizerResult

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE, RunningMode.VIDEO -> min(width * 1f / imageWidth, height * 1f / imageHeight)
            RunningMode.LIVE_STREAM -> max(width * 1f / imageWidth, height * 1f / imageHeight)
        }
        invalidate()
    }

    fun setNextColor(red: Any) {

    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 6F
        private const val LANDMARK_POINT_WIDTH = 6F
        private const val LANDMARK_POINT_RADIUS = 6F
        private const val LARGE_LANDMARK_POINT_RADIUS = 10F
    }

    interface OverlayViewListener{
        fun nextImage()
    }
}