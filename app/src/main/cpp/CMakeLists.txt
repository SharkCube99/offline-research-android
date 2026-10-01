cmake_minimum_required(VERSION 3.22.1)

project(offline_research_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is a git submodule at <repo>/third_party/llama.cpp, pinned to a tag.
# Its build options are passed from app/build.gradle.kts.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp submodule is missing. Run: git submodule update --init --depth 1")
endif()
add_subdirectory(${LLAMA_SRC} llama-build)

# The bridge uses only the public C API in llama.h (no llama-common), so a
# llama.cpp bump only has to keep that one header compatible.
add_library(llama_bridge SHARED llama_bridge.cpp)

target_link_libraries(llama_bridge PRIVATE llama ggml android log)
