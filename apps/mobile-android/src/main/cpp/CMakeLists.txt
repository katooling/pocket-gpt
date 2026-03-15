cmake_minimum_required(VERSION 3.22.1)

project(pocket_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

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

include(FetchContent)

# Build a minimal llama.cpp footprint with OpenCL/Hexagon GPU offload support on Android.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Use statically linked ggml backends in Android app binaries.
# Dynamic backend loading can report "no backends are loaded" when shared
# backend artifacts are unavailable at runtime.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_SHARED OFF CACHE BOOL "" FORCE)
if(POCKETGPT_ENABLE_HEXAGON AND EXISTS "${LLAMA_CPP_ROOT}/ggml/src/ggml-hexagon/CMakeLists.txt")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
# Target OpenCL 2.0 for compatibility with Adreno 6xx GPUs (Snapdragon 855-870)
# which only support CL 2.0. CL 3.0 APIs cause compilation constant errors on
# these devices. CL 2.0 target still works correctly on CL 3.0 capable GPUs.
set(GGML_OPENCL_TARGET_VERSION "200" CACHE STRING "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(POCKETGPT_FORCE_ARM_I8MM)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "" FORCE)
elseif(GGML_BACKEND_DL)
    # Build Android CPU backend variants and let ggml dispatch the best one at runtime.
    set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    # GGML_CPU_ALL_VARIANTS requires dynamic backend loading. When backends are
    # linked statically, keep a conservative baseline ARM target for compatibility.
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

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

set(POCKET_LLAMA_COMPILE_FLAGS
    -O3
    -DNDEBUG
    -fvectorize
    -ffunction-sections
    -fdata-sections
    -flto
)
set(POCKET_LLAMA_LINK_FLAGS
    -Wl,--gc-sections
    -flto
)

function(configure_pocket_llama_target target_name)
    target_include_directories(${target_name} PRIVATE
        "${LLAMA_CPP_ROOT}/include"
        "${LLAMA_CPP_ROOT}/ggml/include"
    )
    target_link_libraries(${target_name}
        llama
        android
        log
    )
    target_compile_options(${target_name} PRIVATE ${POCKET_LLAMA_COMPILE_FLAGS})
    target_link_options(${target_name} PRIVATE ${POCKET_LLAMA_LINK_FLAGS})
endfunction()

function(add_pocket_llama_variant target_name march_flag)
    add_library(${target_name} SHARED
        pocket_llama.cpp
    )
    configure_pocket_llama_target(${target_name})
    if(NOT "${march_flag}" STREQUAL "")
        target_compile_options(${target_name} PRIVATE
            "$<$<COMPILE_LANGUAGE:C>:${march_flag}>"
            "$<$<COMPILE_LANGUAGE:CXX>:${march_flag}>"
        )
    endif()
endfunction()

if(TARGET llama)
    target_compile_options(llama PRIVATE ${POCKET_LLAMA_COMPILE_FLAGS})
    target_link_options(llama PRIVATE ${POCKET_LLAMA_LINK_FLAGS})
endif()

add_pocket_llama_variant(pocket_llama "")
add_pocket_llama_variant(pocket_llama_v8 "-march=armv8-a")
add_pocket_llama_variant(pocket_llama_v8_2 "-march=armv8.2-a")
add_pocket_llama_variant(pocket_llama_v8_2_dotprod "-march=armv8.2-a+dotprod")
add_pocket_llama_variant(pocket_llama_v8_2_i8mm "-march=armv8.2-a+i8mm")
add_pocket_llama_variant(pocket_llama_v8_2_dotprod_i8mm "-march=armv8.2-a+dotprod+i8mm")
