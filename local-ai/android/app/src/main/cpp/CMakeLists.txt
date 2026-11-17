cmake_minimum_required(VERSION 3.22.1)
project("localai" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp (git submodule pinned to a release tag; see docs/MODEL_RUNTIME_DESIGN.md) ----
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)

# Build every ARM CPU variant (armv8.0 … armv9 + i8mm/sve/sme) and pick the best one at runtime,
# so old 4 GB phones and new flagships both get a working, fast kernel.
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
# KleidiAI is fetched from the network at configure time; keep builds hermetic for now.
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp build-llama)

add_library(localai SHARED la_jni.cpp)
target_include_directories(localai PRIVATE
        llama.cpp/include
        llama.cpp/common
        llama.cpp/ggml/include)
target_link_libraries(localai llama llama-common ggml android log)
# 16 KB page-size compatibility (Android 15+ devices)
target_link_options(localai PRIVATE "-Wl,-z,max-page-size=16384")
