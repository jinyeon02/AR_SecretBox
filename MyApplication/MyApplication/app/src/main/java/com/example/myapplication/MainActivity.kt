package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.ar.core.ArCoreApk
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.Intent
import com.example.myapplication.HelloArActivity
private const val CAMERA_PERMISSION_CODE = 100
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private fun launchArScreen() {
        // AR 뷰를 렌더링할 Activity로 전환합니다.
        // 여기서는 Google ARCore 샘플에서 흔히 사용하는 HelloArActivity를 가정합니다.
        val intent = Intent(this, HelloArActivity::class.java)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeEnableArButton() // 🛑 ERROR: binding이 초기화되기 전에 arButton을 사용하려고 시도
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()
        maybeEnableArButton()
        binding.arButton.setOnClickListener {
            launchArScreen() // AR 화면으로 이동하거나 세션을 시작하는 함수
        }
    }

    fun maybeEnableArButton() {
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (availability.isSupported) {
                binding.arButton.visibility = View.VISIBLE
                binding.arButton.isEnabled = true
            } else { // The device is unsupported or unknown.
                binding.arButton.visibility = View.INVISIBLE
                binding.arButton.isEnabled = false
            }
        }
    }
    override fun onResume() {
        super.onResume()

        // 1. 카메라 권한 확인 및 요청 (필수)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }

        // 2. ARCore 가용성 재확인 (버튼 활성화 로직)
        maybeEnableArButton()
    }


    /**
     * A native method that is implemented by the 'myapplication' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'myapplication' library on application startup.
        init {
            System.loadLibrary("myapplication")
        }
    }
}