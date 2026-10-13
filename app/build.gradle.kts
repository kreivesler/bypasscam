import java.io.File
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // O plugin de Compose gerencia tudo agora, não precisa mais de composeOptions
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "com.bypasscam.app"
    compileSdk = 35 // Ajustado para a versão estável mais comum de 2026
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "br.com.kreive.bypasscam"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Sintaxe atualizada para evitar o erro de String no jvmTarget
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // O BLOCO composeOptions FOI REMOVIDO DAQUI

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Usando o BOM mais estável para garantir que Material3 e UI conversem bem
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // CameraX - Versões estáveis para o Moto g04
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("buildRust") {
    inputs.dir("../bypass-audio-rust")
    outputs.dir("src/main/jniLibs")

    doLast {
        val ndkDir = android.ndkDirectory.absolutePath
        val targets = mapOf(
            "aarch64-linux-android" to "arm64-v8a",
            "armv7-linux-androideabi" to "armeabi-v7a",
            "x86_64-linux-android" to "x86_64"
        )
        targets.forEach { (rustTarget, androidTarget) ->
            val pb = ProcessBuilder("cargo", "ndk", "-t", rustTarget, "build", "--release")
                .directory(File(projectDir.parentFile, "bypass-audio-rust"))
                .inheritIO()
            pb.environment()["ANDROID_NDK_HOME"] = ndkDir
            val homeDir = System.getProperty("user.home")
            val cargoBin = "$homeDir/.cargo/bin"
            val currentPath = System.getenv("PATH") ?: ""
            pb.environment()["PATH"] = "$cargoBin:$currentPath"

            val process = pb.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("Cargo build failed for target $rustTarget with exit code $exitCode")
            }

            val soFile = File(projectDir.parentFile, "bypass-audio-rust/target/$rustTarget/release/libbypass_audio_rust.so")
            val destDir = file("src/main/jniLibs/$androidTarget")
            destDir.mkdirs()
            soFile.copyTo(File(destDir, "libbypass_audio_rust.so"), overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildRust")
}
