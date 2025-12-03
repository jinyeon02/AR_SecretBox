package com.example.treasurear.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.treasurear.R
import com.example.treasurear.data.db.AppDatabase
import com.example.treasurear.data.initial.DataInitializer
import com.example.treasurear.ui.pokedex.PokedexActivity
import com.example.treasurear.ui.ar.ARActivity // [필수] ARActivity import 확인!
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. DB 초기화 및 데이터 로드 (기존 유지)
        db = AppDatabase.getInstance(this)
        val dao = db.treasureDao()

        val prefs = getSharedPreferences("init", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first", true)

        lifecycleScope.launch {
            if (isFirstRun) {
                val initializer = DataInitializer(this@MainActivity, dao)
                initializer.loadInitialData()
                prefs.edit().putBoolean("first", false).apply()
            }
        }

        // 2. 도감 열기 버튼 (기존 유지)
        val btnPokedex = findViewById<Button>(R.id.btnOpenPokedex)
        btnPokedex.setOnClickListener {
            val intent = Intent(this, PokedexActivity::class.java)
            startActivity(intent)
        }

        // 3. [교체됨] AR 보물찾기 시작 버튼 연결
        val btnStartAR = findViewById<Button>(R.id.btnStartAR)
        btnStartAR.setOnClickListener {

            // (권장) 구역이 선택되었는지 확인하는 안전장치
            // 도감에서 구역을 선택해야 보물이 나오므로, 선택 안 했으면 알림을 띄웁니다.
            val statePrefs = getSharedPreferences("state", MODE_PRIVATE)
            val lastSubZoneId = statePrefs.getInt("lastSubZoneId", -1)

            if (lastSubZoneId == -1) {
                Toast.makeText(this, "먼저 '도감 열기'에서 탐험할 구역을 선택해주세요!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // AR 화면으로 이동
            val intent = Intent(this, ARActivity::class.java)
            startActivity(intent)
        }
    }
}