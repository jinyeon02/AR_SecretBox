plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // ★ [추가] KSP 플러그인 로드 (apply false 필수)
    alias(libs.plugins.ksp) apply false
}