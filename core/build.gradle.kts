plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    // Several tests report measured envelopes rather than only asserting; that output is the
    // point of running them.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
