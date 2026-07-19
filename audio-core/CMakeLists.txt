cmake_minimum_required(VERSION 3.16)

project(autoremix_audio_core VERSION 1.0.0 LANGUAGES C CXX)

set(_autoremix_tests_default OFF)
if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
    set(_autoremix_tests_default ON)
endif()
option(AUTOREMIX_AUDIO_CORE_BUILD_TESTS
    "Build host tests"
    ${_autoremix_tests_default}
)
option(AUTOREMIX_AUDIO_CORE_BUILD_BENCHMARKS
    "Build host benchmarks"
    OFF
)

add_library(autoremix_audio_core
    src/audio_core.cpp
    src/audio_core_c.cpp
)
add_library(autoremix::audio_core ALIAS autoremix_audio_core)

target_compile_features(autoremix_audio_core PUBLIC cxx_std_17)
target_include_directories(autoremix_audio_core
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)
target_compile_definitions(autoremix_audio_core PRIVATE AR_AUDIO_CORE_BUILDING=1)
if(BUILD_SHARED_LIBS)
    target_compile_definitions(autoremix_audio_core PUBLIC AR_AUDIO_CORE_SHARED=1)
endif()

if(AUTOREMIX_AUDIO_CORE_BUILD_BENCHMARKS)
    add_executable(autoremix_audio_core_benchmark
        benchmarks/audio_core_benchmark.cpp
    )
    target_link_libraries(autoremix_audio_core_benchmark
        PRIVATE autoremix_audio_core
    )
    target_compile_features(autoremix_audio_core_benchmark PRIVATE cxx_std_17)
    set_target_properties(autoremix_audio_core_benchmark PROPERTIES
        CXX_EXTENSIONS OFF
    )
    if(MSVC)
        target_compile_options(autoremix_audio_core_benchmark PRIVATE /W4 /permissive-)
    else()
        target_compile_options(autoremix_audio_core_benchmark PRIVATE -Wall -Wextra -Wpedantic)
    endif()
endif()
set_target_properties(autoremix_audio_core PROPERTIES
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)

if(MSVC)
    target_compile_options(autoremix_audio_core PRIVATE /W4 /permissive-)
else()
    target_compile_options(autoremix_audio_core PRIVATE -Wall -Wextra -Wpedantic)
endif()

if(AUTOREMIX_AUDIO_CORE_BUILD_TESTS)
    include(CTest)
    find_package(Threads REQUIRED)

    add_executable(autoremix_audio_core_tests
        tests/audio_core_tests.cpp
        tests/c_api_compile_test.c
    )
    target_link_libraries(autoremix_audio_core_tests
        PRIVATE autoremix_audio_core Threads::Threads
    )
    target_compile_features(autoremix_audio_core_tests PRIVATE cxx_std_17)
    set_target_properties(autoremix_audio_core_tests PROPERTIES CXX_EXTENSIONS OFF)

    if(MSVC)
        target_compile_options(autoremix_audio_core_tests PRIVATE /W4 /permissive-)
    else()
        target_compile_options(autoremix_audio_core_tests PRIVATE -Wall -Wextra -Wpedantic)
    endif()

    add_test(NAME autoremix_audio_core_tests COMMAND autoremix_audio_core_tests)
endif()
