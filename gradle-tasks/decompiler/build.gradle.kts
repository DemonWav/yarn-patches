import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.0"
}

group = "io.papermc.yarn"

repositories {
    mavenCentral()
    jcenter()
    maven("https://maven.fabricmc.net")
}

dependencies {
    implementation("net.fabricmc:fabric-loom:0.4-SNAPSHOT")
    implementation("io.github.fudge:forgedflower:1.7.0")
    implementation("org.jetbrains:intellij-fernflower:1.2.0.15")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}
