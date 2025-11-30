/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "hello_ar_application.h"

#include <android/asset_manager.h>

#include <array>

#include "arcore_c_api.h"
#include "plane_renderer.h"
#include "util.h"

// GLM includes for vector math
#include <glm.hpp>
#include <gtc/type_ptr.hpp>
#include <gtx/norm.hpp> // for glm::distance

namespace hello_ar {
    namespace {
        constexpr size_t kMaxNumberOfAndroidsToRender = 20;

        const glm::vec3 kWhite = {255, 255, 255};

// Assumed distance from the device camera to the surface on which user will
// try to place objects.
        constexpr float kApproximateDistanceMeters = 1.0f;

        void SetColor(float r, float g, float b, float a, float *color4f) {
            color4f[0] = r;
            color4f[1] = g;
            color4f[2] = b;
            color4f[3] = a;
        }

    }  // namespace

    HelloArApplication::HelloArApplication(AAssetManager *asset_manager)
            : asset_manager_(asset_manager) {}

    HelloArApplication::~HelloArApplication() {
        if (ar_session_ != nullptr) {
            ArSession_destroy(ar_session_);
            ArFrame_destroy(ar_frame_);
        }
    }

    void HelloArApplication::OnPause() {
        LOGI("OnPause()");
        if (ar_session_ != nullptr) {
            ArSession_pause(ar_session_);
        }
    }

    void HelloArApplication::OnResume(JNIEnv *env, void *context, void *activity) {
        LOGI("OnResume()");

        if (ar_session_ == nullptr) {
            ArInstallStatus install_status;
            bool user_requested_install = !install_requested_;

            CHECKANDTHROW(
                    ArCoreApk_requestInstall(env, activity, user_requested_install,
                                             &install_status) == AR_SUCCESS,
                    env, "Please install Google Play Services for AR (ARCore).");

            switch (install_status) {
                case AR_INSTALL_STATUS_INSTALLED:
                    break;
                case AR_INSTALL_STATUS_INSTALL_REQUESTED:
                    install_requested_ = true;
                    return;
            }

            CHECKANDTHROW(ArSession_create(env, context, &ar_session_) == AR_SUCCESS,
                          env, "Failed to create AR session.");

            ConfigureSession();
            ArFrame_create(ar_session_, &ar_frame_);

            ArSession_setDisplayGeometry(ar_session_, display_rotation_, width_,
                                         height_);
        }

        const ArStatus status = ArSession_resume(ar_session_);
        CHECKANDTHROW(status == AR_SUCCESS, env, "Failed to resume AR session.");
    }

    void HelloArApplication::OnSurfaceCreated() {
        LOGI("OnSurfaceCreated()");

        depth_texture_.CreateOnGlThread();
        background_renderer_.InitializeGlContent(asset_manager_,
                                                 depth_texture_.GetTextureId());
        point_cloud_renderer_.InitializeGlContent(asset_manager_);
        andy_renderer_.InitializeGlContent(asset_manager_, "models/andy.obj",
                                           "models/andy.png");
        andy_renderer_.SetDepthTexture(depth_texture_.GetTextureId(),
                                       depth_texture_.GetWidth(),
                                       depth_texture_.GetHeight());
        plane_renderer_.InitializeGlContent(asset_manager_);
    }

    void HelloArApplication::OnDisplayGeometryChanged(int display_rotation,
                                                      int width, int height) {
        LOGI("OnSurfaceChanged(%d, %d)", width, height);
        glViewport(0, 0, width, height);
        display_rotation_ = display_rotation;
        width_ = width;
        height_ = height;
        if (ar_session_ != nullptr) {
            ArSession_setDisplayGeometry(ar_session_, display_rotation, width, height);
        }
    }

    void HelloArApplication::OnDrawFrame(bool depthColorVisualizationEnabled,
                                         bool useDepthForOcclusion) {
        // Render the scene.
        glClearColor(0.9f, 0.9f, 0.9f, 1.0f);
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);

        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        if (ar_session_ == nullptr) return;

        ArSession_setCameraTextureName(ar_session_,
                                       background_renderer_.GetTextureId());

        // Update session to get current frame and render camera background.
        if (ArSession_update(ar_session_, ar_frame_) != AR_SUCCESS) {
            LOGE("HelloArApplication::OnDrawFrame ArSession_update error");
        }

        andy_renderer_.SetDepthTexture(depth_texture_.GetTextureId(),
                                       depth_texture_.GetWidth(),
                                       depth_texture_.GetHeight());

