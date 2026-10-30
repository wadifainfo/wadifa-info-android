import java.util.Properties

// Same upload key as the old Trusted Web Activity wrapper (%USERPROFILE%/.wadifa-app/keystore.properties), so this native
// app installs as an UPDATE of com.wadifainfo.app (Uptodown/Aptoide) and the site's assetlinks.json verifies its links.
// No API key or credential is compiled into the APK: the Wadifa Info API is public, read-only and cached server-side.
val release = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.wadifa-app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(k: String) = release.getProperty(k)?.takeIf { it.isNotBlank() }

// AdMob: Google's public TEST ids (safe to commit). Real ids come only from an untracked file and only in release.
val admob = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.wadifa-app/admob.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testBanner = "ca-app-pub-3940256099942544/9214589741"   // adaptive banner test unit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wadifainfo.concours"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.wadifainfo.app"
        minSdk = 24   // Play automatic protection needs 24+
        targetSdk = 36
        versionCode = 7
        versionName = "2.4"
        resourceConfigurations += listOf("ar", "fr")
        manifestPlaceholders["admobAppId"] = testAppId
        buildConfigField("String", "ADMOB_BANNER", "\"$testBanner\"")
    }
    signingConfigs {
        if (prop("storeFile") != null) create("upload") {
            storeFile = file(prop("storeFile")!!); storePassword = prop("storePassword")
            keyAlias = prop("keyAlias"); keyPassword = prop("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            manifestPlaceholders["admobAppId"] = admob.getProperty("appId")?.takeIf { it.isNotBlank() } ?: testAppId
            buildConfigField("String", "ADMOB_BANNER", "\"${admob.getProperty("banner")?.takeIf { it.isNotBlank() } ?: testBanner}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // play = store builds with AdMob; fdroid = no proprietary code (F-Droid builds it from source). Ads.kt exists once per flavour.
    flavorDimensions += "store"
    productFlavors { create("play") { dimension = "store" }; create("fdroid") { dimension = "store" } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { generateLocaleConfig = true }
    bundle { language { enableSplit = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")   // java.time on API 23-25
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")   // R8 keeps only the icons used
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    "playImplementation"("com.google.android.gms:play-services-ads:24.4.0")
    "playImplementation"("com.google.android.ump:user-messaging-platform:3.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real org.json on the JVM (Android's is a stub in unit tests)
}
