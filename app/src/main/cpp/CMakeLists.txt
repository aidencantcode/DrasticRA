cmake_minimum_required(VERSION 3.22.1)
project(dsra C CXX)

set(RC ${CMAKE_CURRENT_SOURCE_DIR}/rcheevos)
file(GLOB_RECURSE RC_SRC ${RC}/src/*.c)
list(FILTER RC_SRC EXCLUDE REGEX "rc_libretro|/test/")

add_library(dsra SHARED dsra_jni.cpp ${RC_SRC})
target_include_directories(dsra PRIVATE ${RC}/include ${RC}/src)
target_compile_definitions(dsra PRIVATE RC_DISABLE_LUA=1 RC_CLIENT_SUPPORTS_HASH=1)
target_link_libraries(dsra log)
