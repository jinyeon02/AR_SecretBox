/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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

namespace hello_ar {
namespace {
constexpr size_t kMaxNumberOfAndroidsToRender = 20;

const glm::vec3 kWhite = {255, 255, 255};

// Assumed distance from the device camera to the surface on which user will
// try to place objects. This value affects the apparent scale of objects
// while the tracking method of the Instant Placement point is
// SCREENSPACE_WITH_APPROXIMATE_DISTANCE. Values in the [0.2, 2.0] meter
// range are a good choice for most AR experiences. Use lower values for AR
// experiences where users are expected to place objects on surfaces close
// to the camera. Use larger values for experiences where the user will
// likely be standing and trying to place an object on the ground or floor
// in front of them.
constexpr float kApproximateDistanceMeters = 1.0f;

void SetColor(float r, float g, float b, float a, float* color4f) {
  color4f[0] = r;
  color4f[1] = g;
  color4f[2] = b;
  color4f[3] = a;
}

}  // namespace

/**
 * Constructor for HelloArApplication.
 * Initializes the AR application with the Android AssetManager for loading resources.
 * 
 * @param asset_manager Android AssetManager for loading 3D models and textures
 */
HelloArApplication::HelloArApplication(AAssetManager* asset_manager)
    : asset_manager_(asset_manager) {}

/**
 * Destructor for HelloArApplication.
 * Cleans up ARCore session and frame resources.
 */
HelloArApplication::~HelloArApplication() {
  if (ar_session_ != nullptr) {
    ArSession_destroy(ar_session_);
    ArFrame_destroy(ar_frame_);
  }
}

/**
 * Pauses the AR session.
 * Should be called when the activity is paused to save resources.
 */
void HelloArApplication::OnPause() {
  LOGI("OnPause()");
  if (ar_session_ != nullptr) {
    ArSession_pause(ar_session_);
  }
}

/**
 * Resumes the AR session.
 * Creates a new AR session if one doesn't exist, or resumes an existing one.
 * Handles ARCore installation and session configuration.
 * 
 * @param env JNI environment
 * @param context Android application context
 * @param activity Android activity instance
 */
void HelloArApplication::OnResume(JNIEnv* env, void* context, void* activity) {
  LOGI("OnResume()");

  if (ar_session_ == nullptr) {
    ArInstallStatus install_status;
    // If install was not yet requested, this is the first time resuming
    // (e.g., user just launched the app)
    bool user_requested_install = !install_requested_;

    // Request ARCore installation if needed
    // NOTE: This can fail in user-facing situations - handle gracefully
    CHECKANDTHROW(
        ArCoreApk_requestInstall(env, activity, user_requested_install,
                                 &install_status) == AR_SUCCESS,
        env, "Please install Google Play Services for AR (ARCore).");

    switch (install_status) {
      case AR_INSTALL_STATUS_INSTALLED:
        // ARCore is installed and ready
        break;
      case AR_INSTALL_STATUS_INSTALL_REQUESTED:
        // Installation was requested - will resume after installation
        install_requested_ = true;
        return;
    }

    // Create AR session
    // NOTE: This can fail - handle gracefully
    CHECKANDTHROW(ArSession_create(env, context, &ar_session_) == AR_SUCCESS,
                  env, "Failed to create AR session.");

    // Configure session with depth and instant placement settings
    ConfigureSession();
    
    // Create AR frame for capturing camera data
    ArFrame_create(ar_session_, &ar_frame_);

    // Set initial display geometry
    ArSession_setDisplayGeometry(ar_session_, display_rotation_, width_,
                                 height_);
  }

  // Resume the AR session
  const ArStatus status = ArSession_resume(ar_session_);
  CHECKANDTHROW(status == AR_SUCCESS, env, "Failed to resume AR session.");
}

/**
 * Called when the OpenGL surface is created.
 * Initializes all OpenGL resources including:
 * - Depth texture for occlusion
 * - Background renderer (camera feed)
 * - Point cloud renderer
 * - 3D object renderer (Andy model)
 * - Plane renderer
 */
void HelloArApplication::OnSurfaceCreated() {
  LOGI("OnSurfaceCreated()");

  // Create depth texture for depth-based occlusion
  depth_texture_.CreateOnGlThread();
  
  // Initialize background renderer (camera feed)
  background_renderer_.InitializeGlContent(asset_manager_,
                                           depth_texture_.GetTextureId());
  
  // Initialize point cloud renderer (for debugging/visualization)
  point_cloud_renderer_.InitializeGlContent(asset_manager_);
  
  // Initialize 3D object renderer with Andy model
  andy_renderer_.InitializeGlContent(asset_manager_, "models/andy.obj",
                                     "models/andy.png");
  
  // Set depth texture for occlusion
  andy_renderer_.SetDepthTexture(depth_texture_.GetTextureId(),
                                 depth_texture_.GetWidth(),
                                 depth_texture_.GetHeight());
  
  // Initialize plane renderer (for visualizing detected planes)
  plane_renderer_.InitializeGlContent(asset_manager_);
}

/**
 * Called when the display geometry changes (rotation, size, etc.).
 * Updates the OpenGL viewport and AR session display geometry.
 * 
 * @param display_rotation Current display rotation (0, 90, 180, 270 degrees)
 * @param width New viewport width in pixels
 * @param height New viewport height in pixels
 */
void HelloArApplication::OnDisplayGeometryChanged(int display_rotation,
                                                  int width, int height) {
  LOGI("OnSurfaceChanged(%d, %d)", width, height);
  
  // Update OpenGL viewport
  glViewport(0, 0, width, height);
  
  // Store new dimensions and rotation
  display_rotation_ = display_rotation;
  width_ = width;
  height_ = height;
  
  // Update AR session with new display geometry
  if (ar_session_ != nullptr) {
    ArSession_setDisplayGeometry(ar_session_, display_rotation, width, height);
  }
}

/**
 * Main rendering loop called on every frame.
 * This method handles all AR rendering including:
 * - Camera background
 * - Detected planes
 * - AR objects (Andy models)
 * - Point cloud visualization
 * 
 * @param depthColorVisualizationEnabled If true, shows depth map as color overlay
 * @param useDepthForOcclusion If true, uses depth data for realistic object occlusion
 */
void HelloArApplication::OnDrawFrame(bool depthColorVisualizationEnabled,
                                     bool useDepthForOcclusion) {
  // Clear the screen with a light gray background
  glClearColor(0.9f, 0.9f, 0.9f, 1.0f);
  glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);

