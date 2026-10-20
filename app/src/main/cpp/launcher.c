// launcher.c — Minicraft: lanzador nativo de la JVM.
//
// POR QUE EXISTE ESTE FICHERO (y por que no basta con /system/bin/linker64)
//
// En Android 13 (verificado en un Xiaomi M2012K11AG con SELinux Enforcing), el linker
// del sistema REQUIERE que todos sus argumentos sean rutas absolutas:
//
//     $ /system/bin/linker64 /data/local/tmp/jre/bin/java -version
//     error: expected absolute path: "-version"
//
// Esto no se puede esquivar desde fuera: los @argfiles de Java 9+ tampoco valen, porque
// el linker expande los args y vuelve a exigir rutas absolutas. Ademas linker64 solo
// acepta e_type=3 (PIE/dinamico) y rechaza los estaticos (e_type=2):
//
//     $ /system/bin/linker64 <binario-estatico>
//     error: has unexpected e_type: 2
//
// SOLUCION: esta libreria es un .so (e_type=3, sin PT_INTERP) que actua como
// intermediario. Hace fork() + execve() desde un proceso nativo, donde NO se aplica el
// parseo de argumentos del linker de Android: los argumentos llegan intactos a la JVM.
//
// Uso desde Kotlin:
//     val rc = NativeLauncher.execve(paths, argv, envp, workingDir, timeoutMs)

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/stat.h>    // chmod()
#include <stdlib.h>      // calloc, free, malloc, exit
#include <string.h>      // strdup, strerror, strlen, memcpy, memset
#include <stdio.h>       // snprintf
#include <sys/socket.h>   // socket(), connect() — IPC nativo del daemon playitd
#include <sys/un.h>       // sockaddr_un — socket Unix abstracto @playit_ipc
#include <dlfcn.h>
#include <errno.h>
#include <stddef.h>       // offsetof()
#include <fcntl.h>
#include <signal.h>
#include <termios.h>
#include <pthread.h>

// ---------------------------------------------------------------------------
// Consola del proceso hijo: un unico pipe de salida (stdout+stderr) compartido.
// ---------------------------------------------------------------------------
static int g_out_pipe[2] = { -1, -1 };   // [lectura, escritura]
static int g_in_pipe[2] = { -1, -1 };    // [lectura, escritura] — stdin del hijo
static pid_t g_child_pid = -1;             // PID del proceso hijo del servidor
static pid_t g_playit_pid = -1;           // PID del daemon playitd (separado del JVM)
static jclass java_lang_String = NULL;    // cacheada para runAndCaptureNative

#define TAG "MinicraftNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Convierte un jobjectArray de java.lang.String a char** (argv/envp).
// El llamador debe liberar con free_string_array().
// ---------------------------------------------------------------------------
static char** to_char_array(JNIEnv* env, jobjectArray arr, int* out_len) {
    if (arr == NULL) { *out_len = -1; return NULL; }
    jsize n = (*env)->GetArrayLength(env, arr);
    char** out = (char**) calloc((size_t)n + 1, sizeof(char*));
    if (!out) { *out_len = -1; return NULL; }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        if (s == NULL) { out[i] = NULL; continue; }
        const char* c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = c ? strdup(c) : NULL;   // duplicamos: los args viven tras el fork
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    *out_len = (int)n;
    return out;
}

static void free_string_array(char** a) {
    if (!a) return;
    for (int i = 0; a[i]; i++) free(a[i]);
    free(a);
}

