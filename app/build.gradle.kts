import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Keep the project buildable before a team member adds the Firebase Console config.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val cloudinaryProperties = Properties()
val cloudinaryPropertiesFile = rootProject.file("cloudinary.properties")
if (cloudinaryPropertiesFile.exists()) {
    cloudinaryProperties.load(cloudinaryPropertiesFile.inputStream())
}

val cloudNameFromLocal = localProperties.getProperty("CLOUDINARY_CLOUD_NAME")?.trim()
val cloudNameFromShared = cloudinaryProperties.getProperty("CLOUDINARY_CLOUD_NAME")?.trim()
val cloudinaryCloudName = if (!cloudNameFromLocal.isNullOrEmpty()) cloudNameFromLocal else (cloudNameFromShared ?: "")

val uploadPresetFromLocal = localProperties.getProperty("CLOUDINARY_UPLOAD_PRESET")?.trim()
val uploadPresetFromShared = cloudinaryProperties.getProperty("CLOUDINARY_UPLOAD_PRESET")?.trim()
val cloudinaryUploadPreset = if (!uploadPresetFromLocal.isNullOrEmpty()) uploadPresetFromLocal else (uploadPresetFromShared ?: "")

android {
    namespace = "com.example.lostlink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.lostlink"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", "\"$cloudinaryCloudName\"")
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", "\"$cloudinaryUploadPreset\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.play.services.auth)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
