cmake_minimum_required(VERSION 3.15)

project(vorbis C)

if(NOT DEFINED VORBIS_VERSION)
	set(VORBIS_VERSION "1.3.7")
endif()
if(NOT DEFINED OGG_VERSION)
	set(OGG_VERSION "1.3.6")
endif()

get_filename_component(ROOT_DIR "." ABSOLUTE)
set(VORBIS_SOURCE "${ROOT_DIR}/libvorbis-${VORBIS_VERSION}/lib")
set(OGG_SOURCE "${ROOT_DIR}/libogg-${OGG_VERSION}/src")

if (NOT "${CMAKE_C_COMPILER_ID}" STREQUAL "MSVC")
	set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC -fdata-sections -ffunction-sections")
endif()

file(GLOB MAIN_SOURCES "${VORBIS_SOURCE}/*.c" "${OGG_SOURCE}/*.c")

list(REMOVE_ITEM MAIN_SOURCES "${VORBIS_SOURCE}/psytune.c")
list(REMOVE_ITEM MAIN_SOURCES "${VORBIS_SOURCE}/tone.c")

include_directories("${ROOT_DIR}/libogg-${OGG_VERSION}/include")
include_directories("${ROOT_DIR}/libvorbis-${VORBIS_VERSION}/include")
include_directories("${ROOT_DIR}/libvorbis-${VORBIS_VERSION}/lib")

add_library(vorbis STATIC ${MAIN_SOURCES})
