import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is read from keystore.properties in the repository root, which
// is not in git. Without that file the release build is left unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.offlineresearch"
    compileSdk = 36

    // Same NDK and CMake versions as llama.cpp's own Android example at the pinned tag.
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "app.offlineresearch"
        minSdk = 28
        targetSdk = 35
        versionCode = 12
        versionName = "0.7.3"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Native code is always optimised, even in the debug APK: a Debug
                // llama.cpp build is several times slower and would make every
                // on-device measurement meaningless.
                arguments += "-DCMAKE_BUILD_TYPE=Release"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                // One CPU backend per ARM feature level, picked at runtime, so the
                // same APK runs on the Redmi and on a newer 12 GB phone.
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // profiles/*.json are bundled as assets; a profile pushed over adb overrides them.
    sourceSets["main"].assets.srcDir(rootProject.file("profiles"))

    packaging {
        jniLibs {
            // The ggml CPU backends are dlopen()ed from nativeLibraryDir, so the
            // .so files have to be extracted to disk at install time.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.sqlite.android)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
}