        ArCamera *ar_camera;
        ArFrame_acquireCamera(ar_session_, ar_frame_, &ar_camera);

        int32_t geometry_changed = 0;
        ArFrame_getDisplayGeometryChanged(ar_session_, ar_frame_, &geometry_changed);
        if (geometry_changed != 0 || !calculate_uv_transform_) {
            calculate_uv_transform_ = false;
            glm::mat3 transform = GetTextureTransformMatrix(ar_session_, ar_frame_);
            andy_renderer_.SetUvTransformMatrix(transform);
        }

        glm::mat4 view_mat;
        glm::mat4 projection_mat;
        ArCamera_getViewMatrix(ar_session_, ar_camera, glm::value_ptr(view_mat));
        ArCamera_getProjectionMatrix(ar_session_, ar_camera,
                /*near=*/0.1f, /*far=*/100.f,
                                     glm::value_ptr(projection_mat));

        background_renderer_.Draw(ar_session_, ar_frame_,
                                  depthColorVisualizationEnabled);

        ArTrackingState camera_tracking_state;
        ArCamera_getTrackingState(ar_session_, ar_camera, &camera_tracking_state);
        ArCamera_release(ar_camera);

        // If the camera isn't tracking don't bother rendering other objects.
        if (camera_tracking_state != AR_TRACKING_STATE_TRACKING) {
            return;
        }

        int32_t is_depth_supported = 0;
        ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_AUTOMATIC,
                                       &is_depth_supported);
        if (is_depth_supported) {
            depth_texture_.UpdateWithDepthImageOnGlThread(*ar_session_, *ar_frame_);
        }

        // Get light estimation value.
        ArLightEstimate *ar_light_estimate;
        ArLightEstimateState ar_light_estimate_state;
        ArLightEstimate_create(ar_session_, &ar_light_estimate);

        ArFrame_getLightEstimate(ar_session_, ar_frame_, ar_light_estimate);
        ArLightEstimate_getState(ar_session_, ar_light_estimate,
                                 &ar_light_estimate_state);

        float color_correction[4] = {1.f, 1.f, 1.f, 1.f};
        if (ar_light_estimate_state == AR_LIGHT_ESTIMATE_STATE_VALID) {
            ArLightEstimate_getColorCorrection(ar_session_, ar_light_estimate,
                                               color_correction);
        }

        ArLightEstimate_destroy(ar_light_estimate);
        ar_light_estimate = nullptr;

        // Update and render planes.
        ArTrackableList *plane_list = nullptr;
        ArTrackableList_create(ar_session_, &plane_list);
        CHECK(plane_list != nullptr);

        ArTrackableType plane_tracked_type = AR_TRACKABLE_PLANE;
        ArSession_getAllTrackables(ar_session_, plane_tracked_type, plane_list);

        int32_t plane_list_size = 0;
        ArTrackableList_getSize(ar_session_, plane_list, &plane_list_size);
        plane_count_ = plane_list_size;

        for (int i = 0; i < plane_list_size; ++i) {
            ArTrackable *ar_trackable = nullptr;
            ArTrackableList_acquireItem(ar_session_, plane_list, i, &ar_trackable);
            ArPlane *ar_plane = ArAsPlane(ar_trackable);
            ArTrackingState out_tracking_state;
            ArTrackable_getTrackingState(ar_session_, ar_trackable,
                                         &out_tracking_state);

            ArPlane *subsume_plane;
            ArPlane_acquireSubsumedBy(ar_session_, ar_plane, &subsume_plane);
            if (subsume_plane != nullptr) {
                ArTrackable_release(ArAsTrackable(subsume_plane));
                ArTrackable_release(ar_trackable);
                continue;
            }

            if (ArTrackingState::AR_TRACKING_STATE_TRACKING != out_tracking_state) {
                ArTrackable_release(ar_trackable);
                continue;
            }

            plane_renderer_.Draw(projection_mat, view_mat, *ar_session_, *ar_plane);
            ArTrackable_release(ar_trackable);
        }

        ArTrackableList_destroy(plane_list);
        plane_list = nullptr;

        andy_renderer_.setUseDepthForOcclusion(asset_manager_, useDepthForOcclusion);

        // Render Andy objects.
        glm::mat4 model_mat(1.0f);
        for (auto &colored_anchor: anchors_) {
            ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
            ArAnchor_getTrackingState(ar_session_, colored_anchor.anchor,
                                      &tracking_state);
            if (tracking_state == AR_TRACKING_STATE_TRACKING) {
                // ⭐ 주석 처리: UpdateAnchorColor 호출을 건너뛰어 터치 이벤트로 변경된 색상을 유지합니다.
                // UpdateAnchorColor(&colored_anchor);

                util::GetTransformMatrixFromAnchor(*colored_anchor.anchor, ar_session_,
                                                   &model_mat);
                andy_renderer_.Draw(projection_mat, view_mat, model_mat, color_correction,
                                    colored_anchor.color);
            }
        }

        // Update and render point cloud.
        ArPointCloud *ar_point_cloud = nullptr;
        ArStatus point_cloud_status =
                ArFrame_acquirePointCloud(ar_session_, ar_frame_, &ar_point_cloud);
        if (point_cloud_status == AR_SUCCESS) {
            point_cloud_renderer_.Draw(projection_mat * view_mat, ar_session_,
                                       ar_point_cloud);
            ArPointCloud_release(ar_point_cloud);
        }
    }

    bool HelloArApplication::IsDepthSupported() {
        int32_t is_supported = 0;
        ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_AUTOMATIC,
                                       &is_supported);
        return is_supported;
    }

    void HelloArApplication::ConfigureSession() {
        const bool is_depth_supported = IsDepthSupported();

        ArConfig *ar_config = nullptr;
        ArConfig_create(ar_session_, &ar_config);
        if (is_depth_supported) {
            ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_AUTOMATIC);
        } else {
            ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_DISABLED);
        }

        if (is_instant_placement_enabled_) {
            ArConfig_setInstantPlacementMode(ar_session_, ar_config,
                                             AR_INSTANT_PLACEMENT_MODE_LOCAL_Y_UP);
        } else {
            ArConfig_setInstantPlacementMode(ar_session_, ar_config,
                                             AR_INSTANT_PLACEMENT_MODE_DISABLED);
        }
        CHECK(ar_config);
        CHECK(ArSession_configure(ar_session_, ar_config) == AR_SUCCESS);
        ArConfig_destroy(ar_config);
    }

    void HelloArApplication::OnSettingsChange(bool is_instant_placement_enabled) {
        is_instant_placement_enabled_ = is_instant_placement_enabled;

        if (ar_session_ != nullptr) {
            ConfigureSession();
        }
    }

