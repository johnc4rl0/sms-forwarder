plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-android is NOT applied under AGP 9 (Kotlin is built into AGP)
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
