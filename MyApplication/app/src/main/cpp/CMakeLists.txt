cmake_minimum_required(VERSION 3.10.2)
project("myapplication")

add_library(
        myapplication
        SHARED
        native-lib.cpp
)

# ARCore include 경로
include_directories(${CMAKE_SOURCE_DIR}/src/main/arcore/include)

# ARCore .so 임포트
add_library(arcore SHARED IMPORTED)

if ("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set_target_properties(arcore PROPERTIES
            IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/arcore/libs/arm64-v8a/libarcore_sdk_c.so
    )
elseif ("${ANDROID_ABI}" STREQUAL "x86_64")
    set_target_properties(arcore PROPERTIES
            IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/arcore/libs/x86_64/libarcore_sdk_c.so
    )
endif()

# hello_ar_native 소스들
add_library(hello_ar_native SHARED
        background_renderer.cc
        hello_ar_application.cc
        obj_renderer.cc
        plane_renderer.cc
        point_cloud_renderer.cc
        texture.cc
        util.cc
        Jni_interface.cc
)

target_include_directories( # 프로젝트 이름에 따라 다를 수 있음
        hello_ar_native PUBLIC
        ${CMAKE_SOURCE_DIR}/src.main.arcore.include
        # 또는 GLM 라이브러리가 위치한 경로
        # 예: ${CMAKE_SOURCE_DIR}/src/main/cpp/glm_dir
)

# 링크
target_link_libraries(
        hello_ar_native
        arcore
        android
        log
        GLESv3
)

target_link_libraries(
        myapplication
        android
        log
)
