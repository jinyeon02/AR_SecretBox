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
set_target_properties(arcore PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/arcore/libs/arm64-v8a/libarcore_sdk_c.so
)

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