// ---------------------------------------------------------------------------
// execve(): lanza un proceso hijo y devuelve su codigo de salida.
// nunca devuelve en el proceso padre salvo error de arranque (-1).
//
// POR QUE YA NO PASAMOS POR /system/bin/linker64 (resuelto y verificado el 2026-10-04)
//
// Un proceso de app corre en el dominio SELinux `runas_app` y NO puede ejecutar
// un binario de su propio directorio de datos: /data/user/0 esta sujeto a W^X.
//
//     execve path=/data/user/0/com.minicraft.mcserver/files/jre/bin/java
//     -> Permission denied   (errno 13)
//
// La idea inicial era ejecutar /system/bin/linker64 a mano, porque vive en el dominio
// `system_exec` y si puede mapear ficheros del data dir. FALLA: el linker de Android
// exige que TODOS sus argumentos sean rutas absolutas, asi que no deja pasar los flags
// de la JVM hacia el ejecutable:
//
//     $ /system/bin/linker64 /path/java -Djava.awt.headless=true -jar x.jar
//     error: expected absolute path: "-Djava.awt.headless=true"
//
// Ninguna combinacion de variables de entorno lo evita (9 vias probadas):
// el launcher de la JVM reinyecta los flags como
// argv y vuelve a toparse con el parser del linker.
//
// LA SOLUCION (verificada): que el KERNEL ejecute el stub. El stub `bin/java` del
// JDK de Termux es un ELF e_type=3 con PT_INTERP=/system/bin/linker64, asi que el
// kernel carga el linker por el y le entrega el argv SIN reinterpretar. Para que
// execve no choque con W^X, el stub va empaquetado como jniLib del APK: Android lo
// extrae a nativeLibraryDir (/data/app/~~<pkg>/lib/arm64/libmcjvmboot.so), que tiene otra
// etiqueta SELinux (apk_data_file) y no esta bajo W^X.
//
// El resto de la JRE (243 MB: libjvm.so, lib/modules, ...) sigue en el data dir del
// app y se descarga en el primer arranque. Se
// localiza por dos mecanismos, ambos verificados en hardware:
//
//   1. DT_RUNPATH del stub = "$ORIGIN:$ORIGIN/../lib", y LD_LIBRARY_PATH (que el
//      linker de Android SI respeta) apunta a <jre>/lib y <jre>/lib/server. Ahi se
//      cargan libjli.so y lib/server/libjvm.so.
//   2. GetApplicationHomeFromDll() (java_md_common.c) hace dladdr() de libjli.so y
//      trunca en el ultimo "/lib/", de modo que home = <jre>. Esto es lo que permite
//      que el stub viva FUERA del arbol del JRE.
//
// Traza real del mecanismo, con _JAVA_LAUNCHER_DEBUG=1 en un Xiaomi Android 13:
//
//     Attempt to get JRE path from launcher executable path     <- falla (nativeLibDir)
//     Attempt to get JRE path from shared lib of the image       <- acierta
//     JRE path is /data/local/tmp/mc/jre
//     Does `/data/local/tmp/mc/jre/lib/server/libjvm.so' exist ... yes.
//     7401 micro seconds to LoadJavaVM
// ---------------------------------------------------------------------------
static int do_execve(const char* path, char* const argv[],
                     char* const envp[], const char* cwd) {
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() fallo: %s", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        // ---- proceso hijo ----
        // setsid() da una sesion nueva para que los senales del servidor no maten
        // a la app. Falla con EPERM si ya somos leaders de sesion (tipico en una
        // app cuyo padre es zygote); no es critico, asi que solo lo registramos.
        if (setsid() < 0) {
            LOGI("setsid() fallo (%s); se continua", strerror(errno));
        }

        if (cwd && chdir(cwd) != 0) {
            LOGE("chdir(%s) fallo: %s", cwd, strerror(errno));
        }

        // stdout y stderr -> pipe de salida. El servidor loguea a ambos, y el
        // launcher de la JVM escribe sus errores (p.ej. "Picked up JDK_JAVA_OPTIONS")
        // en stderr ANTES de execvear, asi que ambos deben ir al mismo pipe.
        dup2(g_out_pipe[1], STDOUT_FILENO);
        dup2(g_out_pipe[1], STDERR_FILENO);

        // stdin -> pipe de ENTRADA, para poder escribir en la consola del servidor
        // (comandos `stop`, `say`, `whitelist ...`). Antes era /dev/null, con lo que el
        // servidor recibia EOF inmediato y no habia consola que usar.
        // El extremo de escritura se cierra en el hijo: si no, el servidor nunca veria
        // EOF y el padre no podria saber cuando termina.
        if (g_in_pipe[0] >= 0) {
            dup2(g_in_pipe[0], STDIN_FILENO);
            close(g_in_pipe[0]);
            g_in_pipe[0] = -1;
        } else {
            int devnull = open("/dev/null", O_RDONLY);
            if (devnull >= 0) dup2(devnull, STDIN_FILENO);
        }
        if (g_in_pipe[1] >= 0) { close(g_in_pipe[1]); g_in_pipe[1] = -1; }

        // Diagnostico antes de execvear: si algo falla, el error queda en el log
        // aunque el proceso hijo muera al instante.
        struct stat st;
        int eacces = 0;
        if (stat(path, &st) != 0) {
            LOGE("pre-exec: stat(%s) fallo: %s", path, strerror(errno));
            _exit(126);
        }
        if (access(path, X_OK) != 0) {
            eacces = 1;
            LOGE("pre-exec: %s NO es ejecutable para la app: %s", path, strerror(errno));
            LOGE("pre-exec: modo=%o uid=%d gid=%d  (nativeLibraryDir debe ser del sistema)",
                 (int)(st.st_mode & 07777), (int)st.st_uid, (int)st.st_gid);
            LOGE("pre-exec: si uid!=app, el fichero no se extrajo y hay que reinstalar la APK");
        }

        // execve DIRECTO. El kernel carga el PT_INTERP (/system/bin/linker64) del stub
        // y le pasa argv intacto: no hay parser de linker en medio, asi que los flags
        // de la JVM llegan bien. No se invoca /system/bin/linker64 a proposito.
        execve(path, argv, envp);

        // Si llegamos aqui, execve fallo. Distinguir los dos motivos posibles:
        //  - EACCES: W^X / SELinux (el fichero no es ejecutable para este dominio).
        //  - ENOENT: PT_INTERP ausente o libs no resolubles -> dalo claro.
        int e = errno;
        if (e == EACCES) {
            LOGE("execve(%s) -> EACCES: W^X o SELinux lo impide en este dominio", path);
            if (!eacces) {
                LOGE("  (pero access(X_OK) si dijo que es ejecutable: coherencia de SELinux rota)");
            }
        } else if (e == ENOENT) {
            LOGE("execve(%s) -> ENOENT: falta el PT_INTERP (/system/bin/linker64) "
                 "o no se resuelven las libs del stub", path);
        }
        LOGE("execve(%s) fallo: %s", path, strerror(e));
        _exit(127);
    }

    // ---- proceso padre ----
    g_child_pid = pid;
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) { LOGE("waitpid fallo: %s", strerror(errno)); return -1; }
    }
    if (WIFEXITED(status))  return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

