plugins {
    id("komust.kotlin-module")
    `java-gradle-plugin`
}

dependencies {
    // The thin adapter invokes the scope resolver in-process (ADR-0005 §1).
    implementation(project(":komust-scope"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("komust") {
            id = "io.komust"
            implementationClass = "io.komust.gradle.KomustGradlePlugin"
            displayName = "komust"
            description = "Kotlin-native mutation testing — the mutationTest task and komust {} DSL."
        }
    }
}
