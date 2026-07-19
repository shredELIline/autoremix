cmake_minimum_required(VERSION 3.22.1)
project(autoremix_android LANGUAGES C CXX)

find_package(oboe REQUIRED CONFIG)

set(AUTOREMIX_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../audio-core")
set(AUTOREMIX_CORE_HEADER "${AUTOREMIX_CORE_DIR}/include/autoremix/audio_core_c.h")

add_library(autoremix_jni SHARED autoremix_jni.cpp)
target_compile_features(autoremix_jni PRIVATE cxx_std_20)
target_link_libraries(autoremix_jni PRIVATE oboe::oboe android log)

if(EXISTS "${AUTOREMIX_CORE_DIR}/CMakeLists.txt" AND EXISTS "${AUTOREMIX_CORE_HEADER}")
    add_subdirectory("${AUTOREMIX_CORE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/audio-core")
    target_compile_definitions(autoremix_jni PRIVATE AUTOREMIX_HAS_NATIVE_CORE=1)
    target_include_directories(autoremix_jni PRIVATE "${AUTOREMIX_CORE_DIR}/include")
    target_link_libraries(autoremix_jni PRIVATE autoremix_audio_core)
else()
    message(STATUS "audio-core not present; building isolated Tier-C JNI probe")
endif()