// ⭐ OnTouched 함수: 기존 객체 터치 상호작용만 처리합니다.
    void HelloArApplication::OnTouched(float x, float y) {
        if (ar_frame_ == nullptr || ar_session_ == nullptr) {
            return;
        }

        // 객체가 없으면 할 일이 없습니다.
        if (anchors_.empty()) {
            return;
        }

        // Hit Test 수행
        ArHitResultList *hit_result_list = nullptr;
        ArHitResultList_create(ar_session_, &hit_result_list);
        CHECK(hit_result_list);

        ArFrame_hitTest(ar_session_, ar_frame_, x, y, hit_result_list);

        int32_t hit_list_size = 0;
        ArHitResultList_getSize(ar_session_, hit_result_list, &hit_list_size);

        // 기존 객체 터치 확인
        for (int32_t i = 0; i < hit_list_size; ++i) {
            ArHitResult *ar_hit = nullptr;
            ArHitResult_create(ar_session_, &ar_hit);
            ArHitResultList_getItem(ar_session_, hit_result_list, i, ar_hit);

            if (ar_hit == nullptr) {
                continue;
            }

            // Hit Pose 획득
            util::ScopedArPose hit_pose(ar_session_);
            ArHitResult_getHitPose(ar_session_, ar_hit, hit_pose.GetArPose());

            float hit_pose_raw[7] = {0.f};
            ArPose_getPoseRaw(ar_session_, hit_pose.GetArPose(), hit_pose_raw);
            glm::vec3 hit_position(hit_pose_raw[4], hit_pose_raw[5], hit_pose_raw[6]);

            // 기존 Anchor 목록과 비교
            for (auto &colored_anchor: anchors_) {
                ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
                ArAnchor_getTrackingState(ar_session_, colored_anchor.anchor, &tracking_state);

                if (tracking_state != AR_TRACKING_STATE_TRACKING) {
                    continue;
                }

                util::ScopedArPose anchor_pose(ar_session_);
                ArAnchor_getPose(ar_session_, colored_anchor.anchor, anchor_pose.GetArPose());

                float anchor_pose_raw[7] = {0.f};
                ArPose_getPoseRaw(ar_session_, anchor_pose.GetArPose(), anchor_pose_raw);
                glm::vec3 anchor_position(anchor_pose_raw[4], anchor_pose_raw[5],
                                          anchor_pose_raw[6]);

                // 0.1m 이내 거리일 경우 객체가 터치된 것으로 간주
                float distance = glm::distance(hit_position, anchor_position);
                if (distance < 0.1f) {
                    // ⭐ 터치 이벤트: 카운트 증가 및 색상 변경
                    colored_anchor.touch_count++;

                    if (colored_anchor.touch_count % 2 != 0) {
                        SetColor(255.0f, 0.0f, 0.0f, 255.0f, colored_anchor.color);
                    } else {
                        SetColor(0.0f, 255.0f, 0.0f, 255.0f, colored_anchor.color);
                    }

                    ArHitResult_destroy(ar_hit);
                    ArHitResultList_destroy(hit_result_list);
                    return; // 상호작용 후 종료
                }
            }
            ArHitResult_destroy(ar_hit);
        }

        // 새 객체 생성 로직은 제거되었습니다.
        ArHitResultList_destroy(hit_result_list);
    }


