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
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # Keep runtime compatibility for older arm64 cores (e.g., Exynos 9611 class devices).
    add_compile_options(
        "$<$<COMPILE_LANGUAGE:C>:-march=armv8-a>"
        "$<$<COMPILE_LANGUAGE:CXX>:-march=armv8-a>"
        "$<$<COMPILE_LANGUAGE:C>:-U__ARM_FEATURE_FP16_VECTOR_ARITHMETIC>"
        "$<$<COMPILE_LANGUAGE:CXX>:-U__ARM_FEATURE_FP16_VECTOR_ARITHMETIC>"
        "$<$<COMPILE_LANGUAGE:C>:-U__ARM_FEATURE_DOTPROD>"
        "$<$<COMPILE_LANGUAGE:CXX>:-U__ARM_FEATURE_DOTPROD>"
        "$<$<COMPILE_LANGUAGE:C>:-U__ARM_FEATURE_MATMUL_INT8>"
        "$<$<COMPILE_LANGUAGE:CXX>:-U__ARM_FEATURE_MATMUL_INT8>"
        "$<$<COMPILE_LANGUAGE:C>:-U__ARM_FEATURE_SVE>"
        "$<$<COMPILE_LANGUAGE:CXX>:-U__ARM_FEATURE_SVE>"
        "$<$<COMPILE_LANGUAGE:C>:-U__ARM_FEATURE_SME>"
        "$<$<COMPILE_LANGUAGE:CXX>:-U__ARM_FEATURE_SME>"
    )
endif()

include(FetchContent)

# Build a minimal llama.cpp footprint with OpenCL/Hexagon GPU offload support on Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(POCKETGPT_ENABLE_HEXAGON AND EXISTS "${LLAMA_CPP_ROOT}/ggml/src/ggml-hexagon/CMakeLists.txt")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

if(GGML_OPENCL)
    # Provide OpenCL headers + ICD loader for Android builds. This mirrors the
    # dependency model used by llama.rn source builds.
    FetchContent_Declare(
        opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG v2024.10.24
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(opencl_headers)

    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_TESTS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    FetchContent_Declare(
        opencl_icd_loader
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG v2024.10.24
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(opencl_icd_loader)

    # Feed FindOpenCL hints consumed by ggml-opencl's CMake.
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_INCLUDE_DIRS "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    set(OpenCL_LIBRARIES OpenCL CACHE STRING "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_ROOT}" "${CMAKE_BINARY_DIR}/llama.cpp")

add_library(pocket_llama SHARED
    pocket_llama.cpp
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
