package com.example.jebal

import android.os.Bundle
import android.util.Log
import android.widget.Toast
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

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private var isTreasureSpawned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.arSceneView)

        // 1. 바닥 점 끄기
        sceneView.planeRenderer.isEnabled = false

        // [핵심 1] ARCore의 조명 자동 조절 기능 끄기 (이게 켜져 있으면 어둡게 나옵니다)
        sceneView.configureSession { session, config ->
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        // [핵심 2] 카메라에 '헤드랜턴' 조명 달기
        // 환경맵이 없어도 내가 보는 방향으로 항상 빛을 쏘기 때문에 검게 나올 수가 없습니다.
        addHeadLight()

        // 3. 기존 태양광(Main Light)도 밝게 설정
        sceneView.mainLightNode?.intensity = 100000f

        sceneView.onSessionResumed = { session ->
            spawnTreasureWithDelay(3000L)
        }

        sceneView.onSessionFailed = { exception ->
            Log.e("AR_ERROR", "ARCore 세션 오류: ${exception.message}")
        }
    }

    // 카메라를 따라다니는 강력한 조명을 추가하는 함수
    private fun addHeadLight() {
        // LightNode 생성 (빌더 패턴 사용)
        val headLight = LightNode(
            engine = sceneView.engine,
            type = LightManager.Type.DIRECTIONAL
        ) {
            // 조명 자체 속성 설정
            intensity(120000f) // 빛 강도 (아주 밝게 설정)
            color(1.0f, 1.0f, 1.0f) // 흰색 빛
            direction(0.0f, 0.0f, -1.0f) // 빛의 방향 (앞쪽으로)
        }

        // 조명 노드 위치/회전 설정
        headLight.rotation = Rotation(0.0f, 0.0f, 0.0f)

        // [중요] 카메라 노드에 자식으로 붙임 -> 카메라가 움직이면 조명도 따라감
        // addChildNode 대신 parent 속성 사용 (호환성)
        headLight.parent = sceneView.cameraNode
        // 만약 parent 설정이 안 먹히면 아래 줄 사용:
        // sceneView.cameraNode.addChildNode(headLight)
    }

    private fun spawnTreasureWithDelay(delayMillis: Long) {
        if (isTreasureSpawned) return

        lifecycleScope.launch {
            delay(delayMillis)

            // 트래킹 대기
            while (sceneView.cameraNode.trackingState != TrackingState.TRACKING) {
                Toast.makeText(this@MainActivity, "공간 인식 중... 조금만 움직여주세요.", Toast.LENGTH_SHORT).show()
                delay(1000)
            }

            val cameraNode = sceneView.cameraNode
            val cameraPose = cameraNode.pose ?: return@launch

            // 위치: 전방 0.8m
            val randomX = Random.nextDouble(-0.3, 0.3).toFloat()
            val offsetPose = Pose.makeTranslation(randomX, -0.5f, -0.8f)
            val treasurePose = cameraPose.compose(offsetPose)

            val anchor = sceneView.session?.createAnchor(treasurePose) ?: return@launch
            val anchorNode = AnchorNode(sceneView.engine, anchor)

            val modelNode = ModelNode(
                modelInstance = sceneView.modelLoader.createModelInstance("treasure_chest.glb"),
                scaleToUnits = 0.5f
            ).apply {
                // 애니메이션 초기화 (0번 프레임에서 멈춤)
                // 파라미터 없이 호출하거나, 0을 넣어보세요. (버전마다 다를 수 있음)
                try { stopAnimation(0) } catch (e: Exception) { }

                isTouchable = true

                onSingleTapConfirmed = {
                    handleTreasureFound(this)
                    true
                }
            }

            modelNode.parent = anchorNode

            // 안전하게 노드 추가
            sceneView.addChildNode(anchorNode)

            isTreasureSpawned = true
            Toast.makeText(this@MainActivity, "💎 보물상자 발견!", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTreasureFound(modelNode: ModelNode) {
        // 1. 중복 터치 방지
        modelNode.isTouchable = false

        // 2. 애니메이션 재생 (0번 인덱스)
        modelNode.playAnimation(animationIndex = 0, loop = false)

        // 3. 랜덤 보물 데이터 생성
        val treasureId = Random.nextInt(1000, 9999)
        val treasureName = "황금 열쇠"

        // 4. DB 저장
        saveTreasureToDb(treasureId, treasureName)

        // 5. 결과 UI 표시
        lifecycleScope.launch {
            delay(1000) // 애니메이션이 실행될 시간을 줍니다 (1초)

            AlertDialog.Builder(this@MainActivity)
                .setTitle("축하합니다!")
                .setMessage("'$treasureName' (ID: $treasureId)을(를) 획득했습니다!")
                .setPositiveButton("확인") { dialog, _ ->
                    dialog.dismiss()

                    // [수정] 확인 버튼을 누르면 보물상자(와 앵커)를 제거합니다.
                    // modelNode의 부모(AnchorNode)를 파괴하면 자식인 모델도 같이 사라집니다.
                    modelNode.parent?.destroy()

                    // (선택) 만약 보물상자를 다시 찾게 하고 싶다면 아래 주석을 해제하세요.
                    // isTreasureSpawned = false
                    // spawnTreasureWithDelay(3000L)
                }
                .setCancelable(false) // 뒤로가기나 바깥 터치로 닫히지 않게 설정 (선택)
                .show()
        }
    }

    private fun saveTreasureToDb(id: Int, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("DB_SAVE", "저장 완료 - ID: $id, Name: $name")
        }
    }
}