// ---------------------------------------------------------------------------
// JNI: execve(String path, String[] argv, String[] envp, String cwd)
// Devuelve el exit code del hijo, o -1 si no se pudo lanzar.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_execveNative(
        JNIEnv* env, jclass clazz,
        jstring j_path, jobjectArray j_argv, jobjectArray j_envp, jstring j_cwd) {

    const char* path = (*env)->GetStringUTFChars(env, j_path, NULL);
    if (!path) { LOGE("path nulo"); return -1; }

    int n_argv = 0, n_envp = 0;
    char** argv = to_char_array(env, j_argv, &n_argv);
    char** envp = to_char_array(env, j_envp, &n_envp);
    const char* cwd = j_cwd ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    if (!argv) {
        LOGE("no se pudo construir argv");
        if (path) (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return -1;
    }

    LOGI("execve path=%s argc=%d cwd=%s", path, n_argv, cwd ? cwd : "(null)");

    // Pipes del hijo, ANTES del fork para que los descriptores sean validos en ambos
    // procesos. El padre lee la salida y escribe la entrada; el hijo al reves.
    if (pipe(g_out_pipe) != 0) {
        LOGE("pipe() fallo: %s", strerror(errno));
        free_string_array(argv);
        free_string_array(envp);
        (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return -1;
    }

    // Pipe de ENTRADA: el padre escribe, el hijo leera por stdin. Si falla, el hijo
    // cae al /dev/null de abajo y se pierde la consola, pero el servidor arranca igual.
    if (pipe(g_in_pipe) != 0) {
        LOGE("pipe() de entrada fallo: %s (la consola no estara disponible)", strerror(errno));
        g_in_pipe[0] = g_in_pipe[1] = -1;
    }

    int rc = do_execve(path, argv, envp, cwd);

    // El padre no necesita escribir en el pipe de salida, y ya no puede hablar con el
    // hijo (ha terminado): cerrar ambos extremos del de entrada.
    if (g_out_pipe[1] >= 0) { close(g_out_pipe[1]); g_out_pipe[1] = -1; }
    if (g_in_pipe[0] >= 0) { close(g_in_pipe[0]); g_in_pipe[0] = -1; }
    if (g_in_pipe[1] >= 0) { close(g_in_pipe[1]); g_in_pipe[1] = -1; }

    free_string_array(argv);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    return rc;
}

// ---------------------------------------------------------------------------
// JNI: pipe() — crea un pipe para capturar stdout/stderr del proceso hijo.
// Devuelve un int[2]: [lectura, escritura]. La app lo envuelve en un stream.
// ---------------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_pipe(JNIEnv* env, jclass clazz) {
    int fd[2];
    if (pipe(fd) != 0) { LOGE("pipe() fallo: %s", strerror(errno)); return NULL; }
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    jint v[2] = { fd[0], fd[1] };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, v);
    return arr;
}

// ---------------------------------------------------------------------------
// JNI: dup2(fd, target) — redirige un descriptor. Se usa para conectar el stdout
// del proceso hijo a un pipe antes del execve.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_dup2(JNIEnv* env, jclass clazz,
                                                        jint fd, jint target) {
    if (dup2(fd, target) < 0) { LOGE("dup2 fallo: %s", strerror(errno)); return -1; }
    return target;
}

// ---------------------------------------------------------------------------
// JNI: closeFd(int) — cierra un descriptor de fichero.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_closeFd(JNIEnv* env, jclass clazz,
                                                           jint fd) {
    if (fd >= 0) close(fd);
}

// ---------------------------------------------------------------------------
// JNI: isExecutable(String path) — comprueba si un fichero es ejecutable.
// La app lo usa antes de intentar lanzar, para dar un error claro en vez de un
// "exit 127" opaco.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_isExecutableNative(
        JNIEnv* env, jclass clazz, jstring j_path) {
    const char* p = (*env)->GetStringUTFChars(env, j_path, NULL);
    if (!p) return JNI_FALSE;
    int rc = (access(p, X_OK) == 0) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseStringUTFChars(env, j_path, p);
    return rc;
}

// ---------------------------------------------------------------------------
// JNI: which(String name, String[] searchPaths) — busca un ejecutable.
// Necesario porque la app no tiene PATH de sistema: /system/bin no esta en el
// entorno por defecto de una app.
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_whichNative(
        JNIEnv* env, jclass clazz, jstring j_name, jobjectArray j_paths) {
    const char* name = (*env)->GetStringUTFChars(env, j_name, NULL);
    if (!name) return NULL;

    jsize n = (*env)->GetArrayLength(env, j_paths);
    char buf[4096];

    for (jsize i = 0; i < n; i++) {
        jstring jp = (jstring)(*env)->GetObjectArrayElement(env, j_paths, i);
        const char* dir = (*env)->GetStringUTFChars(env, jp, NULL);
        if (dir) {
            snprintf(buf, sizeof(buf), "%s/%s", dir, name);
            (*env)->ReleaseStringUTFChars(env, jp, dir);
            (*env)->DeleteLocalRef(env, jp);
            if (access(buf, X_OK) == 0) {
                (*env)->ReleaseStringUTFChars(env, j_name, name);
                return (*env)->NewStringUTF(env, buf);
            }
        }
    }
    (*env)->ReleaseStringUTFChars(env, j_name, name);
    return NULL;
}

