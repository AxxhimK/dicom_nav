package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: GestureRecognizerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var thumbUp = false
    private var isNextImageCalled = false

    private var lineColor: Int = 0
    private var pointColor: Int = 0
    private var htwOrangeColor: Int = 0
    private var htwBlueColor: Int = 0
    private var htwGreenColor: Int = 0

    init {
        initColors()
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initColors() {
        htwGreenColor = ContextCompat.getColor(context!!, R.color.htwGreen)
        htwBlueColor = ContextCompat.getColor(context!!, R.color.htwBlue)
        htwOrangeColor = ContextCompat.getColor(context!!, R.color.htwOrange)
    }
    private fun initPaints() {
        lineColor = htwGreenColor
        pointColor = htwBlueColor

        linePaint.apply {
            color = lineColor
            strokeWidth = LANDMARK_STROKE_WIDTH
            style = Paint.Style.STROKE
        }

        pointPaint.apply {
            color = pointColor
            strokeWidth = LANDMARK_POINT_WIDTH
            style = Paint.Style.FILL
        }
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

                    // Verändere Farbe bei Erkennung von Landmarke 4
                    if (i == 4) {
                        pointPaint.color = htwOrangeColor
                        radius = LARGE_LANDMARK_POINT_RADIUS
                    } else if (i == 8) {
                        pointPaint.color = htwBlueColor
                        radius = LARGE_LANDMARK_POINT_RADIUS
                    } else {
                        // Set the color for all other landmarks as green
                        pointPaint.color = htwGreenColor
                    }

                    canvas.drawCircle(x, y, radius, pointPaint)
                }
                // draw-Methode (drawRect o.ä.) mit if Bedingung, canvas.draw...
                if (thumbUp) {
                    //canvas.drawRect(200.0f, 200.0f, 2000.0f, 2000.0f, pointPaint)
                    isNextImageCalled = true
                }

                HandLandmarker.HAND_CONNECTIONS.forEach {
                    val startX = gestureRecognizerResult.landmarks().get(0).get(it!!.start())
                        .x() * imageWidth * scaleFactor
                    val startY = gestureRecognizerResult.landmarks().get(0).get(it.start())
                        .y() * imageHeight * scaleFactor
                    val endX = gestureRecognizerResult.landmarks().get(0).get(it.end())
                        .x() * imageWidth * scaleFactor
                    val endY = gestureRecognizerResult.landmarks().get(0).get(it.end())
                        .y() * imageHeight * scaleFactor
                    canvas.drawLine(startX, startY, endX, endY, linePaint)
                }
            }
        }
    }

    fun calculateDistance(): Float {
        results?.let { gestureRecognizerResult ->
            for (landmark in gestureRecognizerResult.landmarks()) {
                //for (i in 0 until landmark.size) {
                    val landmark4 = landmark[4]
                    val landmark8 = landmark[8]
                    val x1 = landmark4.x() * imageWidth * scaleFactor
                    val y1 = landmark4.y() * imageHeight * scaleFactor
                    val x2 = landmark8.x() * imageWidth * scaleFactor
                    val y2 = landmark8.y() * imageHeight * scaleFactor

                    return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2)) //berechne euklidischen Abstand von Daumenspitze zu Indexfingerspitze (geradliniger Abstand zw. zwei Punkten)
           //     }
            }
        }
        return 0f //Wenn results null ist
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
                RunningMode.IMAGE, RunningMode.VIDEO -> min(
                    width * 1f / imageWidth,
                    height * 1f / imageHeight
                )

                RunningMode.LIVE_STREAM -> max(
                    width * 1f / imageWidth,
                    height * 1f / imageHeight
                )
            }
            invalidate()

            // Berechnung von Distanz Daumen- zu Indexfingerspitze
            val distance = calculateDistance()
            //Log.d("Distanz", "Distanz: $distance")
            //Log.d("Landmark Liste", "Größe der Liste: ${gestureRecognizerResult.landmarks().size}")
        }

            companion object {
                private const val LANDMARK_STROKE_WIDTH = 4F
                private const val LANDMARK_POINT_WIDTH = 4F
                private const val LANDMARK_POINT_RADIUS = 4F
                private const val LARGE_LANDMARK_POINT_RADIUS = 6F
            }
        }