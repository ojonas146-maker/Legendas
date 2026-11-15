cmake_minimum_required(VERSION 3.22.1)
project(legendas_native LANGUAGES C CXX)

# arm64 com produto escalar e fp16: bem mais rápido nos celulares de 2019 em diante
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(legendas SHARED jni.cpp)
target_include_directories(legendas PRIVATE ${whisper_SOURCE_DIR}/include ${whisper_SOURCE_DIR}/ggml/include)
target_link_libraries(legendas PRIVATE whisper log)
