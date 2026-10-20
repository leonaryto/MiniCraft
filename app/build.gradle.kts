import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firmado de release: se lee de keystore.properties (raiz del proyecto, NO commiteado).
// Si el fichero no existe (p.ej. CI o build debug), el release se compila sin firmar.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.minicraft.mcserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.minicraft.mcserver"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"

        // Solo arm64-v8a: el JRE bionic que descargamos es aarch64, y anadir
        // otras ABIs multiplicaria el tamano del APK sin aportar nada al MVP.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    // El lanzador nativo reemplaza a /system/bin/linker64 (ver cpp/launcher.c).
    // Sin el, la JVM no arranca: linker64 exige rutas absolutas en todos sus args.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // El stub de la JVM (jniLibs/arm64-v8a/bin/java) debe residir en nativeLibraryDir:
    // /data/user/0 esta montado con W^X (execve -> EACCES), mientras que nativeLibraryDir
    // tiene otra etiqueta SELinux (apk_data_file) y Android garantiza el bit de
    // ejecucion de sus .so. Ver docs/FASE-2-ESTADO.md seccion 5.
    packaging {
        jniLibs.useLegacyPackaging = true      // extrae a nativeLibraryDir en vez de mapear del APK
        // El stub es un ejecutable, no una libreria: no debe perder simbolos al strip.
        jniLibs.keepDebugSymbols += setOf(
        "**/libmcjvmboot.so",
        "**/libplayitd.so",
    )
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }
}

dependencies {
    // Sin dependencias externas de red, a proposito: la Activity de diagnostico solo
    // usa el framework de Android (Activity/Log/TextView). Asi el build funciona
    // con --offline sin depender de lo que haya cacheado, y el APK pesa lo minimo.

    // Decodificador XZ para extraer la JRE de los .deb de Termux dentro de la
    // app (JreProvisioner). Java/Android no trae XZ. Jar local verificado:
    // org.tukaani:xz 1.9, 113 KB, sin dependencias, multi-release Java 8+.
    implementation(files("libs/xz-1.9.jar"))
}