cmake_minimum_required(VERSION 3.22.1)
project(anniedsp CXX)

add_library(temari SHARED IMPORTED)
set_target_properties(temari PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtemari.so)

add_library(anniedsp SHARED native-dsp.cpp native-resampler.cpp native-ffmpeg-decoder.cpp)

target_include_directories(anniedsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/include)

target_compile_options(anniedsp PRIVATE
        -O3
        -ffast-math
        -fno-exceptions
        -fno-rtti
        -funroll-loops
        -Wall)

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(anniedsp ${log-lib} ${dl-lib})

# Temari 单独一个 JNI 库，避免 libtemari 加载失败时连带 anniedsp（调音/ffmpeg）。
add_library(annietemari SHARED native-temari.cpp)
target_compile_options(annietemari PRIVATE -O3 -fno-exceptions -fno-rtti -Wall)
target_link_libraries(annietemari ${log-lib} ${dl-lib} temari)