// ---------------------------------------------------------------------------
// JNI: chmod(String path, int mode) — cambia permisos. Necesario porque los ficheros
// extraidos de un .deb conservan permisos restrictivos y el usuario puede haberlos
// copiado desde un explorador de archivos.
// Nota: chmod NO elimina la opcion de montura noexec. Se verifico que el
// almacenamiento emulado (sdcardfs) permite ejecutar y hacer lock, asi que no
// es un problema en la ruta que usa la app.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_chmodNative(
        JNIEnv* env, jclass clazz, jstring j_path, jint mode) {
    const char* p = (*env)->GetStringUTFChars(env, j_path, NULL);
    if (!p) return -1;
    int rc = chmod(p, (mode_t)mode);
    if (rc != 0) LOGE("chmod(%s, %o) fallo: %s", p, (int)mode, strerror(errno));
    (*env)->ReleaseStringUTFChars(env, j_path, p);
    return rc;
}

// ---------------------------------------------------------------------------
// Callback de linea hacia Kotlin. El hilo nativo de lectura invoca esta funcion
// por cada linea del hijo; se resuelve la clase y el metodo una sola vez y se
// cachean el jclass global y el jmethodID.
// ---------------------------------------------------------------------------
static JavaVM* g_vm = NULL;
static jclass    g_sink_cls = NULL;      // com/minicraft/mcserver/runtime/NativeLauncher
static jmethodID g_sink_mid = NULL;      // ESTATICO acceptLine(Ljava/lang/String;)V
static pthread_once_t g_sink_once = PTHREAD_ONCE_INIT;

static void sink_init(void) {
    if (!g_vm) { LOGE("sink_init: no hay JavaVM (la lib se cargo sin OnLoad)"); return; }
    JNIEnv* env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return;
    jclass local = (*env)->FindClass(env, "com/minicraft/mcserver/runtime/NativeLauncher");
    if (!local) { LOGE("sink_init: no se encontro NativeLauncher"); return; }
    g_sink_cls = (jclass)(*env)->NewGlobalRef(env, local);
    // GetStaticMethodID, NO GetMethodID: acceptLine es un metodo ESTATICO del
    // objeto Kotlin. Con GetMethodID + CallVoidMethod pasando el jclass, ART aborta
    // el proceso: "can't call void ... on instance of java.lang.Class<...>"
    // (SIGABRT verificado en el dispositivo, 2026-10-04.)
    g_sink_mid = (*env)->GetStaticMethodID(env, local, "acceptLine", "(Ljava/lang/String;)V");
    if (!g_sink_mid) LOGE("sink_init: no se encontro acceptLine (estatico)");
    LOGI("sink inicializado");
}

// Envia una linea a Kotlin. Seguro desde hilos que no son los de Java.
static void emit_line(const char* s) {
    LOGI("OUT: %s", s);
    pthread_once(&g_sink_once, sink_init);
    if (!g_vm || !g_sink_cls || !g_sink_mid) return;

    JNIEnv* env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    jstring js = (*env)->NewStringUTF(env, s);
    if (js) {
        (*env)->CallStaticVoidMethod(env, g_sink_cls, g_sink_mid, js);
        // El servidor loguea mucho: limpiar excepciones pendientes para no arrastrar
        // una referencia invalida al siguiente linea.
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, js);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    pthread_once(&g_sink_once, sink_init);

    // Cachear java.lang.String. `runAndCaptureNative` devuelve un String[2] y lo crea con
    // NewObjectArray; si esta clase vale NULL, ART aborta el proceso con
    // "JNI DETECTED ERROR IN APPLICATION: NewObjectArray received NULL jclass".
    // Tiene que ser un NewGlobalRef: un local ref se invalida al volver a Java.
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK && env != NULL) {
        jclass cls = (*env)->FindClass(env, "java/lang/String");
        if (cls != NULL) {
            java_lang_String = (*env)->NewGlobalRef(env, cls);
            (*env)->DeleteLocalRef(env, cls);
        }
    }
    LOGI("MinicraftNative cargada (java.lang.String=%p)", java_lang_String);

    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// readOutput(cb) — lee el pipe de salida del hijo linea a linea y llama a la
