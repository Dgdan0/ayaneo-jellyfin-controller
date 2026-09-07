plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Must equal the Kotlin version exactly. The serialization compiler plugin
    // is versioned in lockstep with the compiler it plugs into, so a mismatch
    // here fails the build with an error that does not mention versions.
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