// ⭐ SpawnObjectAtScreenCenter 함수: 최초 1회 객체 생성
    void HelloArApplication::SpawnObjectAtScreenCenter() {
        if (ar_frame_ == nullptr || ar_session_ == nullptr) {
            return;
        }

        // 객체가 이미 존재하면 생성하지 않습니다.
        if (!anchors_.empty()) {
            return;
        }

        // 카메라 트래킹 상태 확인 (안정적일 때만 시도)
        ArCamera *ar_camera;
        ArFrame_acquireCamera(ar_session_, ar_frame_, &ar_camera);
        ArTrackingState camera_tracking_state;
        ArCamera_getTrackingState(ar_session_, ar_camera, &camera_tracking_state);
        ArCamera_release(ar_camera);

        if (camera_tracking_state != AR_TRACKING_STATE_TRACKING) {
            LOGI("Tracking is not stable enough to spawn object.");
            return;
        }

        // 화면 중앙 Hit Test 좌표 계산
        float center_x = width_ / 2.0f;
        float center_y = height_ / 2.0f;

        ArHitResultList *hit_result_list = nullptr;
        ArHitResultList_create(ar_session_, &hit_result_list);
        CHECK(hit_result_list);

        ArFrame_hitTest(ar_session_, ar_frame_, center_x, center_y, hit_result_list);

        int32_t hit_list_size = 0;
        ArHitResultList_getSize(ar_session_, hit_result_list, &hit_list_size);

        // 첫 번째 유효한 Hit Result를 찾아 Anchor 생성
        for (int32_t i = 0; i < hit_list_size; ++i) {
            ArHitResult *ar_hit = nullptr;
            ArHitResult_create(ar_session_, &ar_hit);
            ArHitResultList_getItem(ar_session_, hit_result_list, i, ar_hit);

            if (ar_hit == nullptr) continue;

            ArTrackable *ar_trackable = nullptr;
            ArHitResult_acquireTrackable(ar_session_, ar_hit, &ar_trackable);
            ArTrackableType trackable_type = AR_TRACKABLE_NOT_VALID;
            ArTrackable_getType(ar_session_, ar_trackable, &trackable_type);

            // 평면(Plane) 또는 Instant Placement Point일 때만 Anchor 생성
            if (trackable_type == AR_TRACKABLE_PLANE ||
                trackable_type == AR_TRACKABLE_INSTANT_PLACEMENT_POINT) {
                ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
                ArTrackable_getTrackingState(ar_session_, ar_trackable, &tracking_state);

                if (tracking_state == AR_TRACKING_STATE_TRACKING) {
                    // Anchor 생성
                    ArAnchor *anchor = nullptr;
                    if (ArHitResult_acquireNewAnchor(ar_session_, ar_hit, &anchor) != AR_SUCCESS) {
                        ArTrackable_release(ar_trackable);
                        ArHitResult_destroy(ar_hit);
                        continue;
                    }

                    // Anchor 목록에 추가
                    ColoredAnchor colored_anchor;
                    colored_anchor.anchor = anchor;
                    colored_anchor.trackable = ar_trackable;
                    colored_anchor.touch_count = 0;
                    UpdateAnchorColor(&colored_anchor); // 초기 색상 설정

                    anchors_.push_back(colored_anchor);

                    ArTrackable_release(ar_trackable);
                    ArHitResult_destroy(ar_hit);
                    ArHitResultList_destroy(hit_result_list);

                    LOGI("Successfully spawned initial object at screen center.");
                    return; // 성공적으로 생성 후 종료
                }
            }

            if (ar_trackable != nullptr) {
                ArTrackable_release(ar_trackable);
            }
            ArHitResult_destroy(ar_hit);
        }

        ArHitResultList_destroy(hit_result_list);
        LOGI("Failed to find a valid surface for initial object spawn.");
    }

