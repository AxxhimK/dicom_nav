package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.Toast
import android.net.Uri
import android.os.Environment
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
import com.google.mediapipe.examples.gesturerecognizer.ImageHandler
import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
import com.google.mediapipe.examples.gesturerecognizer.R
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.pixelmed.dicom.DicomDirectoryRecordType.image
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.system.measureTimeMillis

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

    private val imageResourceIds = arrayOf(
        /*R.drawable.black,
        R.drawable.blue,
        R.drawable.green,
        R.drawable.red,
        R.drawable.purple,
        R.drawable.yellow,
        R.drawable.turq,*/
        R.drawable.image_000001,
        R.drawable.image_000002,
        R.drawable.image_000003,
        R.drawable.image_000004,
        R.drawable.image_000005,
        R.drawable.image_000006,
        R.drawable.image_000007,
        R.drawable.image_000008,
        R.drawable.image_000009,
        R.drawable.image_000010,
        R.drawable.image_000011,
        R.drawable.image_000012,
        R.drawable.image_000013,
        R.drawable.image_000014,
        R.drawable.image_000015,
        R.drawable.image_000016,
        R.drawable.image_000017,
        R.drawable.image_000018,
        R.drawable.image_000019,
        R.drawable.image_000020,
        R.drawable.image_000021,
        R.drawable.image_000022,
        R.drawable.image_000023,
        R.drawable.image_000024,
        R.drawable.image_000025,
        R.drawable.image_000026,
        R.drawable.image_000027,
        R.drawable.image_000028,
        R.drawable.image_000029,
        R.drawable.image_000030,
        R.drawable.image_000031,
        R.drawable.image_000032,
        R.drawable.image_000033,
        R.drawable.image_000034,
        R.drawable.image_000035,
        R.drawable.image_000036,
        R.drawable.image_000037,
        R.drawable.image_000038,
        R.drawable.image_000039,
        R.drawable.image_000040,
        R.drawable.image_000041,
        R.drawable.image_000042,
        R.drawable.image_000043,
        R.drawable.image_000044,
        R.drawable.image_000045,
        R.drawable.image_000046,
        R.drawable.image_000047,
        R.drawable.image_000048,
        R.drawable.image_000049,
        R.drawable.image_000050,
    )


/*
    fun loadImagesFromExternalStorage() {
        val imagesList = mutableListOf<Uri>()
        val imagesDir = requireContext().getExternalFilesDir(null)
        val jpgFiles = imagesDir?.listFiles { _, name ->
            name.endsWith((".jpg"))
        }

        jpgFiles?.forEach { file ->
            val imageUri = Uri.fromFile(file)
            imagesList.add(imageUri)
        }
    }

    fun loadImagesFromDocumentsDir(): List<File> {
        val imagesList = mutableListOf<File>()

        if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState() || Environment.MEDIA_MOUNTED_READ_ONLY == Environment.getExternalStorageState())
        val documentsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir.exists()) {
            val jpgFiles =
                documentsDir.listFiles { _, name -> name.endsWith(".jpg", ignoreCase = true) }
        }
        jpgFiles?.let { files ->
            imagesList.addAll(files)
        }
        return imagesList
    }

*/
    // Bilder von Resources laden
    fun loadImageFromResource(imageView: ImageView) {
        imageView.setImageResource(imageResourceIds[currentImageIndex])
    }
    fun nextImage() {
       currentImageIndex = (currentImageIndex + 1) % imageResourceIds.size //nächstes Bild (Berechnung hinzufügen)
        loadImageFromResource(imageViewDisplay)
    }
    fun previousImage() {
        currentImageIndex = if (currentImageIndex - 1 < 0) {
            imageResourceIds.size - 1
        } else
            currentImageIndex - 1
        loadImageFromResource(imageViewDisplay)
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
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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
        loadImageFromResource(imageViewDisplay)
        fragmentCameraBinding.buttonNext.setOnClickListener { nextImage() }
        fragmentCameraBinding.buttonPrevious.setOnClickListener { previousImage() }

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
    override fun onResults(
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
                            if (currentTime - lastImageChangeTime >= 100) {
                                when (category) {
                                    "Thumb_Up" -> {
                                        nextImage()
                                        lastImageChangeTime = currentTime // Stoppuhr wieder auf 0 setzen.
                                    }
                                    "Thumb_Down" -> {
                                        previousImage()
                                        lastImageChangeTime = currentTime
                                    }
                                }
                            }
                        }
                            /*if (category == "Thumb_Up") {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - imageChangeTime >= 1000) { //1000ms = 1s
                                    nextImage()
                                    imageChangeTime = currentTime
                                }
                            }
                            else if (category == "Thumb_Down") {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - imageChangeTime >= 1000) {
                                    previousImage()
                                    imageChangeTime = currentTime
                                }
                            }*/
                            //"Thumb_Up" -> fragmentCameraBinding.overlay.setThumbUp(true)
                            //"Open_Palm" -> fragmentCameraBinding.overlay.setThumbUp(false)
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
    interface ImageChangeAction {
        //fun nextImage()
    }
}