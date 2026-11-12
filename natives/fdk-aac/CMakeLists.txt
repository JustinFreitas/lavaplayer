cmake_minimum_required(VERSION 3.15)

project(fdk-aac CXX)

get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)
if(EXISTS "${ROOT_DIR}/cpp/libAACdec")
	set(FDK_BASE "${ROOT_DIR}/cpp")
else()
	set(FDK_BASE "${ROOT_DIR}")
endif()

set(MAIN_DIRS "${FDK_BASE}/libAACdec" "${FDK_BASE}/libFDK" "${FDK_BASE}/libSYS" "${FDK_BASE}/libMpegTPDec" "${FDK_BASE}/libSBRdec" "${FDK_BASE}/libPCMutils" "${FDK_BASE}/libArithCoding" "${FDK_BASE}/libDRCdec" "${FDK_BASE}/libSACdec")
set(MAIN_SOURCES "")

message(STATUS "${MAIN_DIRS}")

foreach(subdir ${MAIN_DIRS})
	file (GLOB dir_sources "${subdir}/src/*.cpp" "${subdir}/src/*.h" "${subdir}/include/*.h")
	set (MAIN_SOURCES ${MAIN_SOURCES} ${dir_sources})

	include_directories("${subdir}/include")
endforeach()

if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "MSVC")
	add_definitions(-Dinline=__inline)
else()
	add_definitions(-Wno-narrowing)
	set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -fdata-sections -ffunction-sections")
endif()

add_library(fdk-aac STATIC ${MAIN_SOURCES})
target_include_directories(fdk-aac PUBLIC "${FDK_BASE}/libAACdec/include" "${FDK_BASE}/libSYS/include")
