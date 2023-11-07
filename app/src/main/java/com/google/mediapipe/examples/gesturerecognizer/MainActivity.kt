package com.google.mediapipe.examples.gesturerecognizer

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.mediapipe.examples.gesturerecognizer.databinding.ActivityMainBinding
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Environment
import com.google.mediapipe.examples.gesturerecognizer.fragment.CameraFragment
import com.google.mediapipe.tasks.components.containers.Category
import com.pixelmed.dicom.AttributeList
import com.pixelmed.dicom.DicomInputStream
import com.pixelmed.display.SourceImage
import java.io.InputStream
import java.nio.IntBuffer

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    //private lateinit var imageHandler: ImageHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        activityMainBinding.navigation.setupWithNavController(navController)
        activityMainBinding.navigation.setOnNavigationItemReselectedListener {
            // ignore the reselection
        }
        /*
        val imageView = findViewById<ImageView>(R.id.imageView)
        imageHandler.nextImage(imageView) // Load initial image

        activityMainBinding.buttonNext.setOnClickListener {
            imageHandler.nextImage(imageView)
        }
        activityMainBinding.buttonPrevious.setOnClickListener {
            imageHandler.previousImage(imageView)
        }
        //activityMainBinding.imageView

    */
    }
    override fun onBackPressed() {
        finish()
    }
/*
    override fun nextImage() {
        imageHandler.nextImage(activityMainBinding.imageView)
    }
    override fun previousImage() {
    }
    */

}