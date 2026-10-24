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
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "br.com.kreive.bypasscam"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.4"

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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Compose BOM atualizado para versão estável moderna
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // CameraX - Versões atualizadas e estáveis (suporte aprimorado a dispositivos recentes e Android 14/15/16)
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
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
            pb.environment()["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
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
