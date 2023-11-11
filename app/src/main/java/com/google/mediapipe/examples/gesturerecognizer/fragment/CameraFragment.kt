package com.google.mediapipe.examples.gesturerecognizer.fragment

    import android.annotation.SuppressLint
    import android.content.res.Configuration
    import android.graphics.Bitmap
    import android.graphics.BitmapFactory
    import android.graphics.drawable.BitmapDrawable
    import android.os.Bundle
    import android.util.Log
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.widget.AdapterView
    import android.widget.ImageView
    import android.widget.Toast
    import androidx.camera.core.*
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.core.content.ContextCompat
    import androidx.fragment.app.Fragment
    import androidx.fragment.app.activityViewModels
    import androidx.navigation.Navigation
    import androidx.recyclerview.widget.LinearLayoutManager
    import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
    import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
    import com.google.mediapipe.examples.gesturerecognizer.R
    import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
    import com.google.mediapipe.tasks.vision.core.RunningMode
    import java.util.*
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors
    import java.util.concurrent.TimeUnit

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
        private lateinit var imageViewDisplay: ImageView

        private var lastImageChangeTime: Long = 0 // Stoppuhr fuer Bildwechsel (Long für ms)

        /* private val imageResourceIds = arrayOf(
             R.drawable.image_000001
             )
         */

        //private var imageResources: List<Int> = buildImageList()
        private var imageBitmaps: List<Bitmap> = listOf() //Anstatt Res-IDs --> Neue Liste für Bitmap Objekte

        private fun displayZoomedBitmap() {
            if (currentImageIndex in imageBitmaps.indices) {
                val originalBitmap = imageBitmaps[currentImageIndex]

                // Originale Bitmap Eigenschaften
                Log.d("BitmapInfo", "Original Bitmap - Width: ${originalBitmap.width}, Height: ${originalBitmap.height}")

                val zoomedBitmap = createZoomedBitmap(originalBitmap)
                imageViewDisplay.setImageBitmap(zoomedBitmap)
            }
        }
        private fun createZoomedBitmap(originalBitmap: Bitmap): Bitmap {
            val x = originalBitmap.width / 4
            val y = originalBitmap.height / 4
            val width = originalBitmap.width / 2
            val height = originalBitmap.height / 2

            Log.d("ZoomBitmap", "Zoomed Bitmap - x: $x, y: $y, width: $width, height: $height")
            return Bitmap.createBitmap(originalBitmap, x, y, width, height)

        }

        private fun loadBitmapFromResource(): List<Bitmap> {
            val fields = R.raw::class.java.fields //Felder aus Java Klasse
            return fields.mapNotNull { field ->
                val id = field.getInt(null)
                BitmapFactory.decodeResource(resources, id)// Umwandeln von Integer ID in Bitmap
            }
        }
/*
                private fun buildImageList(): List<Int> { //erzeuge dynamische Liste von allen Bildern in /raw ohne spezifische Auflistung zu erzeugen
                    val fields = R.raw::class.java.fields //Felder aus Java Klasse
                    val imageList = mutableListOf<Int>() //leere, veränderbare Liste für IDs der Bilder

                    for (field in fields) {
                        val id = field.getInt(null) //getter Methode für Id des Resource-Feldes --> null weil Java Konventionen
                        imageList.add(id) //Füge Id zur Liste hinzu
                    }
                    return imageList
                }*/
        /*
                        private fun loadImageFromResource(imageView: ImageView) {
                            val bitmap = BitmapFactory.decodeResource(resources, imageResources[currentImageIndex])
                            imageView.setImageBitmap(bitmap)
                            //imageViewDisplay.setImageResource(imageResources[currentImageIndex])
                        }*/

        private fun loadImageFromResource(imageView: ImageView) {
            if (currentImageIndex in imageBitmaps.indices) {
                imageView.setImageBitmap(imageBitmaps[currentImageIndex])
            }
        }

        private fun nextImage() {
            if (currentImageIndex < imageBitmaps.size - 1) {
                currentImageIndex++
                loadImageFromResource(imageViewDisplay)
            }
        }

        private fun previousImage() {
            if (currentImageIndex > 0) {
                currentImageIndex--
                loadImageFromResource(imageViewDisplay)
            }
        }

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

        @SuppressLint("MissingPermission")
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

            imageViewDisplay = fragmentCameraBinding.imageView
            imageBitmaps = loadBitmapFromResource() //Aufruf der Bitmap Liste
            displayZoomedBitmap()
            loadImageFromResource(imageViewDisplay)
            //loadImageFromResource(imageViewDisplay)
            //fragmentCameraBinding.buttonNext.setOnClickListener { nextImage() }
            //fragmentCameraBinding.buttonPrevious.setOnClickListener { previousImage() }

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


        //private var isZoomedIn = false //ZoomIn auf false setzen
        //private val zoomFactor = 2.0f //Zoomstärke (evtl. anpassen)

        /*
        private fun zoomInBitmap() {
            if (isZoomedIn) {
                imageViewDisplay.scaleX = 1f
                imageViewDisplay.scaleY = 1f
                isZoomedIn = false
            } else {
                imageViewDisplay.scaleX = zoomFactor
                imageViewDisplay.scaleY = zoomFactor
                isZoomedIn = true
            }
        }*/
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
                                when (category) {
                                    "Thumb_Up" -> {
                                        nextImage()
                                        lastImageChangeTime = currentTime // Stoppuhr wieder auf 0 setzen.
                                    }
                                    "Thumb_Down" -> {
                                        previousImage()
                                        lastImageChangeTime = currentTime
                                    }
                                    //"Closed_Fist" -> {
                                      //  zoomInBitmap()
                                    //}
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