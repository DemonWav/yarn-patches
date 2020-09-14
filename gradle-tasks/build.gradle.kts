plugins {
    `kotlin-dsl`
}

group = "io.papermc.yarn"

repositories {
    mavenCentral()
    jcenter()
    maven("https://maven.fabricmc.net")
}

dependencies {
    implementation("de.undercouch:gradle-download-task:4.1.1")

    implementation("net.fabricmc:stitch:0.5.1+build.77")
    implementation("net.fabricmc:tiny-remapper:0.3.1.72")
    implementation("net.fabricmc:fabric-loom:0.4-SNAPSHOT")

    implementation("org.jetbrains:intellij-fernflower:1.2.0.15")

    implementation("io.github.java-diff-utils:java-diff-utils:4.5")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.github.salomonbrys.kotson:kotson:2.5.0")
    implementation("com.google.guava:guava:29.0-jre")
}