// funcion Java con cada linea. Bloquea hasta EOF (cuando el hijo cierra el pipe).
//
// El callback se invoca DESDE un hilo nativo. La app debe reenviarlo al hilo
// principal si toca UI.
// ---------------------------------------------------------------------------
static void* read_output_thread(void* arg) {
    (void)arg;
    char buf[4096];
    size_t pending = 0;
    static __thread char line[8192];

    while (1) {
        ssize_t n = read(g_out_pipe[0], buf, sizeof(buf) - 1);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("read() fallo: %s", strerror(errno));
            break;
        }
        if (n == 0) break;   // EOF: el hijo cerro stdout

        buf[n] = '\0';

        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n' || buf[i] == '\r') {
                line[pending] = '\0';
                emit_line(line);
                pending = 0;
            } else if (pending < sizeof(line) - 1) {
                line[pending++] = buf[i];
            }
        }
    }
    if (pending > 0) {
        line[pending] = '\0';
        emit_line(line);
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_readOutputNative(JNIEnv* env, jclass clazz) {
    if (g_out_pipe[0] < 0) { LOGE("readOutput sin pipe abierto"); return; }
    read_output_thread(NULL);
}

// ---------------------------------------------------------------------------
// hasOutputPipe() — ¿existe ya el pipe? El lector Kotlin lo espera antes de
// arrancar: el hijo puede morir en ~100ms si algo falla, y con un sleep fijo se
// perderia toda la salida del diagnostico.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_hasOutputPipeNative(
        JNIEnv* env, jclass clazz) {
    return (g_out_pipe[0] >= 0) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// startOutputReader() — lanza readOutputNative() en un hilo demonio y devuelve.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_startOutputReaderNative(JNIEnv* env, jclass clazz) {
    if (g_out_pipe[0] < 0) { LOGE("startOutputReader sin pipe abierto"); return; }
    pthread_t th;
    if (pthread_create(&th, NULL, read_output_thread, NULL) != 0) {
        LOGE("pthread_create fallo: %s", strerror(errno));
        return;
    }
    pthread_detach(th);   // no esperamos: la app no debe bloquear
    LOGI("lector de salida lanzado");
}

// ---------------------------------------------------------------------------
// closeOutput() — cierra el pipe de lectura. La app lo llama al destruir.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_closeOutputNative(JNIEnv* env, jclass clazz) {
    if (g_out_pipe[0] >= 0) { close(g_out_pipe[0]); g_out_pipe[0] = -1; }
    if (g_out_pipe[1] >= 0) { close(g_out_pipe[1]); g_out_pipe[1] = -1; }
}

// ---------------------------------------------------------------------------
// JNI: writeStdin(String) — escribe una linea en la consola del servidor.
// Devuelve los bytes escritos, o -1 si el hijo ya no esta.
//
// MANEJO DE SIGPIPE (importante): si el servidor ha muerto pero el padre aun no
// ha hecho waitpid, write() devuelve EPIPE y ademas el proceso recibe SIGPIPE,
// que por omision MATARIA la app entera. Por eso se ignora SIGPIPE solo durante
// la escritura y se restaura despues, en vez de tocar el manejador global.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_writeStdinNative(
        JNIEnv* env, jclass clazz, jstring j_line) {
    if (g_in_pipe[1] < 0) {
        LOGE("writeStdin: no hay pipe de entrada abierto");
        return -1;
    }
    if (!j_line) return -1;

    const char* s = (*env)->GetStringUTFChars(env, j_line, NULL);
    if (!s) return -1;

    // El servidor lee lineas terminadas en \n, asi que anadimos el salto si falta.
    size_t len = strlen(s);

    char stackbuf[513];
    char* buf = stackbuf;
    size_t total = len + 1;
    if (total > sizeof(stackbuf)) {
        buf = (char*)malloc(total);
        if (!buf) { (*env)->ReleaseStringUTFChars(env, j_line, s); return -1; }
    }
    memcpy(buf, s, len);
    buf[len] = '\n';

    struct sigaction old_ign, ign;
    memset(&ign, 0, sizeof(ign));
    ign.sa_handler = SIG_IGN;
    sigemptyset(&ign.sa_mask);
    sigaction(SIGPIPE, &ign, &old_ign);

    size_t off = 0;
    int rc = -1;
    while (off < total) {
        ssize_t w = write(g_in_pipe[1], buf + off, total - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            LOGE("writeStdin fallo: %s (el servidor sigue vivo?)", strerror(errno));
            rc = -1;
            break;
        }
        off += (size_t)w;
        rc = (int)off;
    }

    sigaction(SIGPIPE, &old_ign, NULL);
    if (buf != stackbuf) free(buf);
    (*env)->ReleaseStringUTFChars(env, j_line, s);
    return rc;
}

// ---------------------------------------------------------------------------
// JNI: closeStdinNative() — cierra la entrada para que el servidor vea EOF y
// termine de forma ordenada. Equivale a pulsar Ctrl+D en su consola.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_closeStdinNative(
        JNIEnv* env, jclass clazz) {
    if (g_in_pipe[1] >= 0) { close(g_in_pipe[1]); g_in_pipe[1] = -1; }
    LOGI("stdin cerrado (EOF para el servidor)");
}

