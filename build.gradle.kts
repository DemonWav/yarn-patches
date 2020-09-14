@file:Suppress("UnstableApiUsage")

import de.undercouch.gradle.tasks.download.Download
import io.papermc.yarn.CreateWorkspace
import io.papermc.yarn.DecompileJar
import io.papermc.yarn.DownloadMcJars
import io.papermc.yarn.DownloadWantedVersionManifest
import io.papermc.yarn.MapIntermediaryJar
import io.papermc.yarn.MergeJars
import io.papermc.yarn.SetupMcDeps
import io.papermc.yarn.ApplyPatches

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

group = "io.papermc"
version = "1.0.0-SNAPSHOT"

val mappings: Configuration by configurations.creating
val mclibs: Configuration by configurations.creating

val decompile: Configuration by configurations.creating

val minecraftVersion: String by project

repositories {
    mavenCentral()
    jcenter()
    maven("https://libraries.minecraft.net/") {
        metadataSources {
            artifact()
        }
    }
    maven("https://maven.fabricmc.net")
}

dependencies {
    mappings("net.fabricmc:intermediary:$minecraftVersion:v2")

    decompile("io.papermc.yarn:decompiler")
}

val cacheDir = file(".gradle/cache")
val versionDir = cacheDir.resolve("versions/$minecraftVersion")
val jarsDir = versionDir.resolve("jars")

val workspaceDir = file("workspace")
val workspaceVersionDir = workspaceDir.resolve(minecraftVersion)

val cleanCache by tasks.registering(Delete::class) {
    delete(cacheDir)
}
val cleanWorkspace by tasks.registering(Delete::class) {
    delete(workspaceDir)
}

val downloadVersionManifest by tasks.registering(Download::class) {
    src("https://launchermeta.mojang.com/mc/game/version_manifest.json")
    dest(cacheDir.resolve("version_manifest.json"))
    onlyIfModified(true)
    useETag("strongOnly")
}

val downloadWantedVersionManifest by tasks.registering(DownloadWantedVersionManifest::class) {
    manifestFile.set(project.layout.file(downloadVersionManifest.map { it.dest }))
    version.set(minecraftVersion)
    versionFile.set(versionDir.resolve("version_info_$minecraftVersion.json"))
}

val downloadMcJars by tasks.registering(DownloadMcJars::class) {
    versionFile.set(downloadWantedVersionManifest.flatMap { it.versionFile })
    clientJar.set(jarsDir.resolve("client-$minecraftVersion.jar"))
    serverJar.set(jarsDir.resolve("server-$minecraftVersion.jar"))
}

val setupMcDeps by tasks.registering(SetupMcDeps::class) {
    versionFile.set(downloadWantedVersionManifest.flatMap { it.versionFile })
    config.set(mclibs)
}

val extractIntermediaryOutput = versionDir.resolve("$minecraftVersion-intermediary-v2.tiny")
val extractIntermediary by tasks.registering(Copy::class) {
    from(project.zipTree(project.provider { mappings.resolve().first() })) {
        include("mappings/mappings.tiny")
        rename("mappings.tiny", "../${extractIntermediaryOutput.name}")
    }
    into(extractIntermediaryOutput.parentFile)
    doLast {
        extractIntermediaryOutput.parentFile.resolve("mappings").delete()
    }
}

val mergeJars by tasks.registering(MergeJars::class) {
    clientJar.set(downloadMcJars.flatMap { it.clientJar })
    serverJar.set(downloadMcJars.flatMap { it.serverJar })
    outputJar.set(jarsDir.resolve("merged-$minecraftVersion.jar"))
}

val filterServerJar: TaskProvider<Zip> by tasks.registering(Zip::class) {
    dependsOn(downloadMcJars)
    from(project.zipTree(downloadMcJars.flatMap { it.serverJar })) {
        include(
            "/*.class",
            "/net/minecraft/**/*.class"
        )
    }
    destinationDirectory.set(jarsDir)
    archiveFileName.set("server-filtered-$minecraftVersion.jar")
}

val filterClientJar: TaskProvider<Zip> by tasks.registering(Zip::class) {
    dependsOn(downloadMcJars)
    from(project.zipTree(downloadMcJars.flatMap { it.clientJar })) {
        include(
            "/*.class",
            "/com/mojang/**/*.class",
            "/net/minecraft/**/*.class"
        )
    }
    destinationDirectory.set(jarsDir)
    archiveFileName.set("client-filtered-$minecraftVersion.jar")
}

val setupServer = setupWorkspace("server", filterServerJar.flatMap { it.archiveFile })
val setupClient = setupWorkspace("client", filterClientJar.flatMap { it.archiveFile })
val setupMerged = setupWorkspace("merged", mergeJars.flatMap { it.outputJar })

val setup by tasks.registering {
    dependsOn(setupServer, setupClient, setupMerged)
}

fun setupWorkspace(type: String, filteredJar: Provider<RegularFile>): TaskProvider<out Task> {
    val typeName = type.capitalize()
    val mapJar = tasks.register("map${typeName}Jar", MapIntermediaryJar::class) {
        dependsOn(extractIntermediary)
        mcLibs.set(mclibs)
        mcJar.set(filteredJar)
        mappingsFile.set(extractIntermediaryOutput)
        intermediaryJar.set(jarsDir.resolve("$type-remapped-$minecraftVersion.jar"))
    }

    val decompileJar = tasks.register("decompile${typeName}Jar", DecompileJar::class) {
        inputJar.set(mapJar.flatMap { it.intermediaryJar })
        libraries.set(mclibs)
        runtimeConfig.set(decompile)
        mappings.set(extractIntermediaryOutput)
    }

    val applyPatches = tasks.register("apply${typeName}Patches", ApplyPatches::class) {
        inputJar.set(decompileJar.flatMap { it.outputJar })
        file("patches/$minecraftVersion/$type").let { patches ->
            if (patches.exists()) {
                patchDir.set(patches)
            }
        }
        outputJar.set(jarsDir.resolve("$type-remapped-$minecraftVersion-sources-patched.jar"))
    }

    return tasks.register("setup${typeName}", CreateWorkspace::class) {
        inputJar.set(applyPatches.flatMap { it.outputJar })
        outputDir.set(project.layout.dir(project.provider { workspaceVersionDir.resolve(type) }))
    }
}
