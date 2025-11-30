package com.example.myapplication

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.widget.Toast
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.ar.core.ArCoreApk

/**
 * Main entry point of the AR application.
 * 
 * This activity:
 * - Checks for ARCore support
 * - Requests camera permission
 * - Provides a button to launch the AR experience
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    private lateinit var binding: ActivityMainBinding

    /**
     * Launches the AR screen (HelloArActivity).
     */
    private fun launchArScreen() {
        val intent = Intent(this, HelloArActivity::class.java)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up AR button click listener
        binding.arButton.setOnClickListener {
            launchArScreen()
        }
    }

    /**
     * Checks ARCore availability and enables/disables the AR button accordingly.
     * This method is called after camera permission is granted.
     */
    private fun maybeEnableArButton() {
        if (!::binding.isInitialized) return

        // Check ARCore availability asynchronously
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (availability.isSupported) {
                // Device supports ARCore - enable the button
                binding.arButton.visibility = View.VISIBLE
                binding.arButton.isEnabled = true
            } else {
                // Device does not support ARCore - hide the button
                binding.arButton.visibility = View.INVISIBLE
                binding.arButton.isEnabled = false
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Check camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request permission if not granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            return
        }

        // Permission granted - check ARCore availability and enable button
        if (::binding.isInitialized) {
            maybeEnableArButton()
        }
    }

    /**
     * Handles the result of permission requests.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted - enable AR button
                if (::binding.isInitialized) {
                    maybeEnableArButton()
                }
            } else {
                // Permission denied - show message
                Toast.makeText(
                    this,
                    "AR 기능을 사용하려면 카메라 권한이 필요합니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}