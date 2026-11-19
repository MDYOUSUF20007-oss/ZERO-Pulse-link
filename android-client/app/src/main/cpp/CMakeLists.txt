cmake_minimum_required(VERSION 3.22.1)
project("pulselink_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pulselink_engine SHARED
    jni_bridge.cpp
    PulseLinkEngine.cpp
    JitterBuffer.cpp
    PiResampler.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)

target_include_directories(pulselink_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(pulselink_engine
    ${log-lib}
    ${aaudio-lib}
    ${opensles-lib}
)

# Enforce 16KB ELF page alignment for Android 15 (API 35+) compatibility
target_link_options(pulselink_engine PRIVATE
    "-Wl,-z,max-page-size=16384"
)
