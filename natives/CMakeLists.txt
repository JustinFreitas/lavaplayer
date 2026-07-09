cmake_minimum_required(VERSION 3.15)

# Force MSVC static runtime /MT (and /MTd for debug) across all targets
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# Force -fPIC on Unix platforms for all compiled targets
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Disable Opus programs, tests, and shared library build
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build shared library" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Build tests" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Build programs" FORCE)

add_subdirectory(opus/opus-1.6.1)
add_subdirectory(samplerate)
add_subdirectory(fdk-aac)
add_subdirectory(vorbis)
add_subdirectory(connector)

