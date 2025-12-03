package com.example.treasurear.ui.ar

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.treasurear.R
import com.example.treasurear.data.db.AppDatabase
import com.example.treasurear.data.entity.TreasureEntity
import com.google.android.filament.LightManager
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class ARActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private var isTreasureSpawned = false

    // DB에서 불러온 보물 데이터를 저장할 변수
    private var targetTreasure: TreasureEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 레이아웃 설정 (반드시 가장 먼저!)
        setContentView(R.layout.activity_ar)

        // 2. 뷰 초기화
        sceneView = findViewById(R.id.arSceneView)

        // 초기 설정
        sceneView.planeRenderer.isEnabled = false
        sceneView.configureSession { session, config ->
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        addHeadLight()
        sceneView.mainLightNode?.intensity = 100000f

        // 세션이 시작되면 DB에서 보물 정보를 가져오고 스폰 준비
        sceneView.onSessionResumed = { session ->
            prepareTreasureFromDb()
        }

        sceneView.onSessionFailed = { exception ->
            Log.e("AR_ERROR", "ARCore 세션 오류: ${exception.message}")
        }
    }

    private fun addHeadLight() {
        val headLight = LightNode(
            engine = sceneView.engine,
            type = LightManager.Type.DIRECTIONAL
        ) {
            intensity(120000f)
            color(1.0f, 1.0f, 1.0f)
            direction(0.0f, 0.0f, -1.0f)
        }
        headLight.rotation = Rotation(0.0f, 0.0f, 0.0f)
        headLight.parent = sceneView.cameraNode
    }

    // [DB 연동] 미수집 보물을 랜덤으로 하나 가져오기
    private fun prepareTreasureFromDb() {
        if (isTreasureSpawned) return

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@ARActivity).treasureDao()

            // 백그라운드 스레드에서 DB 조회
            val treasure = withContext(Dispatchers.IO) {
                dao.getRandomUncollectedTreasure()
            }

            if (treasure == null) {
                // 더 이상 수집할 보물이 없는 경우
                Toast.makeText(this@ARActivity, "모든 보물을 이미 수집했습니다!", Toast.LENGTH_LONG).show()
                delay(2000)
                finish() // 화면 종료
            } else {
                targetTreasure = treasure
                spawnTreasureWithDelay(3000L)
            }
        }
    }

    private suspend fun spawnTreasureWithDelay(delayMillis: Long) {
        delay(delayMillis)

        // 트래킹 상태가 될 때까지 대기
        while (sceneView.cameraNode.trackingState != TrackingState.TRACKING) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ARActivity, "공간 인식 중... 조금만 움직여주세요.", Toast.LENGTH_SHORT).show()
            }
            delay(1000)
        }

        val cameraNode = sceneView.cameraNode
        val cameraPose = cameraNode.pose ?: return

        // 카메라 앞 랜덤 위치 계산
        val randomX = Random.nextDouble(-0.3, 0.3).toFloat()
        val offsetPose = Pose.makeTranslation(randomX, -0.5f, -0.8f)
        val treasurePose = cameraPose.compose(offsetPose)

        // 앵커 및 모델 생성
        val anchor = sceneView.session?.createAnchor(treasurePose) ?: return
        val anchorNode = AnchorNode(sceneView.engine, anchor)

        val modelNode = ModelNode(
            modelInstance = sceneView.modelLoader.createModelInstance("treasure_chest.glb"),
            scaleToUnits = 0.5f
        ).apply {
            try { stopAnimation(0) } catch (e: Exception) { }
            isTouchable = true
            onSingleTapConfirmed = {
                handleTreasureFound(this)
                true
            }
        }

        modelNode.parent = anchorNode
        sceneView.addChildNode(anchorNode)

        isTreasureSpawned = true
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ARActivity, "💎 보물상자 발견!", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTreasureFound(modelNode: ModelNode) {
        val treasure = targetTreasure ?: return

        modelNode.isTouchable = false
        modelNode.playAnimation(animationIndex = 0, loop = false)

        // [핵심] DB에 '수집됨' 상태 업데이트
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@ARActivity).treasureDao()

            // 수집 처리 (isCollected = 1로 변경)
            withContext(Dispatchers.IO) {
                dao.collectTreasure(treasure.id)
            }
            Log.d("DB_SAVE", "수집 완료: ${treasure.name} (ID: ${treasure.id})")

            delay(1000) // 애니메이션 대기
            showResultDialog(treasure, modelNode)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun showResultDialog(treasure: TreasureEntity, modelNode: ModelNode) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_treasure_result, null)

        val imageView = dialogView.findViewById<ImageView>(R.id.treasureImageView)
        val nameTextView = dialogView.findViewById<TextView>(R.id.treasureNameTextView)
        val idTextView = dialogView.findViewById<TextView>(R.id.treasureIdTextView)

        // [이미지 처리] DB에 저장된 파일명(String)을 리소스 ID(Int)로 변환
        // 예: DB에 "potion"이라고 저장되어 있으면 R.drawable.potion을 찾음
        val resourceId = resources.getIdentifier(
            treasure.imageUrl, // DB에 저장된 이미지 파일명
            "drawable",
            packageName
        )

        // 이미지가 있으면 설정, 없으면 기본 이미지
        if (resourceId != 0) {
            imageView.setImageResource(resourceId)
        } else {
            imageView.setImageResource(R.drawable.ic_launcher_foreground) // 기본 이미지
        }

        nameTextView.text = treasure.name
        idTextView.text = "ID: ${treasure.id}"

        AlertDialog.Builder(this@ARActivity)
            .setTitle("축하합니다!")
            .setView(dialogView)
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                modelNode.parent?.destroy()

                // 확인 누르면 액티비티 종료 -> 도감 화면으로 복귀하여 수집된 것 확인
                finish()
            }
            .setCancelable(false)
            .show()
    }
}