package com.example.jebal

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.filament.LightManager
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
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

        sceneView = findViewById(R.id.arSceneView)

        // 1. 바닥 점 끄기
        sceneView.planeRenderer.isEnabled = false

        // 2. [해결책] 카메라에 조명 달기 (헤드랜턴 효과)
        // 이렇게 하면 내가 보는 방향으로 항상 빛이 나가므로 검게 보일 수가 없습니다.
        addHeadLight()

        // 3. 세션 리스너
        sceneView.onSessionResumed = { session ->
            spawnTreasureWithDelay(3000L)
        }

        sceneView.onSessionFailed = { exception ->
            Log.e("AR_ERROR", "ARCore 세션 오류: ${exception.message}")
        }
    }

    // [핵심] 카메라를 따라다니는 조명 추가 함수
    // [수정된 함수] 카메라를 따라다니는 조명 추가
    private fun addHeadLight() {
        val headLight = LightNode(
            engine = sceneView.engine,
            type = LightManager.Type.DIRECTIONAL
        ) {
            // 1. [Light Builder] 조명 자체의 속성 설정 (함수 호출 방식)
            intensity(80000f) // 밝기 설정
        }.apply {
            // 2. [Node] 노드의 속성 설정
            // 빛이 비추는 방향 (카메라가 보는 방향과 같게)
            rotation = Rotation(0.0f, 0.0f, 0.0f)
        }

        // 3. 카메라 노드에 자식으로 연결 (이 방식이 가장 안전합니다)
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

            // 위치: 전방 0.8m, 바닥 쪽
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