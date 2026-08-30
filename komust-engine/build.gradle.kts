plugins {
    id("komust.kotlin-module")
}

dependencies {
    // The engine drives tests through the JUnit Platform Launcher API directly
    // (ADR-0005 §4) — the launcher is a main dependency here, not just a test one.
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.platform.launcher)

    testImplementation(libs.junit.jupiter)
}
