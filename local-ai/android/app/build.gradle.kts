import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val ciVersionCode = (System.getenv("LOCALAI_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.localai.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.localai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"

        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                // Native code is always optimised: a -O0 llama.cpp is unusably slow on phones.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    signingConfigs {
        // TEST-ONLY key committed on purpose so CI builds install over each other.
        // Never use it for a Play Store release.
        create("test") {
            storeFile = file("debug-release.keystore")
            storePassword = "localai-test"
            keyAlias = "localai-test"
            keyPassword = "localai-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("test")
        }
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // Extract .so files at install: llama.cpp's backend loader scans nativeLibraryDir for the
        // libggml-cpu-*.so variants, which it cannot see inside the APK.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md") }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Natural on-device voices (sherpa-onnx, Apache-2.0). The AAR is not on Maven Central: fetch the pinned release once
// and verify its SHA-256 before use. Only arm64-v8a is packaged (abiFilters), all native libs are 16 KB aligned.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
fun sha256Of(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}
if (!sherpaAar.exists() || sha256Of(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
    check(sha256Of(tmp) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
    tmp.renameTo(sherpaAar)
}

dependencies {
    implementation(files(sherpaAar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.tesseract4android)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
