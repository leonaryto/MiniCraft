cmake_minimum_required(VERSION 3.22.1)
project(minicraft_launcher C)

# El launcher nativo no usa STL ni excepciones de C++: es codigo C plano que
# habla JNI directamente. Ver cpp/launcher.c para elmotivo de su existencia.

add_library(minicraft_launcher SHARED launcher.c)

target_compile_options(minicraft_launcher PRIVATE
        -Wall
        -Wextra
        -Werror=implicit-function-declaration
        -fvisibility=hidden)

# find_package(log REQUIRED) -> liblog, que aporta android/log.h.
#
# NOTA: no se enlaza `jnilibs` a proposito. Las funciones JNI (JNIEnv,
# GetStringUTFChars, ...) las resuelve el runtime de la app (art/libnativehelper)
# en tiempo de ejecucion; no hace falta una libreria "jnilibs" explicita, y
# find_library(jnilibs) falla con NOTFOUND en el NDK moderno.
find_library(log-lib log)

if(NOT log-lib)
    message(FATAL_ERROR "no se encontro liblog en el NDK")
endif()

target_link_libraries(minicraft_launcher ${log-lib})