  // Enable face culling and depth testing for 3D rendering
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

  ArCamera* ar_camera;
  ArFrame_acquireCamera(ar_session_, ar_frame_, &ar_camera);

  int32_t geometry_changed = 0;
  ArFrame_getDisplayGeometryChanged(ar_session_, ar_frame_, &geometry_changed);
  if (geometry_changed != 0 || !calculate_uv_transform_) {
    // The UV Transform represents the transformation between screenspace in
    // normalized units and screenspace in units of pixels.  Having the size of
    // each pixel is necessary in the virtual object shader, to perform
    // kernel-based blur effects.
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
  ArLightEstimate* ar_light_estimate;
  ArLightEstimateState ar_light_estimate_state;
  ArLightEstimate_create(ar_session_, &ar_light_estimate);

  ArFrame_getLightEstimate(ar_session_, ar_frame_, ar_light_estimate);
  ArLightEstimate_getState(ar_session_, ar_light_estimate,
                           &ar_light_estimate_state);

  // Set light intensity to default. Intensity value ranges from 0.0f to 1.0f.
  // The first three components are color scaling factors.
  // The last one is the average pixel intensity in gamma space.
  float color_correction[4] = {1.f, 1.f, 1.f, 1.f};
  if (ar_light_estimate_state == AR_LIGHT_ESTIMATE_STATE_VALID) {
    ArLightEstimate_getColorCorrection(ar_session_, ar_light_estimate,
                                       color_correction);
  }

  ArLightEstimate_destroy(ar_light_estimate);
  ar_light_estimate = nullptr;

  // Update and render planes.
  ArTrackableList* plane_list = nullptr;
  ArTrackableList_create(ar_session_, &plane_list);
  CHECK(plane_list != nullptr);

  ArTrackableType plane_tracked_type = AR_TRACKABLE_PLANE;
  ArSession_getAllTrackables(ar_session_, plane_tracked_type, plane_list);

  int32_t plane_list_size = 0;
  ArTrackableList_getSize(ar_session_, plane_list, &plane_list_size);
  plane_count_ = plane_list_size;

  for (int i = 0; i < plane_list_size; ++i) {
    ArTrackable* ar_trackable = nullptr;
    ArTrackableList_acquireItem(ar_session_, plane_list, i, &ar_trackable);
    ArPlane* ar_plane = ArAsPlane(ar_trackable);
    ArTrackingState out_tracking_state;
    ArTrackable_getTrackingState(ar_session_, ar_trackable,
                                 &out_tracking_state);

    ArPlane* subsume_plane;
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
  for (auto& colored_anchor : anchors_) {
    ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
    ArAnchor_getTrackingState(ar_session_, colored_anchor.anchor,
                              &tracking_state);
    if (tracking_state == AR_TRACKING_STATE_TRACKING) {
      UpdateAnchorColor(&colored_anchor);
      // Render object only if the tracking state is AR_TRACKING_STATE_TRACKING.
      util::GetTransformMatrixFromAnchor(*colored_anchor.anchor, ar_session_,
                                         &model_mat);
      andy_renderer_.Draw(projection_mat, view_mat, model_mat, color_correction,
                          colored_anchor.color);
    }
  }

  // Update and render point cloud.
  ArPointCloud* ar_point_cloud = nullptr;
  ArStatus point_cloud_status =
      ArFrame_acquirePointCloud(ar_session_, ar_frame_, &ar_point_cloud);
  if (point_cloud_status == AR_SUCCESS) {
    point_cloud_renderer_.Draw(projection_mat * view_mat, ar_session_,
                               ar_point_cloud);
    ArPointCloud_release(ar_point_cloud);
  }
}

/**
 * Checks if the device supports depth sensing.
 * @return True if depth sensing is supported, false otherwise
 */
bool HelloArApplication::IsDepthSupported() {
  int32_t is_supported = 0;
  ArSession_isDepthModeSupported(ar_session_, AR_DEPTH_MODE_AUTOMATIC,
                                 &is_supported);
  return is_supported;
}

/**
 * Configures the AR session with depth and instant placement settings.
 * This is called when the session is created or when settings change.
 */
void HelloArApplication::ConfigureSession() {
  const bool is_depth_supported = IsDepthSupported();

  ArConfig* ar_config = nullptr;
  ArConfig_create(ar_session_, &ar_config);
  
  // Configure depth mode
  if (is_depth_supported) {
    ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_AUTOMATIC);
  } else {
    ArConfig_setDepthMode(ar_session_, ar_config, AR_DEPTH_MODE_DISABLED);
  }

  // Configure instant placement mode
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

/**
 * Updates AR session settings.
 * Reconfigures the session with new settings.
 * 
 * @param is_instant_placement_enabled If true, enables instant placement mode
 */
void HelloArApplication::OnSettingsChange(bool is_instant_placement_enabled) {
  is_instant_placement_enabled_ = is_instant_placement_enabled;

  if (ar_session_ != nullptr) {
    ConfigureSession();
  }
}

/**
 * Handles touch events on the AR screen.
 * This method ONLY processes touches on existing AR objects (changes their color).
 * It does NOT create new objects - that is handled by SpawnObjectAtScreenCenter().
 * 
 * IMPORTANT: This method will NOT create new anchors. It only checks if an existing
 * anchor was touched by comparing hit test results with the anchors_ list.
 * 
 * @param x Screen x coordinate in pixels where the user touched
 * @param y Screen y coordinate in pixels where the user touched
 */
void HelloArApplication::OnTouched(float x, float y) {
  if (ar_frame_ == nullptr || ar_session_ == nullptr) {
    return;
  }

  // If no objects exist, there's nothing to touch
  if (anchors_.empty()) {
    return;
  }

  // Perform hit test at the touch location
  ArHitResultList* hit_result_list = nullptr;
  ArHitResultList_create(ar_session_, &hit_result_list);
  CHECK(hit_result_list);

  ArFrame_hitTest(ar_session_, ar_frame_, x, y, hit_result_list);

  int32_t hit_list_size = 0;
  ArHitResultList_getSize(ar_session_, hit_result_list, &hit_list_size);

  // Check if any existing objects were touched
  // We compare hit positions with existing anchor positions to detect touches
  for (int32_t i = 0; i < hit_list_size; ++i) {
    ArHitResult* ar_hit = nullptr;
    ArHitResult_create(ar_session_, &ar_hit);
    ArHitResultList_getItem(ar_session_, hit_result_list, i, ar_hit);

    if (ar_hit == nullptr) {
      continue;
    }

    // Get the hit pose to compare with existing anchor positions
    util::ScopedArPose hit_pose(ar_session_);
    ArHitResult_getHitPose(ar_session_, ar_hit, hit_pose.GetArPose());
    
    float hit_pose_raw[7] = {0.f};
    ArPose_getPoseRaw(ar_session_, hit_pose.GetArPose(), hit_pose_raw);
    glm::vec3 hit_position(hit_pose_raw[4], hit_pose_raw[5], hit_pose_raw[6]);

    // Check if this hit is close to any existing anchor
    for (auto& colored_anchor : anchors_) {
      ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
      ArAnchor_getTrackingState(ar_session_, colored_anchor.anchor, &tracking_state);
      
      if (tracking_state != AR_TRACKING_STATE_TRACKING) {
        continue;
      }

      // Get anchor pose
      util::ScopedArPose anchor_pose(ar_session_);
      ArAnchor_getPose(ar_session_, colored_anchor.anchor, anchor_pose.GetArPose());
      
      float anchor_pose_raw[7] = {0.f};
      ArPose_getPoseRaw(ar_session_, anchor_pose.GetArPose(), anchor_pose_raw);
      glm::vec3 anchor_position(anchor_pose_raw[4], anchor_pose_raw[5], anchor_pose_raw[6]);

      // Check if hit position is close to anchor position (within 0.1 meters)
      float distance = glm::distance(hit_position, anchor_position);
      if (distance < 0.1f) {
        // Object was touched - increment touch count and change color
        colored_anchor.touch_count++;

        // Toggle color between red and green on each touch
        if (colored_anchor.touch_count % 2 != 0) {
          // Odd touches: red
          SetColor(255.0f, 0.0f, 0.0f, 255.0f, colored_anchor.color);
        } else {
          // Even touches: green
          SetColor(0.0f, 255.0f, 0.0f, 255.0f, colored_anchor.color);
        }

        ArHitResult_destroy(ar_hit);
        ArHitResultList_destroy(hit_result_list);
        return; // Object was touched, no need to continue
      }
    }
    ArHitResult_destroy(ar_hit);
  }

  // No existing object was touched - clean up and return
  // IMPORTANT: We do NOT create new objects here - that's done by SpawnObjectAtScreenCenter()
  ArHitResultList_destroy(hit_result_list);
}

/**
 * Automatically spawns a new AR object at the center of the screen.
 * This method is called after a delay (e.g., 10 seconds) to automatically
 * create an object without user interaction.
 * The object is placed using hit testing at the screen center.
 * 
 * This method only creates an object if no objects currently exist (anchors_.empty()).
 * This ensures that only the initial object is created automatically.
 */
void HelloArApplication::SpawnObjectAtScreenCenter() {
  if (ar_frame_ == nullptr || ar_session_ == nullptr) {
    return;
  }

  // Only create object if no objects exist yet
  // This ensures automatic spawning only happens once for the initial object
  if (!anchors_.empty()) {
    return;
  }

  // Check if we've reached the maximum number of objects (safety check)
  if (anchors_.size() >= kMaxNumberOfAndroidsToRender) {
    return;
  }

  // Perform hit test at screen center
  float center_x = width_ / 2.0f;
  float center_y = height_ / 2.0f;

  ArHitResultList* hit_result_list = nullptr;
  ArHitResultList_create(ar_session_, &hit_result_list);
  CHECK(hit_result_list);

  ArFrame_hitTest(ar_session_, ar_frame_, center_x, center_y, hit_result_list);

  int32_t hit_list_size = 0;
  ArHitResultList_getSize(ar_session_, hit_result_list, &hit_list_size);

  // Find the first valid hit result (prefer planes, then instant placement)
  for (int32_t i = 0; i < hit_list_size; ++i) {
    ArHitResult* ar_hit = nullptr;
    ArHitResult_create(ar_session_, &ar_hit);
    ArHitResultList_getItem(ar_session_, hit_result_list, i, ar_hit);

    if (ar_hit == nullptr) {
      continue;
    }

    // Check what type of trackable was hit
    ArTrackable* ar_trackable = nullptr;
    ArHitResult_acquireTrackable(ar_session_, ar_hit, &ar_trackable);
    ArTrackableType trackable_type = AR_TRACKABLE_NOT_VALID;
    ArTrackable_getType(ar_session_, ar_trackable, &trackable_type);

    // Only create anchor on planes or instant placement points
    if (trackable_type == AR_TRACKABLE_PLANE ||
        trackable_type == AR_TRACKABLE_INSTANT_PLACEMENT_POINT) {
      ArTrackingState tracking_state = AR_TRACKING_STATE_STOPPED;
      ArTrackable_getTrackingState(ar_session_, ar_trackable, &tracking_state);

      if (tracking_state == AR_TRACKING_STATE_TRACKING) {
        // Create a new anchor at this hit location
        ArAnchor* anchor = nullptr;
        if (ArHitResult_acquireNewAnchor(ar_session_, ar_hit, &anchor) != AR_SUCCESS) {
          ArTrackable_release(ar_trackable);
          ArHitResult_destroy(ar_hit);
          continue;
        }

        // Create a new colored anchor entry
        ColoredAnchor colored_anchor;
        colored_anchor.anchor = anchor;
        colored_anchor.trackable = ar_trackable;
        colored_anchor.touch_count = 0;
        UpdateAnchorColor(&colored_anchor);

        anchors_.push_back(colored_anchor);

        ArTrackable_release(ar_trackable);
        ArHitResult_destroy(ar_hit);
        ArHitResultList_destroy(hit_result_list);
        return; // Successfully created object
      }
    }

    if (ar_trackable != nullptr) {
      ArTrackable_release(ar_trackable);
    }
    ArHitResult_destroy(ar_hit);
  }

  // No valid surface found for object placement
  ArHitResultList_destroy(hit_result_list);
}

/**
 * Updates the color of an anchor based on its trackable type.
 * Different trackable types (planes, points, etc.) get different colors
 * to help users understand what type of surface the object is placed on.
 * 
 * @param colored_anchor Pointer to the colored anchor whose color should be updated
 */
void HelloArApplication::UpdateAnchorColor(ColoredAnchor* colored_anchor) {
  ArTrackable* ar_trackable = colored_anchor->trackable;
  float* color = colored_anchor->color;

  ArTrackableType ar_trackable_type;
  ArTrackable_getType(ar_session_, ar_trackable, &ar_trackable_type);

  // Set color based on trackable type
  if (ar_trackable_type == AR_TRACKABLE_POINT) {
    // Blue for feature points
    SetColor(66.0f, 133.0f, 244.0f, 255.0f, color);
    return;
  }

  if (ar_trackable_type == AR_TRACKABLE_PLANE) {
    // Green for detected planes
    SetColor(139.0f, 195.0f, 74.0f, 255.0f, color);
    return;
  }

  if (ar_trackable_type == AR_TRACKABLE_DEPTH_POINT) {
    // Red for depth points
    SetColor(199.0f, 8.0f, 65.0f, 255.0f, color);
    return;
  }

  if (ar_trackable_type == AR_TRACKABLE_INSTANT_PLACEMENT_POINT) {
    ArInstantPlacementPoint* ar_instant_placement_point =
        ArAsInstantPlacementPoint(ar_trackable);
    ArInstantPlacementPointTrackingMethod tracking_method;
    ArInstantPlacementPoint_getTrackingMethod(
        ar_session_, ar_instant_placement_point, &tracking_method);
    
    if (tracking_method ==
        AR_INSTANT_PLACEMENT_POINT_TRACKING_METHOD_FULL_TRACKING) {
      // Yellow for fully tracked instant placement
      SetColor(255.0f, 255.0f, 137.0f, 255.0f, color);
      return;
    } else if (
        tracking_method ==
        AR_INSTANT_PLACEMENT_POINT_TRACKING_METHOD_SCREENSPACE_WITH_APPROXIMATE_DISTANCE) {
      // White for approximate instant placement
      SetColor(255.0f, 255.0f, 255.0f, 255.0f, color);
      return;
    }
  }

  // Fallback: transparent (should not happen in normal operation)
  SetColor(0.0f, 0.0f, 0.0f, 0.0f, color);
}

/**
 * Returns a transformation matrix that converts screen space UVs to texture coordinates.
 * This matrix accounts for device orientation and ensures the camera feed texture
 * is correctly mapped to the screen.
 * 
 * @param session ARCore session
 * @param frame Current AR frame
 * @return 3x3 transformation matrix for UV coordinates
 */
glm::mat3 HelloArApplication::GetTextureTransformMatrix(
    const ArSession* session, const ArFrame* frame) {
  float frameTransform[6];
  float uvTransform[9];
  // XY pairs of coordinates in NDC space that constitute the origin and points
  // along the two principal axes.
  const float ndcBasis[6] = {0, 0, 1, 0, 0, 1};
  ArFrame_transformCoordinates2d(
      session, frame, AR_COORDINATES_2D_OPENGL_NORMALIZED_DEVICE_COORDINATES, 3,
      ndcBasis, AR_COORDINATES_2D_TEXTURE_NORMALIZED, frameTransform);

  // Convert the transformed points into an affine transform and transpose it.
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
}  // namespace hello_ar