// ⭐ HasDetectedPlanes 구현: JNI에서 호출되어 평면 감지 여부를 Kotlin에 전달
    bool HelloArApplication::HasDetectedPlanes() const {
        if (ar_session_ == nullptr) {
            return false;
        }

        ArTrackableList *plane_list = nullptr;
        ArTrackableList_create(ar_session_, &plane_list);
        ArTrackableType plane_tracked_type = AR_TRACKABLE_PLANE;

        ArSession_getAllTrackables(ar_session_, plane_tracked_type, plane_list);

        int32_t plane_list_size = 0;
        ArTrackableList_getSize(ar_session_, plane_list, &plane_list_size);

        ArTrackableList_destroy(plane_list);

        // 1개 이상의 평면이 감지되었으면 true 반환
        return plane_list_size > 0;
    }

    void HelloArApplication::UpdateAnchorColor(ColoredAnchor *colored_anchor) {
        ArTrackable *ar_trackable = colored_anchor->trackable;
        float *color = colored_anchor->color;

        ArTrackableType ar_trackable_type;
        ArTrackable_getType(ar_session_, ar_trackable, &ar_trackable_type);

        if (ar_trackable_type == AR_TRACKABLE_POINT) {
            SetColor(66.0f, 133.0f, 244.0f, 255.0f, color);
            return;
        }

        if (ar_trackable_type == AR_TRACKABLE_PLANE) {
            SetColor(139.0f, 195.0f, 74.0f, 255.0f, color);
            return;
        }

        if (ar_trackable_type == AR_TRACKABLE_DEPTH_POINT) {
            SetColor(199.0f, 8.0f, 65.0f, 255.0f, color);
            return;
        }

        if (ar_trackable_type == AR_TRACKABLE_INSTANT_PLACEMENT_POINT) {
            ArInstantPlacementPoint *ar_instant_placement_point =
                    ArAsInstantPlacementPoint(ar_trackable);
            ArInstantPlacementPointTrackingMethod tracking_method;
            ArInstantPlacementPoint_getTrackingMethod(
                    ar_session_, ar_instant_placement_point, &tracking_method);
            if (tracking_method ==
                AR_INSTANT_PLACEMENT_POINT_TRACKING_METHOD_FULL_TRACKING) {
                SetColor(255.0f, 255.0f, 137.0f, 255.0f, color);
                return;
            } else if (
                    tracking_method ==
                    AR_INSTANT_PLACEMENT_POINT_TRACKING_METHOD_SCREENSPACE_WITH_APPROXIMATE_DISTANCE) {  // NOLINT
                SetColor(255.0f, 255.0f, 255.0f, 255.0f, color);
                return;
            }
        }

        // Fallback color
        SetColor(0.0f, 0.0f, 0.0f, 0.0f, color);
    }

    glm::mat3 HelloArApplication::GetTextureTransformMatrix(
            const ArSession *session, const ArFrame *frame) {
        float frameTransform[6];
        float uvTransform[9];
        const float ndcBasis[6] = {0, 0, 1, 0, 0, 1};
        ArFrame_transformCoordinates2d(
                session, frame, AR_COORDINATES_2D_OPENGL_NORMALIZED_DEVICE_COORDINATES, 3,
                ndcBasis, AR_COORDINATES_2D_TEXTURE_NORMALIZED, frameTransform);

        float ndcOriginX = frameTransform[0];
        float ndcOriginY = frameTransform[1];
        uvTransform[0] = frameTransform[2] - ndcOriginX;
        uvTransform[1] = frameTransform[3] - ndcOriginY;
        uvTransform[2] = 0;
        uvTransform[3] = frameTransform[4] - ndcOriginX;
        uvTransform[4] = frameTransform[5] - ndcOriginY;
        uvTransform[5] = 0;
        uvTransform[6] = ndcOriginX;
        uvTransform[7] = ndcOriginY;
        uvTransform[8] = 1;

        return glm::make_mat3(uvTransform);
    }
}

size_t hello_ar::HelloArApplication::GetAnchorCount() const {
    // anchors_가 std::vector<ColoredAnchor>로 선언되어 있다고 가정
    return anchors_.size();
}
