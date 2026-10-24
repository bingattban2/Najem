import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp") version "2.3.12"
}

val releaseProperties = Properties().apply {
    val propertiesFile = rootProject.file("release.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

// Owner-provided environment secrets override the ignored local properties.
// This allows signing in a protected GitHub Actions environment without
// writing passwords to a project file or committing private credentials.
fun signingValue(property: String, environment: String): String? =
    System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: releaseProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "NAJIM_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "NAJIM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "NAJIM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "NAJIM_RELEASE_KEY_PASSWORD")

val signingParts = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
)
require(signingParts.all { it != null } || signingParts.all { it == null }) {
    "Incomplete release signing settings: specify all four values or none."
}
val releaseSigningEnabled = signingParts.all { it != null }
if (releaseSigningEnabled) {
    require(rootProject.file(requireNotNull(releaseStoreFile)).isFile) {
        "Release keystore not found; check local file or protected CI environment."
    }
}

android {
    namespace = "com.najimwifi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.najimwifi.app"

        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            // Avoid resource shrink until the Arabic assets and release PDFs
            // have passed visual verification on the signed APK.
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// Room schema export for versioned migrations and final acceptance tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Existing XML resources remain intact until their authorized migration.
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material.components)

    // Room 2.x stable, including Kotlin/Flow DAO support.
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Stable versions verified against the AndroidX and Zetetic releases.
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.0")

    // STEP 10.02: explicit Google consent and Android background scheduler.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // PHASE 12.01: unit fixtures and on-device Room/invariant verification.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}