# Builds libwmtess.so: Tesseract + Leptonica + wm_tesseract_jni.cpp as ONE
# shared library, sized for an APK. Run through build.sh, which fetches the
# sources and passes T4A_CPP; see README.md for what each choice saves.

cmake_minimum_required(VERSION 3.22)
project(wmtess C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT T4A_CPP)
    message(FATAL_ERROR "Pass -DT4A_CPP=<Tesseract4Android>/tesseract4android/src/main/cpp")
endif()
set(LEPT ${T4A_CPP}/leptonica/src/src)
set(TESS ${T4A_CPP}/tesseract/src)

# Generated headers go into the (throwaway) source checkout, as upstream does.
configure_file(${T4A_CPP}/leptonica/endianness.h.in ${LEPT}/endianness.h @ONLY)
file(STRINGS ${TESS}/VERSION PACKAGE_VERSION)
string(REGEX REPLACE "^([^.]*)\\..*" "\\1" GENERIC_MAJOR_VERSION ${PACKAGE_VERSION})
string(REGEX REPLACE "^[^.]*\\.([^.]*)\\..*" "\\1" GENERIC_MINOR_VERSION ${PACKAGE_VERSION})
string(REGEX REPLACE "^[^.]*\\.[^.]*\\.([0-9]*).*" "\\1" GENERIC_MICRO_VERSION ${PACKAGE_VERSION})
configure_file(${TESS}/include/tesseract/version.h.in ${TESS}/include/tesseract/version.h @ONLY)
configure_file(${T4A_CPP}/tesseract/config_auto.h.in ${TESS}/src/api/config_auto.h @ONLY)

# Everything is compiled for size and for the linker to garbage-collect.
# Full LTO lets it drop whatever the four JNI entry points never reach.
set(SIZE_FLAGS -Os -ffunction-sections -fdata-sections -flto)
# Reproducible objects: no build-machine paths in the output.
set(PATH_FLAGS -ffile-prefix-map=${T4A_CPP}=. -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.)

# ---- Leptonica: image containers and the few operations Tesseract uses.
# No image codecs: the keyboard hands over decoded bitmaps.
file(GLOB leptonica_src ${LEPT}/*.c)
add_library(leptonica STATIC ${leptonica_src})
target_include_directories(leptonica PUBLIC ${LEPT})
target_compile_definitions(leptonica PRIVATE
    ANDROID_BUILD HAVE_LIBJPEG=0 HAVE_LIBPNG=0 HAVE_LIBZ=0 NDEBUG)
target_compile_options(leptonica PRIVATE ${SIZE_FLAGS} ${PATH_FLAGS} -Wno-address-of-packed-member)

# ---- Tesseract: LSTM engine only. The legacy engine is upstream's own list,
# read from its CMakeLists so a source bump cannot drift from it.
file(GLOB tesseract_src
    ${TESS}/src/api/*.cpp ${TESS}/src/ccmain/*.cpp ${TESS}/src/ccstruct/*.cpp
    ${TESS}/src/ccutil/*.cpp ${TESS}/src/classify/*.cpp ${TESS}/src/cutil/*.cpp
    ${TESS}/src/dict/*.cpp ${TESS}/src/lstm/*.cpp ${TESS}/src/textord/*.cpp
    ${TESS}/src/viewer/*.cpp ${TESS}/src/wordrec/*.cpp)
file(READ ${TESS}/CMakeLists.txt upstream_cmake)
string(REGEX MATCH "set\\([ \t\n]*TESSERACT_SRC_LEGACY[^)]*\\)" legacy_block "${upstream_cmake}")
string(REGEX MATCHALL "src/[A-Za-z0-9_/]+\\.cpp" legacy_files "${legacy_block}")
list(LENGTH legacy_files legacy_count)
if(legacy_count LESS 30)
    message(FATAL_ERROR "Found only ${legacy_count} legacy sources in upstream CMakeLists.txt")
endif()
foreach(f ${legacy_files})
    list(REMOVE_ITEM tesseract_src ${TESS}/${f})
endforeach()

set(arch_src
    ${TESS}/src/arch/dotproduct.cpp
    ${TESS}/src/arch/simddetect.cpp
    ${TESS}/src/arch/intsimdmatrix.cpp
    ${TESS}/src/arch/intsimdmatrixneon.cpp
    ${TESS}/src/arch/dotproductneon.cpp)

add_library(tesseract STATIC ${tesseract_src} ${arch_src})
target_include_directories(tesseract PUBLIC ${TESS}/include)
target_include_directories(tesseract PRIVATE
    ${TESS}/src/api ${TESS}/src/arch ${TESS}/src/ccmain ${TESS}/src/ccstruct
    ${TESS}/src/ccutil ${TESS}/src/classify ${TESS}/src/cutil ${TESS}/src/dict
    ${TESS}/src/lstm ${TESS}/src/textord ${TESS}/src/viewer ${TESS}/src/wordrec)
target_compile_definitions(tesseract PRIVATE
    HAVE_CONFIG_H GRAPHICS_DISABLED FAST_FLOAT DISABLED_LEGACY_ENGINE NDEBUG)
target_compile_options(tesseract PRIVATE ${SIZE_FLAGS} ${PATH_FLAGS} -Wno-null-dereference)
target_link_libraries(tesseract PUBLIC leptonica)

# The recognition loop lives in lstm/ and arch/. -O2 there costs ~50 KB per
# ABI over -Os and keeps the matrix maths from being size-optimised.
file(GLOB hot_src ${TESS}/src/lstm/*.cpp ${TESS}/src/arch/*.cpp)
set_property(SOURCE ${hot_src} APPEND PROPERTY COMPILE_OPTIONS -O2)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(tesseract PRIVATE HAVE_NEON)
    set_property(SOURCE ${TESS}/src/arch/intsimdmatrixneon.cpp ${TESS}/src/arch/dotproductneon.cpp
        APPEND PROPERTY COMPILE_OPTIONS -mfpu=neon)
    # NEON detection at run time. Built from source: CMake 4 no longer ships
    # the AndroidNdkModules helper that used to import it.
    add_library(cpufeatures STATIC ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)
    target_include_directories(cpufeatures PUBLIC ${ANDROID_NDK}/sources/android/cpufeatures)
    target_link_libraries(cpufeatures PUBLIC dl)
    target_link_libraries(tesseract PUBLIC cpufeatures)
endif()

# ---- The library the app loads.
add_library(wmtess SHARED wm_tesseract_jni.cpp)
target_compile_options(wmtess PRIVATE ${SIZE_FLAGS} ${PATH_FLAGS})
target_link_libraries(wmtess PRIVATE tesseract jnigraphics log)
target_link_options(wmtess PRIVATE
    -flto
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
