import io.papermc.yarn.GeneratePatches
import io.papermc.yarn.SetupMcDeps

buildscript {
    repositories {
        mavenCentral()
        jcenter()
        maven("https://maven.fabricmc.net")
    }
    dependencies {
        classpath("io.papermc.yarn:gradle-tasks")
    }
}

plugins {
    java
}

val typeName = name
val versionName = parent?.name ?: error("No parent project defined")

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net/") {
        metadataSources {
            artifact()
        }
    }
    maven("https://maven.fabricmc.net")
}

val versionFile = rootProject.file(".gradle/cache/versions/$versionName/version_info_$versionName.json")
if (versionFile.exists()) {
    SetupMcDeps.setup(project, versionFile, configurations.implementation.get())
}

dependencies {
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    if (name == "merged") {
        implementation("net.fabricmc:fabric-loader:0.9.3+build.207")
    }
}

tasks.register("generatePatches", GeneratePatches::class) {
    originalJar.set(rootProject.file(".gradle/cache/versions/$versionName/jars/$typeName-remapped-$versionName-sources-patched.jar"))
    inputDir.set(file("src/main/java"))
    outputDir.set(rootProject.file("patches/$versionName/$typeName"))
}
