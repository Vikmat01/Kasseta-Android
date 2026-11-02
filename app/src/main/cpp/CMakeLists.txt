cmake_minimum_required(VERSION 3.22.1)
project(kasseta C)

# FFmpeg 7.1, audio-only subset (decoders for every common format incl. DSD/DST, APE, WavPack,
# ALAC, TAK, TTA, WMA, Opus...). Pre-configured per ABI in ffmpeg-config/<abi>.
set(FF ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg)
set(FFCFG ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg-config/${ANDROID_ABI})
if(NOT EXISTS ${FFCFG}/config.h)
    message(FATAL_ERROR "Kasseta: no FFmpeg config for ABI ${ANDROID_ABI} (only arm64-v8a and armeabi-v7a)")
endif()
include(${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg-sources.cmake)

# FFmpeg relies on dead-code elimination, so it is always optimised (also in debug builds).
set(FF_COPTS -std=c17 -O3 -fomit-frame-pointer -fno-math-errno -fno-signed-zeros
    -ffunction-sections -fdata-sections -fvisibility=hidden -w)
set(FF_DEFS HAVE_AV_CONFIG_H _ISOC11_SOURCE _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE
    strtod=avpriv_strtod PIC)

function(ff_lib name)
    add_library(${name} STATIC ${ARGN})
    target_include_directories(${name} PRIVATE ${FFCFG} ${FF} ${FF}/compat/stdbit)
    target_compile_definitions(${name} PRIVATE ${FF_DEFS} BUILDING_${name})
    target_compile_options(${name} PRIVATE ${FF_COPTS})
endfunction()

# the opus decoder sources include libavcodec headers by bare name (FFmpeg adds this path for them)
foreach(src ${FF_AVCODEC_SRCS})
    if(src MATCHES "/libavcodec/opus/")
        set_source_files_properties(${src} PROPERTIES INCLUDE_DIRECTORIES ${FF}/libavcodec)
    endif()
endforeach()

ff_lib(avutil ${FF_AVUTIL_SRCS})
ff_lib(swresample ${FF_SWRESAMPLE_SRCS})
ff_lib(avcodec ${FF_AVCODEC_SRCS})
ff_lib(avformat ${FF_AVFORMAT_SRCS})

add_library(kasseta SHARED kcore.c kjni.c)
target_include_directories(kasseta PRIVATE ${FFCFG} ${FF})
target_compile_options(kasseta PRIVATE -std=c17 -O2 -Wall)
target_link_libraries(kasseta avformat avcodec swresample avutil m)
target_link_options(kasseta PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
