cmake_minimum_required(VERSION 3.22.1)
project("zenith_vision" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization Flags for Snapdragon & ARM64-v8a Architecture
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fopenmp -fvisibility=hidden -fvisibility-inlines-hidden")

# Find Android Core System Libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)

# NCNN & OpenCV Configuration
# NCNN and OpenCV Mobile paths can be provided via environment or CMake search paths
find_package(ncnn QUIET)
find_package(OpenCV QUIET)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${OpenCV_INCLUDE_DIRS}
)

# Define Native Shared Library
add_library(
    zenith_vision
    SHARED
    zenith_vision.cpp
)

# Link Libraries
target_link_libraries(
    zenith_vision
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
)

if (TARGET ncnn)
    target_link_libraries(zenith_vision PRIVATE ncnn)
endif()

if (OpenCV_FOUND)
    target_link_libraries(zenith_vision PRIVATE ${OpenCV_LIBS})
endif()
