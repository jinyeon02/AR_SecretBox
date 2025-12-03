package com.example.jebal

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater // [추가]
import android.widget.ImageView // [추가]
import android.widget.TextView // [추가]
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

data class TreasureItem(val name: String, val imageResId: Int)


class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private var isTreasureSpawned = false

    // [추가] 랜덤으로 나올 보물 목록 리스트
    private val treasureList = listOf(
        TreasureItem("교수님과 식사 데이트권", R.drawable.ic_launcher_foreground),
        TreasureItem("교수님 농담 이해력+5", R.drawable.ic_launcher_foreground),
        TreasureItem("수업 1회 지각 허용권", R.drawable.ic_launcher_foreground),
        TreasureItem("A+ 기원 부적", R.drawable.ic_launcher_foreground),
        TreasureItem("몰래 간식 먹기 성공권", R.drawable.ic_launcher_foreground),
        TreasureItem("조별과제 면죄부", R.drawable.ic_launcher_foreground)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.arSceneView)
        sceneView.planeRenderer.isEnabled = false

        sceneView.configureSession { session, config ->
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        addHeadLight()
        sceneView.mainLightNode?.intensity = 100000f

        sceneView.onSessionResumed = { session ->
            spawnTreasureWithDelay(3000L)
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

    private fun spawnTreasureWithDelay(delayMillis: Long) {
        if (isTreasureSpawned) return

        lifecycleScope.launch {
            delay(delayMillis)

            while (sceneView.cameraNode.trackingState != TrackingState.TRACKING) {
                Toast.makeText(this@MainActivity, "공간 인식 중... 조금만 움직여주세요.", Toast.LENGTH_SHORT).show()
                delay(1000)
            }

            val cameraNode = sceneView.cameraNode
            val cameraPose = cameraNode.pose ?: return@launch

            val randomX = Random.nextDouble(-0.3, 0.3).toFloat()
            val offsetPose = Pose.makeTranslation(randomX, -0.5f, -0.8f)
            val treasurePose = cameraPose.compose(offsetPose)

            val anchor = sceneView.session?.createAnchor(treasurePose) ?: return@launch
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
            Toast.makeText(this@MainActivity, "💎 보물상자 발견!", Toast.LENGTH_LONG).show()
        }
    }

    // [수정됨 3단계] 랜덤 보물 뽑기 로직 적용
    private fun handleTreasureFound(modelNode: ModelNode) {
        modelNode.isTouchable = false
        modelNode.playAnimation(animationIndex = 0, loop = false)

        // 1. 리스트에서 랜덤으로 아이템 객체 뽑기
        val selectedTreasureItem = treasureList.random()

        // ID도 랜덤 생성
        val treasureId = Random.nextInt(1000, 9999)

        // DB 저장 (객체의 name 속성 사용)
        saveTreasureToDb(treasureId, selectedTreasureItem.name)

        // 결과 UI 표시 (별도 함수 호출)
        lifecycleScope.launch {
            delay(1000)
            showResultDialog(selectedTreasureItem, treasureId, modelNode)
        }
    }

    // [추가됨 3단계] 커스텀 다이얼로그를 띄우는 함수
    private fun showResultDialog(treasureItem: TreasureItem, id: Int, modelNode: ModelNode) {
        // 1. 커스텀 레이아웃 Inflate (메모리에 로드)
        // dialog_treasure_result.xml 파일을 기반으로 뷰를 생성합니다.
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_treasure_result, null)

        // 2. 레이아웃 내부의 뷰 찾기
        val imageView = dialogView.findViewById<ImageView>(R.id.treasureImageView)
        val nameTextView = dialogView.findViewById<TextView>(R.id.treasureNameTextView)
        val idTextView = dialogView.findViewById<TextView>(R.id.treasureIdTextView)

        // 3. 뽑힌 데이터로 뷰 내용 채우기
        imageView.setImageResource(treasureItem.imageResId)
        nameTextView.text = treasureItem.name
        idTextView.text = "ID: $id"

        // 4. AlertDialog 생성 및 설정
        AlertDialog.Builder(this@MainActivity)
            .setTitle("축하합니다!")
            // .setMessage() 대신 .setView()를 사용하여 커스텀 레이아웃을 설정합니다.
            .setView(dialogView)
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                // 확인 누르면 상자 사라짐
                modelNode.parent?.destroy()
            }
            .setCancelable(false)
            .show()
    }

    private fun saveTreasureToDb(id: Int, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("DB_SAVE", "저장 완료 - ID: $id, Name: $name")
        }
    }
}
