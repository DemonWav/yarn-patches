@file:Suppress("UnstableApiUsage")

package io.papermc.yarn

import com.github.salomonbrys.kotson.fromJson
import com.google.common.io.ByteSource
import com.google.common.io.Files as GFiles
import com.google.gson.Gson
import de.undercouch.gradle.tasks.download.DownloadAction
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Path

val gson = Gson()

val File.byteSource: ByteSource
    get() = GFiles.asByteSource(this)

inline fun <reified T : Any> File.json(): T {
    return bufferedReader().use { reader ->
        gson.fromJson(reader)
    }
}

fun Project.download(configure: DownloadAction.() -> Unit) {
    val action = DownloadAction(this)
    action.configure()
    action.execute()
}

fun Task.download(configure: DownloadAction.() -> Unit) {
    val action = DownloadAction(project, this)
    action.configure()
    action.execute()
}

fun Project.outputFile(file: Any): File {
    val f = project.file(file)
    f.parentFile.let { parent ->
        if (!parent.exists()) {
            parent.mkdirs()
        }
    }
    if (f.exists()) {
        f.deleteRecursively()
    }
    return f
}
fun Task.outputFile(file: Any): File {
    return project.outputFile(file)
}

val RegularFileProperty.file: File
    get() = get().asFile
val RegularFileProperty.path: Path
    get() = file.toPath()
val DirectoryProperty.file: File
    get() = get().asFile
