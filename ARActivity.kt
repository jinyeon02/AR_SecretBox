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

    // [테스트용] 찾고 싶은 보물 이름 (DB에 저장된 이름과 정확히 일치해야 함)
    private val TEST_TREASURE_NAME = "교수님과 식사 데이트권" // <-- 여기에 테스트할 보물 이름을 적으세요

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 레이아웃 설정
        setContentView(R.layout.activity_ar)

        // 2. 뷰 초기화
        sceneView = findViewById(R.id.arSceneView)

        sceneView.planeRenderer.isEnabled = false
        sceneView.configureSession { session, config ->
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        addHeadLight()
        sceneView.mainLightNode?.intensity = 100000f

        // 세션 시작, DB에서 보물 정보를 가져오고 스폰 준비
        sceneView.onSessionResumed = {
                session ->
            prepareTreasureFromDb()
        }

        sceneView.onSessionFailed = {
                exception ->
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

    // [DB 연동] 보물 가져오기
    private fun prepareTreasureFromDb() {
        if (isTreasureSpawned) return

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@ARActivity).treasureDao()

            // 백그라운드 스레드에서 DB 조회
            val treasure = withContext(Dispatchers.IO) {
                // [기존 코드] 랜덤으로 미수집 보물 하나 가져오기 (주석 처리됨)
                // dao.getRandomUncollectedTreasure()

                // [테스트 코드] 특정 이름으로 가져오기
                dao.getTreasureByName(TEST_TREASURE_NAME)
            }

            if (treasure == null) {
                // [기존 코드] 모든 보물 수집 시 (주석 처리됨)
                /*
                Toast.makeText(this@ARActivity,
                    "모든 보물을 이미 수집했습니다!",
                    Toast.LENGTH_LONG).show()
                delay(2000)
                finish()
                */

                // [테스트 코드] 해당 이름의 보물이 없을 때
                Toast.makeText(this@ARActivity,
                    "DB에서 '$TEST_TREASURE_NAME'을(를) 찾을 수 없습니다.",
                    Toast.LENGTH_LONG).show()
                delay(2000)
                finish()
            } else {
                targetTreasure = treasure

                // [기존 코드] 10초 대기 (주석 처리됨)
                // spawnTreasureWithDelay(10000L)

                // [테스트 코드] 1초 대기 (빠른 테스트)
                spawnTreasureWithDelay(1000L)
            }
        }
    }

    private suspend fun spawnTreasureWithDelay(delayMillis: Long) {
        delay(delayMillis)

        // 트래킹 상태가 될 때까지 대기
        while (sceneView.cameraNode.trackingState != TrackingState.TRACKING) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ARActivity,
                    "공간 인식 중... 조금만 움직여주세요.",
                    Toast.LENGTH_SHORT).show()
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
            modelInstance =
                sceneView.modelLoader.createModelInstance("treasure_chest.glb"),
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
            Toast.makeText(
                this@ARActivity,
                "💎 ${targetTreasure?.name} 발견!", // (선택 사항) 발견 시 이름 표시하도록 변경함
                Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTreasureFound(modelNode: ModelNode) {
        val treasure = targetTreasure ?: return

        // 터치 감지 후 애니메이션 재생
        modelNode.isTouchable = false
        modelNode.playAnimation(animationIndex = 0, loop = false)

        // DB 상태 업데이트
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
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_treasure_result, null)

        val imageView =
            dialogView.findViewById<ImageView>(R.id.treasureImageView)

        val nameTextView =
            dialogView.findViewById<TextView>(R.id.treasureNameTextView)

        val idTextView =
            dialogView.findViewById<TextView>(R.id.treasureIdTextView)

        // 이미지 처리: DB에 저장된 파일명(String)을 리소스 ID(Int)로 변환
        val resourceId = resources.getIdentifier(
            treasure.imageUrl,
            "drawable",
            packageName
        )

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

                finish()
            }
            .setCancelable(false)
            .show()
    }
}