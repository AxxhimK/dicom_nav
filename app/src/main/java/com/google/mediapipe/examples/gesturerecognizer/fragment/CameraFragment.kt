package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract.Directory
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.findFragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.examples.gesturerecognizer.OverlayView
import com.google.mediapipe.examples.gesturerecognizer.fragment.CameraFragment
import com.google.mediapipe.examples.gesturerecognizer.R
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.Float.max
import java.lang.Float.min
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

class CameraFragment : Fragment(),
    GestureRecognizerHelper.GestureRecognizerListener {

    companion object {
        private const val TAG = "Hand gesture recognizer"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var defaultNumResults = 1
    private val gestureRecognizerResultAdapter: GestureRecognizerResultsAdapter by lazy {
        GestureRecognizerResultsAdapter().apply {
            updateAdapterSize(defaultNumResults)
        }
    }
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private var currentImageIndex = 0 //Index der Bilder wird auf 0 gesetzt

    private var lastImageChangeTime: Long = 0 // Stoppuhr fuer Bildwechsel (Long für ms)
    private lateinit var imageView: ImageView
    private lateinit var indexTextView: TextView

    private var imageBitmaps: List<Bitmap> = mutableListOf() //Anstatt Res-IDs --> Neue Liste für Bitmap Objekte
    private lateinit var miniatureImageView: ImageView

    private var currentZoomFactor = 1.0f
    private var isZoomedIn: Boolean = false
    private var lastLandmarkX8 = 0f
    private var lastLandmarkY8 = 0f



    private fun loadBitmapFromResource(): List<Bitmap> {
        val fields = R.raw::class.java.fields //Felder aus Java Klasse
        return fields.mapNotNull { field ->
            val id = field.getInt(null)
            BitmapFactory.decodeResource(resources, id)// Umwandeln von Integer ID in Bitmap
        }
    }

/*
    private fun loadBitmapsFromDevice(context: Context): List<Bitmap> {
        val externalDirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_DOWNLOADS)
        val downloadsDir = externalDirs.firstOrNull() ?: return emptyList()

        val bitmaps = mutableListOf<Bitmap>()
        downloadsDir.listFiles()?.forEach { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            bitmap?.let { bitmaps.add(it) }
        }

        Log.d(TAG, "Number of bitmaps loaded: ${bitmaps.size}")
        return bitmaps
    }*/


    private fun displayBitmap() {
        if (currentImageIndex in imageBitmaps.indices) {
            //Abrufen der aktuellen Bitmap aus imageBitmaps und speichern in originalBitmap
            val originalBitmap = imageBitmaps[currentImageIndex]

            //Aufruf von fun createZoomedBitmap und Übergabe von originalBitmap, setzen in imageView
            imageView.setImageBitmap(originalBitmap)
            updateIndexTextView()
        }
    }

    private fun createZoomedBitmap(originalBitmap: Bitmap, landmarkX0: Float, landmarkY0: Float, zoomFactor: Float, offsetX: Float = 0f, offsetY: Float = 0f): Bitmap {
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height

        // Gezoomter Bereich berechnen (width/height)
        val zoomedWidth = (originalWidth / zoomFactor).toInt()
        val zoomedHeight = (originalHeight / zoomFactor).toInt()

        // Begrenzen von x und y:
        // max -> deltaX,Y darf nicht negativ werden
        // min -> deltaX,Y darf nicht Maximalwert des originalBitmap überschreiten
        // offsetX,Y für panning im gezoomten Bild

        val deltaX = max(0, min(originalWidth - zoomedWidth, (landmarkX0 - (zoomedWidth / 2)+ offsetX).toInt()))
        val deltaY = max(0, min(originalHeight - zoomedHeight, (landmarkY0 - (zoomedHeight / 2) + offsetY).toInt()))

        // Ausschneiden von Zoom-Bereich und hochskalieren auf Originalgröße der Bitmap
        // Filter an für mehr Schärfe
        val zoomedBitmap = Bitmap.createBitmap(originalBitmap, deltaX, deltaY, zoomedWidth, zoomedHeight)
        return Bitmap.createScaledBitmap(zoomedBitmap, originalWidth, originalHeight, true)
    }

    //Quellen:
    //https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,%20int,%20int,%20boolean)
    //https://shareg.pt/hB7B18p
    //https://www.geeksforgeeks.org/how-to-resize-a-bitmap-in-android/
    //https://shareg.pt/LKnVBZ9

    private fun displayZoomedBitmap(roi: Bitmap) {
        imageView.setImageBitmap(roi)
    }

    private fun displayMiniatureBitmap()/*originalBitmap: Bitmap, sizeOfMinature: Int): Bitmap */{
        val originalBitmap = imageBitmaps[currentImageIndex]
        val miniatureBitmap = Bitmap.createScaledBitmap(originalBitmap, 200, 200, true)
        //return Bitmap.createScaledBitmap(originalBitmap, sizeOfMinature, sizeOfMinature, true)
        miniatureImageView.setImageBitmap(miniatureBitmap)
    }

    private fun drawSquareOnMiniature(originalBitmap: Bitmap, miniatureBitmap: Bitmap, landmarkX8: Float, landmarkY8: Float): Bitmap {
        val canvas = Canvas(miniatureBitmap)
        val paint = Paint().apply {
            color = Color.parseColor("#FF5F00")
            style = Paint.Style.FILL
        }

        val scaledX = (landmarkX8 / originalBitmap.width) * miniatureBitmap.width
        val scaledY = (landmarkY8 / originalBitmap.height) * miniatureBitmap.height

        canvas.drawRect(scaledX, scaledY, scaledX + 20, scaledY + 20, paint)
        return miniatureBitmap
    }

    @SuppressLint("SuspiciousIndentation")
    private fun updateMiniature(handLandmarks: HandLandmarks) {
        val originalBitmap = imageBitmaps[currentImageIndex]
        var miniatureBitmap = Bitmap.createScaledBitmap(originalBitmap, 200, 200, true)
        miniatureBitmap = drawSquareOnMiniature(
                originalBitmap,
                miniatureBitmap,
                handLandmarks.x9,
                handLandmarks.y9
            )
            //Update miniatureImageView
            miniatureImageView.setImageBitmap(miniatureBitmap)
        }

    // Erstelle gezoomte Bitmap und update isZoomedIn
    private fun applyZoom(indexPosX: Float, indexPosY: Float) {
        isZoomedIn = true
        lastLandmarkX8 = indexPosX
        lastLandmarkY8 = indexPosY
        val zoomedBitmap = createZoomedBitmap(
            imageBitmaps[currentImageIndex],
            indexPosX,
            indexPosY,
            currentZoomFactor
        )
        displayZoomedBitmap(zoomedBitmap)
    }

    private fun nextImage() {
        if (currentImageIndex < imageBitmaps.size - 1) {
            currentImageIndex++
            applyCurrentZoomState()
            updateIndexTextView()
            }
        }

    private fun previousImage() {
        if (currentImageIndex > 0) {
            currentImageIndex--
            applyCurrentZoomState()
            updateIndexTextView()
        }
    }

    private fun updateIndexTextView() {
        val indexText = "Bildindex: ${currentImageIndex + 1} / ${imageBitmaps.size}"
        indexTextView.text = indexText
    }

    //Erhält den aktuellen Zoom-Zustand bei Bildwechsel
    private fun applyCurrentZoomState() {
        //check ob gezoomt
        if (isZoomedIn) {

            val zoomedBitmap = createZoomedBitmap(
                imageBitmaps[currentImageIndex],
                lastLandmarkX8,
                lastLandmarkY8,
                currentZoomFactor
            )
            displayZoomedBitmap(zoomedBitmap)
        } else {
            displayBitmap()
        }
    }

    //https://shareg.pt/Y8SZFBS

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the GestureRecognizerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            viewModel.setMinHandDetectionConfidence(gestureRecognizerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(gestureRecognizerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(gestureRecognizerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(gestureRecognizerHelper.currentDelegate)

            // Close the Gesture Recognizer helper and release resources
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { //Alle Reaktionen beim Starten der App
        super.onViewCreated(view, savedInstanceState)
        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = gestureRecognizerResultAdapter
        }
        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the Hand Gesture Recognition Helper that will handle the
        // inference
        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                gestureRecognizerListener = this
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        imageView = fragmentCameraBinding.imageView
        imageBitmaps = loadBitmapFromResource() //Aufruf der Bitmap Liste
        indexTextView = view.findViewById(R.id.textViewIndex)
        updateIndexTextView()
        displayBitmap()
        miniatureImageView = view.findViewById(R.id.miniatureImageView)
        displayMiniatureBitmap()
    }


    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandDetectionConfidence >= 0.2) {
                gestureRecognizerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandDetectionConfidence <= 0.8) {
                gestureRecognizerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandTrackingConfidence >= 0.2) {
                gestureRecognizerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandTrackingConfidence <= 0.8) {
                gestureRecognizerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (gestureRecognizerHelper.minHandPresenceConfidence >= 0.2) {
                gestureRecognizerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (gestureRecognizerHelper.minHandPresenceConfidence <= 0.8) {
                gestureRecognizerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        gestureRecognizerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "GestureRecognizerHelper has not been initialized yet.")

                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset recognition
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                gestureRecognizerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                gestureRecognizerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                gestureRecognizerHelper.minHandPresenceConfidence
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            gestureRecognizerHelper.clearGestureRecognizer()
            gestureRecognizerHelper.setupGestureRecognizer()
        }
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        recognizeHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun recognizeHand(imageProxy: ImageProxy) {
        gestureRecognizerHelper.recognizeLiveStream(
            imageProxy = imageProxy,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after a hand gesture has been recognized. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView. Only one result is expected at a time. If two or more
    // hands are seen in the camera frame, only one will be processed.
    override fun onResults( //Alle Reaktionen auf Gesten
        resultBundle: GestureRecognizerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                // Show result of recognized gesture
                val gestureCategories = resultBundle.results.first().gestures()
                if (gestureCategories.isNotEmpty()) {
                    gestureRecognizerResultAdapter.updateResults(
                        gestureCategories.first()
                    )
                    if (gestureCategories.first().isNotEmpty()) {

                        val sortedCategories =
                            gestureCategories.first().sortedByDescending { it.score() }
                        if (sortedCategories.isNotEmpty()) {
                            val category = sortedCategories.first().categoryName()
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastImageChangeTime >= 10) {
                                // Position der Indexfingerspitze
                                val handLandmarks = getLandmarkPosition(resultBundle.results.first())
                                updateMiniature(handLandmarks)

                                when (category) {
                                    "Thumb_Up" -> {
                                        nextImage()
                                        lastImageChangeTime = currentTime // Stoppuhr wieder auf 0 setzen.
                                    }

                                    "Thumb_Down" -> {
                                        previousImage()
                                        lastImageChangeTime = currentTime
                                    }

                                    "Closed_Fist" -> {
                                        //displayBitmap()
                                    }

                                    "Pointing_Up" -> {
                                        applyZoom(handLandmarks.x8, handLandmarks.y8)
                                        if (currentZoomFactor < 3.5f) {
                                            currentZoomFactor += 0.05f // Erhöhen von Zoom in 0.5 Schritten
                                        }
                                    }

                                    "Victory" -> {
                                        applyZoom(handLandmarks.x8, handLandmarks.y8)
                                        if (currentZoomFactor > 1f) {
                                            currentZoomFactor -= 0.05f // Verringern von Zoom in 0.5 Schritten
                                        }
                                    }

                                    "Open_Palm" -> {
                                        if (currentZoomFactor > 1.0f) {
                                            val offsetX = handLandmarks.x8 - lastLandmarkX8
                                            val offsetY = handLandmarks.y8 - lastLandmarkY8

                                            // Update der letzten Landmarke
                                            lastLandmarkX8 = handLandmarks.x8
                                            lastLandmarkY8 = handLandmarks.y8

                                            val zoomedBitmap = createZoomedBitmap(imageBitmaps[currentImageIndex], handLandmarks.x8, handLandmarks.y8, currentZoomFactor, offsetX, offsetY)
                                            displayZoomedBitmap(zoomedBitmap)

                                            // https://shareg.pt/W9aY4j9
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                gestureRecognizerResultAdapter.updateResults(emptyList())
            }
        }

        fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
            String.format("%d ms", resultBundle.inferenceTime)

        // Pass necessary information to OverlayView for drawing on the canvas
        fragmentCameraBinding.overlay.setResults(
            resultBundle.results.first(),
            resultBundle.inputImageHeight,
            resultBundle.inputImageWidth,
            RunningMode.LIVE_STREAM
        )

        // Force a redraw
        fragmentCameraBinding.overlay.invalidate()
        miniatureImageView.invalidate()
    }

    data class HandLandmarks(
        val x0: Float,
        val y0: Float,
        val x8: Float,
        val y8: Float,
        val x9: Float,
        val y9: Float)


    fun getLandmarkPosition(results: GestureRecognizerResult): HandLandmarks {
        val landmarksList = results.landmarks() // Landmarken aus Hand 1
        if (landmarksList.isNotEmpty()) {
            val landmarks = landmarksList[0]
            val landmark0 = landmarks[0] //Deklariere Landmarke #0 = Handwurzel
            val landmark8 = landmarks[8] // Indexfingerspitze
            val landmark9 = landmarks[9] // Mittelfinger Metacarpophalangeal (MCP)
            //logPeriodically("Landmarke 0", "$landmark0")

            // Berechne und skaliere x,y Koordinaten relativ zu imageView
            val x0 = landmark0.x() * imageView.width
            val y0 = landmark0.y() * imageView.height
            val x8 = landmark8.x() * imageView.width
            val y8 = landmark8.y() * imageView.height
            val x9 = landmark9.x() * imageView.width
            val y9 = landmark9.y() * imageView.height

            return HandLandmarks(x0, y0, x8, y8, x9, y9)
        }
        return HandLandmarks(0f, 0f, 0f, 0f, 0f, 0f) //Wenn keine Landmarken vorhanden
    }
    // Quelle: https://shareg.pt/JLIdHmO

    private var lastLogTime = 0L // Initialisiere mit 0
    private fun logPeriodically(tag: String, message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 500) { // 1 Sekunde
            Log.d(tag, message)
            lastLogTime = currentTime // Aktualisiere
        }
    }


    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            gestureRecognizerResultAdapter.updateResults(emptyList())

            if (errorCode == GestureRecognizerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    GestureRecognizerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}