// ---------------------------------------------------------------------------
// JNI: hasStdinNative() — ¿hay consola disponible?
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_hasStdinNative(
        JNIEnv* env, jclass clazz) {
    return (g_in_pipe[1] >= 0) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// JNI: runAndCapture(path, argv, envp, cwd) -> String[2] = [salida, codigoSalida]
//
// Para COMANDOS CORTOS de Java (el instalador de Fabric, `java -version`...).
// No se puede reutilizar execve() para esto: aquel bloquea en waitpid() sin
// drainear el pipe, y el instalador escribe bastante, asi que el pipe se llenaria
// (64 KB) y los dos procesos se quedarian bloqueados. Aqui se fork+execve, se
// drena el pipe hasta EOF y luego se hace waitpid: el orden correcto.
//
// NO se puede usar en paralelo con execve() (comparten g_child_pid). Uso
// secuencial: o el servidor corre, o se instala.
// ---------------------------------------------------------------------------
static void* read_all(int fd, char* buf, size_t cap) {
    size_t off = 0;
    while (off + 1 < cap) {
        ssize_t n = read(fd, buf + off, cap - 1 - off);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (n == 0) break;   // EOF
        off += (size_t)n;
    }
    buf[off] = '\0';
    return buf;
}

JNIEXPORT jobjectArray JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_runAndCaptureNative(
        JNIEnv* env, jclass clazz,
        jstring j_path, jobjectArray j_argv, jobjectArray j_envp, jstring j_cwd) {

    const char* path = j_path ? (*env)->GetStringUTFChars(env, j_path, NULL) : NULL;
    if (!path) return NULL;

    int n_argv = 0, n_envp = 0;
    char** argv = to_char_array(env, j_argv, &n_argv);
    char** envp = to_char_array(env, j_envp, &n_envp);
    const char* cwd = j_cwd ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    if (!argv || !envp) {
        free_string_array(argv); free_string_array(envp);
        (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return NULL;
    }

    // Pipe de salida del hijo (solo escribe el hijo).
    int pfd[2];
    if (pipe(pfd) != 0) {
        LOGE("runAndCapture: pipe() fallo: %s", strerror(errno));
        free_string_array(argv); free_string_array(envp);
        (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return NULL;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("runAndCapture: fork() fallo: %s", strerror(errno));
        close(pfd[0]); close(pfd[1]);
        free_string_array(argv); free_string_array(envp);
        (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return NULL;
    }

    if (pid == 0) {
        // ---- hijo ----
        if (cwd && chdir(cwd) != 0) LOGE("runAndCapture: chdir fallo: %s", strerror(errno));
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) dup2(devnull, STDIN_FILENO);   // sin entrada: no es interactivo
        close(pfd[0]);
        close(pfd[1]);
        execve(path, argv, envp);
        // Si llegamos aqui, execve fallo.
        LOGE("runAndCapture: execve(%s) fallo: %s", path, strerror(errno));
        _exit(127);
    }

    // ---- padre: drenar el pipe ANTES de waitpid ----
    close(pfd[1]);
    char* buf = (char*)malloc(1 << 18);     // 256 KB, sobra para el instalador
    if (!buf) {
        LOGE("runAndCapture: sin memoria para la salida");
        close(pfd[0]);
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        free_string_array(argv); free_string_array(envp);
        (*env)->ReleaseStringUTFChars(env, j_path, path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return NULL;
    }
    read_all(pfd[0], buf, 1 << 18);
    close(pfd[0]);

    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) break;
    }
    int rc = WIFEXITED(status) ? WEXITSTATUS(status)
             : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1);

    LOGI("runAndCapture rc=%d (%zu bytes)", rc, strlen(buf));

    jobjectArray res = (*env)->NewObjectArray(env, 2, java_lang_String, NULL);
    if (res) {
        jstring s_out = (*env)->NewStringUTF(env, buf);
        char rcbuf[32];
        snprintf(rcbuf, sizeof(rcbuf), "%d", rc);
        jstring s_rc = (*env)->NewStringUTF(env, rcbuf);
        (*env)->SetObjectArrayElement(env, res, 0, s_out);
        (*env)->SetObjectArrayElement(env, res, 1, s_rc);
        (*env)->DeleteLocalRef(env, s_out);
        (*env)->DeleteLocalRef(env, s_rc);
    }

    free(buf);
    free_string_array(argv); free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    return res;
}

// ---------------------------------------------------------------------------
// JNI: killChild() — envia SIGTERM al proceso hijo. Necesario para "detener".
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_killChildNative(JNIEnv* env, jclass clazz) {
    if (g_child_pid > 0) {
        LOGI("enviando SIGTERM a pid=%d", g_child_pid);
        kill(g_child_pid, SIGTERM);
    } else {
        LOGE("killChild: no hay hijo registrado");
    }
}

// ---------------------------------------------------------------------------
// playitd daemon — proceso hermano del servidor JVM.
//
// playitd es un PIE dinámico (jniLibs/libplayitd.so) que se execvea desde
// nativeLibraryDir: evita W^X (ese directorio no esta bajo la restriccion).
// El daemon no necesita pipes de IO: escribe a --log-path y habla al cliente
// IPC por el socket abstracto @playit_ipc. Su PID se guarda en g_playit_pid,
// que es INDEPENDIENTE de g_child_pid (el servidor JVM).
//
// IMPORTANTE: hay que cerrar los fds heredados de los pipes del servidor JVM
// en el hijo, porque si no, el write-end (g_out_pipe[1]) quedaría abierto en
// el proceso playitd y el read_output_thread nunca vería EOF cuando el
// servidor termine.
// ---------------------------------------------------------------------------

// fork()+execve() SIN waitpid: el daemon corre en segundo plano mientras el
// servidor JVM también corre. Devuelve el PID al padre inmediatamente.
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_startPlayitDaemonNative(
        JNIEnv* env, jclass clazz,
        jstring j_path, jobjectArray j_argv, jobjectArray j_envp, jstring j_cwd) {

    const char* path = (*env)->GetStringUTFChars(env, j_path, NULL);
    if (!path) { LOGE("playit: path nulo"); return -1; }

    int n_argv = 0, n_envp = 0;
    char** argv = to_char_array(env, j_argv, &n_argv);
    char** envp = to_char_array(env, j_envp, &n_envp);
    const char* cwd = j_cwd ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    if (!argv) {
        LOGE("playit: no se pudo construir argv");
        if (path) (*env)->ReleaseStringUTFChars(env, j_path, path);
        free_string_array(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return -1;
    }

    /* Verificar que el binario existe y es ejecutable (apk_data_file label). */
    struct stat st;
    if (stat(path, &st) != 0) {
        LOGE("playit: stat(%s) fallo: %s", path, strerror(errno));
        goto playit_fail;
    }
    if (access(path, X_OK) != 0) {
        LOGE("playit: %s NO es ejecutable: %s", path, strerror(errno));
        LOGE("playit:  modo=%o uid=%d gid=%d (debe ser apk_data_file)",
             (int)(st.st_mode & 07777), (int)st.st_uid, (int)st.st_gid);
        goto playit_fail;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("playit: fork() fallo: %s", strerror(errno));
        goto playit_fail;
    }

    if (pid == 0) {
        /* ---- proceso hijo (daemon) ---- */
        if (setsid() < 0) {
            LOGI("playit: setsid() fallo (%s); se continua", strerror(errno));
        }
        if (cwd && chdir(cwd) != 0) {
            LOGE("playit: chdir(%s) fallo: %s", cwd, strerror(errno));
        }

        /* Cerrar fds heredados: los pipes del servidor JVM no son para el daemon. */
        if (g_out_pipe[0] >= 0) close(g_out_pipe[0]);
        if (g_out_pipe[1] >= 0) close(g_out_pipe[1]);
        if (g_in_pipe[0] >= 0) close(g_in_pipe[0]);
        if (g_in_pipe[1] >= 0) close(g_in_pipe[1]);

        /* stdin/stdout/stderr -> /dev/null. El daemon escribe a --log-path. */
        int devnull_w = open("/dev/null", O_WRONLY);
        if (devnull_w >= 0) {
            dup2(devnull_w, STDOUT_FILENO);
            dup2(devnull_w, STDERR_FILENO);
            close(devnull_w);
        }
        int devnull_r = open("/dev/null", O_RDONLY);
        if (devnull_r >= 0) {
            dup2(devnull_r, STDIN_FILENO);
            close(devnull_r);
        }

        LOGI("playit: arrancando daemon: %s", path);
        execve(path, argv, envp);
        /* Si llegamos aqui, execve fallo. */
        LOGE("playit: execve(%s) fallo: %s", path, strerror(errno));
        _exit(127);
    }

    /* ---- proceso padre ---- */
    g_playit_pid = pid;
    LOGI("playit: daemon iniciado pid=%d", pid);

    free_string_array(argv);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    return (jint)pid;

playit_fail:
    free_string_array(argv);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    return -1;
}

// Detiene el daemon playitd con SIGTERM + waitpid. Devuelve exit code.
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_stopPlayitDaemonNative(
        JNIEnv* env, jclass clazz) {
    if (g_playit_pid <= 0) {
        LOGE("playit: stopPlayitDaemon: no hay daemon registrado");
        return -1;
    }
    LOGI("playit: enviando SIGTERM a pid=%d", g_playit_pid);
    kill(g_playit_pid, SIGTERM);

    int status = 0;
    pid_t w = waitpid(g_playit_pid, &status, 0);
    if (w < 0 && errno == EINTR) {
        w = waitpid(g_playit_pid, &status, 0);
    }
    int rc = -1;
    if (w > 0) {
        if (WIFEXITED(status))  rc = WEXITSTATUS(status);
        if (WIFSIGNALED(status)) rc = 128 + WTERMSIG(status);
    }
    g_playit_pid = -1;
    LOGI("playit: daemon terminado rc=%d", rc);
    return (jint)rc;
}

// Comprueba si el daemon playitd sigue vivo (kill(pid, 0)).
JNIEXPORT jboolean JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_isPlayitRunningNative(
        JNIEnv* env, jclass clazz) {
    if (g_playit_pid <= 0) return JNI_FALSE;
    if (kill(g_playit_pid, 0) == 0) return JNI_TRUE;
    // ESRCH = proceso no existe: limpiar.
    if (errno == ESRCH) g_playit_pid = -1;
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Native IPC client for playitd daemon.
//
// POR QUE EXISTE (verificado en hardware, 2026-10-09):
// Android's LocalSocket API throws UnsupportedOperationException cuando se
// conecta a un socket Unix abstracto (@playit_ipc) en ciertas ROMs vendor
// (verificado en MIUI 14 / Android 13, Xiaomi M2112K11AG). El daemon
// listena en @playit_ipc y un probe C demuestra que el socket funciona
// correctamente via llamadas al sistema directas (socket+connect).
//
// Este cliente nativo usa socket(AF_UNIX) + connect() directamente,
// evitando la restriccion de LocalSocket de Java. El protocolo es
// line-delimited JSON: cada request/response termina con '\n'.
//
// El nombre del socket NO incluye el '@' — la funcion pone el primer byte
// de sun_path a '\0' para activar el namespace abstracto del kernel.
// ---------------------------------------------------------------------------

// Conecta a un socket Unix abstracto. name NO lleva el '@'.
// Devuelve fd, o -1 si falla (chequear errno).
static int native_ipc_connect(const char* name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("ipc: socket() fallo: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // Abstract socket: sun_path[0] = '\0', name starts at sun_path[1]
    addr.sun_path[0] = '\0';
    size_t len = strlen(name);
    if (len > sizeof(addr.sun_path) - 1) {
        LOGE("ipc: nombre de socket demasiado largo");
        close(fd);
        return -1;
    }
    memcpy(addr.sun_path + 1, name, len);
    socklen_t addr_len = (socklen_t)offsetof(struct sockaddr_un, sun_path) + 1 + len;
    if (connect(fd, (struct sockaddr*)&addr, addr_len) < 0) {
        LOGE("ipc: connect() fallo: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

// Lee una linea (hasta '\n' o EOF) del fd. No incluye el '\n' en el buffer.
// Devuelve bytes leidos (sin contar '\0'), 0 en EOF, -1 en error.
static int native_ipc_read_line(int fd, char* buf, int max_len) {
    int i = 0;
    while (i < max_len - 1) {
        ssize_t n = read(fd, buf + i, 1);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;   // EOF
        if (buf[i] == '\n') {
            buf[i] = '\0';
            return i;
        }
        i++;
    }
    buf[i] = '\0';
    return i;
}

// Escribe data + '\n' al fd. Devuelve 0 en OK, -1 en error.
static int native_ipc_write_line(int fd, const char* data) {
    size_t len = strlen(data);
    size_t total = len + 1;  // + '\n'
    char stackbuf[2048];
    char* buf = (total <= sizeof(stackbuf)) ? stackbuf : (char*)malloc(total);
    if (!buf) return -1;
    memcpy(buf, data, len);
    buf[len] = '\n';

    // SIGPIPE por si el otro extremo cerro: no matar el proceso app.
    struct sigaction old_ign, ign;
    memset(&ign, 0, sizeof(ign));
    ign.sa_handler = SIG_IGN;
    sigemptyset(&ign.sa_mask);
    sigaction(SIGPIPE, &ign, &old_ign);

    int rc = 0;
    size_t off = 0;
    while (off < total) {
        ssize_t w = write(fd, buf + off, total - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            LOGE("ipc: write() fallo: %s", strerror(errno));
            rc = -1;
            break;
        }
        off += (size_t)w;
    }
    sigaction(SIGPIPE, &old_ign, NULL);
    if (buf != stackbuf) free(buf);
    return rc;
}

// ---------------------------------------------------------------------------
// JNI wrappers para el cliente IPC nativo.
// El nombre del socket abstracto se pasa SIN el '@' (ej. "playit_ipc"),
// porque el codigo nativo pone el '\0' inicial para el namespace abstracto.
// ---------------------------------------------------------------------------

// JNI: connectIpcNative(String name) -> int (fd, o -1)
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_connectIpcNative(
        JNIEnv* env, jclass clazz, jstring j_name) {
    const char* name = (*env)->GetStringUTFChars(env, j_name, NULL);
    if (!name) return -1;
    int fd = native_ipc_connect(name);
    (*env)->ReleaseStringUTFChars(env, j_name, name);
    return fd;
}

// JNI: readLineIpcNative(int fd) -> String? (null en EOF/error)
JNIEXPORT jstring JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_readLineIpcNative(
        JNIEnv* env, jclass clazz, jint fd) {
    char buf[16384];
    int n = native_ipc_read_line(fd, buf, sizeof(buf));
    if (n <= 0) return NULL;   // EOF o error
    return (*env)->NewStringUTF(env, buf);
}

// JNI: writeLineIpcNative(int fd, String data) -> int (0 OK, -1 error)
JNIEXPORT jint JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_writeLineIpcNative(
        JNIEnv* env, jclass clazz, jint fd, jstring j_data) {
    const char* data = (*env)->GetStringUTFChars(env, j_data, NULL);
    if (!data) return -1;
    int rc = native_ipc_write_line(fd, data);
    (*env)->ReleaseStringUTFChars(env, j_data, data);
    return rc;
}

// JNI: closeIpcNative(int fd) -> void
JNIEXPORT void JNICALL
Java_com_minicraft_mcserver_runtime_NativeLauncher_closeIpcNative(
        JNIEnv* env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}