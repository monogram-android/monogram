cmake_minimum_required(VERSION 3.22.1)

project("presentation")

set(VPX_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libvpx_build/${ANDROID_ABI}")
include_directories(${VPX_PATH}/include)
add_library(libvpx STATIC IMPORTED)
set_target_properties(libvpx PROPERTIES IMPORTED_LOCATION
        "${VPX_PATH}/lib/libvpx.a"
)

add_library(
        native-lib
        SHARED
        native-lib.cpp
        VpxDecoder.cpp
        RLottieDecoder.cpp
        RLottieJni.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LOTTIE_MODULE OFF CACHE BOOL "" FORCE)
set(LOTTIE_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/rlottie)

target_compile_options(native-lib PRIVATE
    -O3
    -fvisibility=hidden
    -fdata-sections
    -ffunction-sections)

target_link_options(native-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
    -Wl,--gc-sections)

#if(NOT CMAKE_BUILD_TYPE MATCHES "Debug")
#    add_compile_options(-flto)
#    add_link_options(-flto)
#endif()

find_library(
        log-lib
        log)

find_library(
        android-lib
        android)

find_library(
        egl-lib
        EGL)

find_library(
        glesv2-lib
        GLESv2)

target_link_libraries(
        native-lib

        libvpx
        rlottie
        mediandk
        jnigraphics

        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${glesv2-lib})
