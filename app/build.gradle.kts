plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionCodeValue = if (versionPropertiesFile.exists()) {
    versionPropertiesFile.readLines().find { it.startsWith("versionCode=") }?.substringAfter("=")?.toInt() ?: 1
} else 1
val versionNameValue = if (versionPropertiesFile.exists()) {
    versionPropertiesFile.readLines().find { it.startsWith("versionName=") }?.substringAfter("=") ?: "1.0"
} else "1.0"

android {
    namespace = "dev.iyas.ninilala"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.iyas.ninilala"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = project.rootProject.file("release-keystore.jks")
            storeFile = if (keystoreFile.exists()) keystoreFile else null
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.github.pedroSG94:RTSP-Server:1.4.3")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
    implementation("com.github.alexeyvasilyev:rtsp-client-android:6.1.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}