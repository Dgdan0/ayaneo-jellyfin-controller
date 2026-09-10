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
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Left off deliberately, as in the sibling project. kotlinx.serialization
            // needs R8 keep rules to survive shrinking, and this is a personal build
            // where APK size has never been the constraint. Revisit only if it is.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Navigation, input routing, retry/cache policy and load-state transitions
    // live in plain Kotlin classes that take primitives rather than
    // View/MotionEvent/Context, so they can be tested on the JVM without a
    // device or Robolectric. Verifying this app on hardware is slow enough that
    // anything decidable off-device should be.
    testImplementation("junit:junit:4.13.2")
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
    // Pocket DS firmware has no platform AC-3/E-AC-3 decoder. This arm64-only
    // Media3 extension keeps Original offline files playable without converting
    // or discarding their audio tracks.
    implementation(files("libs/media3-decoder-ffmpeg-1.4.1-ac3-arm64.aar"))
    // Posters come from the hub, so they need the same bearer token, the same
    // TLS trust and the same connection pool as the API. Coil takes an
    // OkHttpClient in one line; Glide would need an extra artifact and an
    // annotation processor in a build that has no KSP or kapt at all.
    implementation("io.coil-kt:coil:2.6.0")
}
