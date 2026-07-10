cmake_minimum_required(VERSION 3.15)
project(natives)

# Force CMP0091 to NEW for all subdirectories to ensure CMAKE_MSVC_RUNTIME_LIBRARY is respected
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)

# Force MSVC static runtime /MT (and /MTd for debug) across all targets
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>" CACHE STRING "MSVC runtime library" FORCE)

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

if (MSVC)
    foreach(tgt opus samplerate fdk-aac vorbis connector)
        if(TARGET ${tgt})
            set_target_properties(${tgt} PROPERTIES
                MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
            )
        endif()
    endforeach()
endif()

