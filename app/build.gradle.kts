plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pocketds.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketds.hub"
        minSdk = 26
        targetSdk = 34
        // Must always increase: Android/Obtanium correctly rejects a release
        // whose version code is lower than the APK already on the Pocket DS.
        versionCode = 51
        versionName = "0.4.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The everyday app's own key. The debug key that signed 0.3.12 was lost on
    // 2026-09-30, so that install could only be replaced, never updated. This key
    // lives outside this public repository; its file and passwords come from the
    // user-level ~/.gradle/gradle.properties. Without them a release build is
    // unsigned and cannot be installed, which is the safe failure.
    signingConfigs {
        val store = providers.gradleProperty("POCKETDS_KEYSTORE_FILE").orNull
        if (store != null) {
            create("pocketds") {
                storeFile = file(store)
                storePassword = providers.gradleProperty("POCKETDS_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("POCKETDS_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("POCKETDS_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        create("uitest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            versionNameSuffix = "-uitest"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfigs.findByName("pocketds")?.let { signingConfig = it }
            // Left off deliberately, as in the sibling project. kotlinx.serialization
            // needs R8 keep rules to survive shrinking, and this is a personal build
            // where APK size has never been the constraint. Revisit only if it is.
            isMinifyEnabled = false
        }
    }

    // Connected instrumentation uninstalls its target after the run. Never target
    // the everyday app: its account settings and downloaded media must survive.
    testBuildType = "uitest"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // Navigation, input routing, retry/cache policy and load-state transitions
    // live in plain Kotlin classes that take primitives rather than
    // View/MotionEvent/Context, so they can be tested on the JVM without a
    // device or Robolectric. Verifying this app on hardware is slow enough that
    // anything decidable off-device should be.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Spring physics for the focus ring. The damping and stiffness that feel
    // right on this hardware were already found in the sibling project; this
    // exposes them directly, which is what actually needs tuning.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // The app is nothing but async work over a slow link, and cancellation is
    // the whole problem: backing out of a screen has to kill the twenty poster
    // requests it started. Coil pulls this in transitively regardless, so
    // declaring it costs nothing. Only the coroutines runtime is adopted --
    // no ViewModel, LiveData, Flow, Lifecycle or DI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // 21.5 is the last Cast line compatible with this app's Kotlin 1.9 toolchain.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    // Pocket DS firmware has no platform AC-3/E-AC-3 decoder. This arm64-only
    // Media3 extension keeps Original offline files playable without converting
    // or discarding their audio tracks.
    implementation(files("libs/media3-decoder-ffmpeg-1.4.1-ac3-arm64.aar"))
    // Posters come from the hub, so they need the same bearer token, the same
    // TLS trust and the same connection pool as the API. Coil takes an
    // OkHttpClient in one line; Glide would need an extra artifact and an
    // annotation processor in a build that has no KSP or kapt at all.
    implementation("io.coil-kt:coil:2.6.0")
    // Comic and manga scans routinely exceed the bitmap memory budget. This
    // view decodes only visible tiles while retaining native pinch/pan support.
    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
    // 3.0.0 is the Readium line built for compileSdk 34 and Kotlin 1.9.24,
    // matching this app without a toolchain migration. Readium owns EPUB
    // parsing/navigation; the Pocket UI continues to own every control.
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.0")
}
