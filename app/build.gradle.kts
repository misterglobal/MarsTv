import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val providerProperties = Properties().apply {
    val localFile = rootProject.file("providers.properties")
    if (localFile.exists()) localFile.inputStream().use(::load)
}

val privatePortalUrl = providers.gradleProperty("MARSTV_PRIVATE_PORTAL_URL")
    .orNull
    ?: providerProperties.getProperty("MARSTV_PRIVATE_PORTAL_URL", "")
val escapedPrivatePortalUrl = privatePortalUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val marsBackendUrl = providerProperties.getProperty("MARSTV_BACKEND_URL", "https://marstv.online/api/v1/")
require(marsBackendUrl.startsWith("https://")) { "MARSTV_BACKEND_URL must use HTTPS" }
val entitlementPublicKeyPem = providerProperties.getProperty("MARSTV_ENTITLEMENT_PUBLIC_KEY_PEM", "")
val updatePublicKeyPem = providerProperties.getProperty("MARSTV_UPDATE_PUBLIC_KEY_FILE", "").takeIf(String::isNotBlank)
    ?.let { rootProject.file(it).readText() }
    ?: providerProperties.getProperty("MARSTV_UPDATE_PUBLIC_KEY_PEM", rootProject.file("app/update-public-key.txt").readText())
val updateKeyId = providerProperties.getProperty("MARSTV_UPDATE_KEY_ID", "release-2026-01")
fun String.asBuildConfigString() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use(::load)
}
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
if (releaseSigningFile.exists()) {
    val missingSigningKeys = requiredSigningKeys.filterNot(releaseSigningProperties::containsKey)
    require(missingSigningKeys.isEmpty()) {
        "keystore.properties is missing: ${missingSigningKeys.joinToString()}"
    }
}
val releaseSigningConfigured = releaseSigningFile.exists()

android {
    namespace = "tv.mars.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.mars.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PRIVATE_PORTAL_URL", "\"$escapedPrivatePortalUrl\"")
        buildConfigField("String", "MARS_BACKEND_URL", "\"${marsBackendUrl.asBuildConfigString()}\"")
        buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY_PEM", "\"${entitlementPublicKeyPem.asBuildConfigString()}\"")
        buildConfigField("String", "UPDATE_PUBLIC_KEY_PEM", "\"${updatePublicKeyPem.asBuildConfigString()}\"")
        buildConfigField("String", "UPDATE_KEY_ID", "\"${updateKeyId.asBuildConfigString()}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("production") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("production")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
