cmake_minimum_required(VERSION 3.22.1)

project(pocket_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "pocket_llama native build is Android-only.")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "ENG-13 closure build is pinned to arm64-v8a. Found ABI=${ANDROID_ABI}")
endif()

option(POCKETGPT_ENABLE_HEXAGON "Enable experimental Hexagon backend" OFF)
option(
    POCKETGPT_FORCE_ARM_I8MM
    "Force ARM i8mm/dotprod compile flags (unsafe on older arm64 devices)"
    OFF
)

# Path from apps/mobile-android/src/main/cpp -> repo root -> third_party/llama.cpp
set(LLAMA_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_CPP_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored llama.cpp source at ${LLAMA_CPP_ROOT}.")
endif()

if(POCKETGPT_FORCE_ARM_I8MM AND ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(
        "$<$<COMPILE_LANGUAGE:C>:-march=armv8.2-a+dotprod+i8mm>"
        "$<$<COMPILE_LANGUAGE:CXX>:-march=armv8.2-a+dotprod+i8mm>"
    )
endif()

include(FetchContent)

# Build a minimal llama.cpp footprint with Vulkan GPU offload support on Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(POCKETGPT_ENABLE_HEXAGON AND EXISTS "${LLAMA_CPP_ROOT}/ggml/src/ggml-hexagon/CMakeLists.txt")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if(GGML_VULKAN)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        # Android NDK ships host tools under darwin-x86_64 even on Apple Silicon.
        set(_ndk_shader_host_tag "darwin-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(_ndk_shader_host_tag "linux-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(_ndk_shader_host_tag "windows-x86_64")
    endif()

    set(_android_ndk_path "${CMAKE_ANDROID_NDK}")
    if(NOT _android_ndk_path)
        set(_android_ndk_path "${ANDROID_NDK}")
    endif()
    if(_ndk_shader_host_tag AND _android_ndk_path)
        set(_ndk_glslc_hint "${_android_ndk_path}/shader-tools/${_ndk_shader_host_tag}/glslc")
        if(EXISTS "${_ndk_glslc_hint}")
            set(Vulkan_GLSLC_EXECUTABLE "${_ndk_glslc_hint}" CACHE FILEPATH "" FORCE)
        endif()
    endif()

    FetchContent_Declare(
        vulkan_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG v1.3.290
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(vulkan_headers)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)

    find_program(HOST_C_COMPILER NAMES clang gcc NO_CMAKE_FIND_ROOT_PATH)
    find_program(HOST_CXX_COMPILER NAMES clang++ g++ NO_CMAKE_FIND_ROOT_PATH)
    if(NOT HOST_C_COMPILER OR NOT HOST_CXX_COMPILER)
        message(FATAL_ERROR "Unable to locate host C/C++ compilers required for Vulkan shader generation.")
    endif()
    if(NOT CMAKE_MAKE_PROGRAM)
        message(FATAL_ERROR "CMAKE_MAKE_PROGRAM is required for Vulkan shader generation.")
    endif()

    set(VULKAN_SHADERS_HOST_TOOLCHAIN "${CMAKE_BINARY_DIR}/pocket-vulkan-host-toolchain.cmake")
    file(
        WRITE "${VULKAN_SHADERS_HOST_TOOLCHAIN}"
        "set(CMAKE_BUILD_TYPE Release)\n"
        "set(CMAKE_C_FLAGS -O2)\n"
        "set(CMAKE_CXX_FLAGS -O2)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)\n"
        "set(CMAKE_C_COMPILER \"${HOST_C_COMPILER}\")\n"
        "set(CMAKE_CXX_COMPILER \"${HOST_CXX_COMPILER}\")\n"
        "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)\n"
    )
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${VULKAN_SHADERS_HOST_TOOLCHAIN}" CACHE FILEPATH "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_ROOT}" "${CMAKE_BINARY_DIR}/llama.cpp")

add_library(pocket_llama SHARED
    pocket_llama.cpp
    vulkan_compat_shim.cpp
)

target_include_directories(pocket_llama PRIVATE
    "${LLAMA_CPP_ROOT}/include"
    "${LLAMA_CPP_ROOT}/ggml/include"
)

target_link_libraries(pocket_llama
    llama
    android
    log
)
