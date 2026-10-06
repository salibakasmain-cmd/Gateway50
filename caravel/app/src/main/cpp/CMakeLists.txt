cmake_minimum_required(VERSION 3.22.1)

project(caravel_7z_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT BIT7Z_ROOT)
    message(FATAL_ERROR "BIT7Z_ROOT is required")
endif()

if(NOT SEVENZIP_ROOT)
    message(FATAL_ERROR "SEVENZIP_ROOT is required")
endif()

add_library(7-zip INTERFACE IMPORTED)
target_include_directories(
    7-zip INTERFACE
    "${SEVENZIP_ROOT}/CPP"
)

set(BIT7Z_SOURCES
    ${BIT7Z_ROOT}/src/bit7zlibrary.cpp
    ${BIT7Z_ROOT}/src/bit7zlibraryloader.cpp
    ${BIT7Z_ROOT}/src/bitabstractarchivecreator.cpp
    ${BIT7Z_ROOT}/src/bitabstractarchivehandler.cpp
    ${BIT7Z_ROOT}/src/bitabstractarchiveopener.cpp
    ${BIT7Z_ROOT}/src/bitarchiveeditor.cpp
    ${BIT7Z_ROOT}/src/bitarchiveitem.cpp
    ${BIT7Z_ROOT}/src/bitarchiveiteminfo.cpp
    ${BIT7Z_ROOT}/src/bitarchiveitemoffset.cpp
    ${BIT7Z_ROOT}/src/bitarchivereader.cpp
    ${BIT7Z_ROOT}/src/bitarchivewriter.cpp
    ${BIT7Z_ROOT}/src/biterror.cpp
    ${BIT7Z_ROOT}/src/bitexception.cpp
    ${BIT7Z_ROOT}/src/bitfilecompressor.cpp
    ${BIT7Z_ROOT}/src/bitformat.cpp
    ${BIT7Z_ROOT}/src/bitinputarchive.cpp
    ${BIT7Z_ROOT}/src/bitinputitem.cpp
    ${BIT7Z_ROOT}/src/bititemsvector.cpp
    ${BIT7Z_ROOT}/src/bitnestedarchivereader.cpp
    ${BIT7Z_ROOT}/src/bitoutputarchive.cpp
    ${BIT7Z_ROOT}/src/bitpropvariant.cpp
    ${BIT7Z_ROOT}/src/bitsharedlibrary.cpp
    ${BIT7Z_ROOT}/src/bittypes.cpp
    ${BIT7Z_ROOT}/src/internal/atomicfilereplacer.cpp
    ${BIT7Z_ROOT}/src/internal/bufferextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/bufferqueue.cpp
    ${BIT7Z_ROOT}/src/internal/bufferutil.cpp
    ${BIT7Z_ROOT}/src/internal/callback.cpp
    ${BIT7Z_ROOT}/src/internal/cbufferinstream.cpp
    ${BIT7Z_ROOT}/src/internal/cbufferoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/cfixedbufferoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/cmultivolumeinstream.cpp
    ${BIT7Z_ROOT}/src/internal/cmultivolumeoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/crawoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/cstdinstream.cpp
    ${BIT7Z_ROOT}/src/internal/cstdoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/csymlinkinstream.cpp
    ${BIT7Z_ROOT}/src/internal/csynchronizedinstream.cpp
    ${BIT7Z_ROOT}/src/internal/csynchronizedoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/cvolumeoutstream.cpp
    ${BIT7Z_ROOT}/src/internal/dateutil.cpp
    ${BIT7Z_ROOT}/src/internal/extractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/failuresourcecategory.cpp
    ${BIT7Z_ROOT}/src/internal/fileextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/filehandle.cpp
    ${BIT7Z_ROOT}/src/internal/fixedbufferextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/formatdetect.cpp
    ${BIT7Z_ROOT}/src/internal/fsindexer.cpp
    ${BIT7Z_ROOT}/src/internal/fsutil.cpp
    ${BIT7Z_ROOT}/src/internal/genericinputitem.cpp
    ${BIT7Z_ROOT}/src/internal/guids.cpp
    ${BIT7Z_ROOT}/src/internal/hresultcategory.cpp
    ${BIT7Z_ROOT}/src/internal/internalcategory.cpp
    ${BIT7Z_ROOT}/src/internal/opencallback.cpp
    ${BIT7Z_ROOT}/src/internal/opencategory.cpp
    ${BIT7Z_ROOT}/src/internal/openerror.cpp
    ${BIT7Z_ROOT}/src/internal/operationcategory.cpp
    ${BIT7Z_ROOT}/src/internal/operationresult.cpp
    ${BIT7Z_ROOT}/src/internal/processeditem.cpp
    ${BIT7Z_ROOT}/src/internal/rawdataextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/sequentialextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/streamextractcallback.cpp
    ${BIT7Z_ROOT}/src/internal/stringutil.cpp
    ${BIT7Z_ROOT}/src/internal/updatecallback.cpp
    ${BIT7Z_ROOT}/src/internal/win32category.cpp
    ${BIT7Z_ROOT}/src/internal/windows.cpp
)

add_library(caravel_bit7z STATIC ${BIT7Z_SOURCES})

target_include_directories(caravel_bit7z
    PUBLIC
        ${BIT7Z_ROOT}/include
    PRIVATE
        ${BIT7Z_ROOT}/include/bit7z
        ${BIT7Z_ROOT}/src
)

target_compile_definitions(caravel_bit7z
    PRIVATE
        UNICODE
        _UNICODE
        _LARGEFILE64_SOURCE
        _LARGEFILE_SOURCE
        _REENTRANT
        ENV_UNIX
        BREAK_HANDLER
        USE_WIN_FILE
        EXTERNAL_CODECS
    PUBLIC
        _TIME_BITS=64
        _FILE_OFFSET_BITS=64
        BIT7Z_PATH_SANITIZATION
)

add_library(caravel_7z_jni SHARED native7z_jni.cpp)

target_include_directories(caravel_7z_jni PRIVATE
    ${BIT7Z_ROOT}/include
    ${BIT7Z_ROOT}/include/bit7z
    ${BIT7Z_ROOT}/src
)

target_link_libraries(
    caravel_7z_jni
    PRIVATE
        caravel_bit7z
        ${CMAKE_DL_LIBS}
)

target_link_options(
    caravel_7z_jni
    PRIVATE
        "-Wl,-z,max-page-size=16384"
)

set_target_properties(
    caravel_7z_jni
    PROPERTIES
        OUTPUT_NAME "caravel_7z_jni"
)
