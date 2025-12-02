package com.example.jebal

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private var isTreasureSpawned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. ARSceneView 연결
        sceneView = findViewById(R.id.arSceneView)

        // 바닥 점 끄기
        sceneView.planeRenderer.isEnabled = false

        // 2. [색감 문제 해결] 조명 설정
       // 주 조명 (Main Light) - 그림자 생성용
        sceneView.mainLightNode?.intensity = 50000f

        // [수정] 간접 조명 (Indirect Light) - 반사광 및 전체 밝기 담당
        // environment 객체 내부의 indirectLight에 접근해야 합니다.
        sceneView.environment?.indirectLight?.intensity = 50000f

        // 3. 세션 리스너 설정
        sceneView.onSessionResumed = { session ->
            spawnTreasureWithDelay(3000L)
        }

        sceneView.onSessionFailed = { exception ->
            Log.e("AR_ERROR", "ARCore 세션 오류: ${exception.message}")
        }
    }

    private fun spawnTreasureWithDelay(delayMillis: Long) {
        if (isTreasureSpawned) return

        lifecycleScope.launch {
            delay(delayMillis)

            while (sceneView.cameraNode.trackingState != TrackingState.TRACKING) {
                Toast.makeText(this@MainActivity, "공간을 인식 중입니다... 잠시만 기다려주세요.", Toast.LENGTH_SHORT).show()
                delay(1000)
            }

            val cameraNode = sceneView.cameraNode
            val cameraPose = cameraNode.pose ?: return@launch

            // 위치 조정 (전방 0.8m, 약간 아래) - 가까이서 보기 위해 거리 단축
            val randomX = Random.nextDouble(-0.3, 0.3).toFloat()
            val offsetPose = Pose.makeTranslation(randomX, -0.5f, -0.8f)
            val treasurePose = cameraPose.compose(offsetPose)

            val anchor = sceneView.session?.createAnchor(treasurePose) ?: return@launch
            val anchorNode = AnchorNode(sceneView.engine, anchor)

            val modelNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance("treasure_chest.glb"),
                scaleToUnits = 0.5f
            ).apply {
                stopAnimation(0)
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

    private fun handleTreasureFound(modelNode: ModelNode) {
        modelNode.isTouchable = false
        modelNode.playAnimation(animationIndex = 0, loop = false)

        val treasureId = Random.nextInt(1000, 9999)
        val treasureName = "황금 열쇠"

        saveTreasureToDb(treasureId, treasureName)

        lifecycleScope.launch {
            delay(1000)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("축하합니다!")
                .setMessage("'$treasureName' (ID: $treasureId)을(를) 획득했습니다!")
                .setPositiveButton("확인") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun saveTreasureToDb(id: Int, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("DB_SAVE", "저장 완료 - ID: $id, Name: $name")
        }
    }
}