cmake_minimum_required(VERSION 3.15)
project(natives)

if(APPLE AND CMAKE_OSX_ARCHITECTURES STREQUAL "arm64")
    set(CMAKE_SYSTEM_PROCESSOR "aarch64")
    set(OPUS_MAY_HAVE_NEON OFF CACHE BOOL "Disable runtime NEON check on Apple Silicon" FORCE)
    set(OPUS_PRESUME_NEON ON CACHE BOOL "Apple Silicon always supports NEON" FORCE)
elseif(APPLE AND CMAKE_OSX_ARCHITECTURES STREQUAL "x86_64")
    set(CMAKE_SYSTEM_PROCESSOR "x86_64")
elseif(APPLE AND CMAKE_SYSTEM_PROCESSOR MATCHES "(arm64|aarch64)")
    set(OPUS_MAY_HAVE_NEON OFF CACHE BOOL "Disable runtime NEON check on Apple Silicon" FORCE)
    set(OPUS_PRESUME_NEON ON CACHE BOOL "Apple Silicon always supports NEON" FORCE)
endif()

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

if(NOT DEFINED OPUS_VERSION)
    set(OPUS_VERSION "1.6.1")
endif()
if(NOT DEFINED VORBIS_VERSION)
    set(VORBIS_VERSION "1.3.7")
endif()
if(NOT DEFINED OGG_VERSION)
    set(OGG_VERSION "1.3.6")
endif()
if(NOT DEFINED MPG123_VERSION)
    set(MPG123_VERSION "1.33.7")
endif()
if(NOT DEFINED SAMPLERATE_VERSION)
    set(SAMPLERATE_VERSION "0.2.2")
endif()
if(NOT DEFINED FDK_AAC_VERSION)
    set(FDK_AAC_VERSION "3.0.0")
endif()

add_subdirectory(opus/opus-${OPUS_VERSION})
if(APPLE AND (CMAKE_OSX_ARCHITECTURES STREQUAL "arm64" OR CMAKE_SYSTEM_PROCESSOR MATCHES "(arm64|aarch64)"))
    if(TARGET opus)
        target_compile_definitions(opus PRIVATE OPUS_ARM_MAY_HAVE_NEON OPUS_ARM_MAY_HAVE_NEON_INTR)
    endif()
endif()
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

