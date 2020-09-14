@file:Suppress("UnstableApiUsage")

package io.papermc.yarn

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.algorithm.myers.MyersDiff
import com.github.difflib.patch.Patch
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.common.hash.HashCode
import com.google.common.hash.Hashing
import com.google.gson.JsonObject
import net.fabricmc.stitch.merge.JarMerger
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.tasks.execution.TaskExecutionAccessListener
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.process.internal.worker.request.WorkerAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

open class DownloadWantedVersionManifest : DefaultTask() {
    @InputFile
    val manifestFile: RegularFileProperty = project.objects.fileProperty()

    @Input
    val version: Property<String> = project.objects.property()

    @OutputFile
    val versionFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val mcVersion = version.get()

        val obj = manifestFile.file.json<JsonObject>()
        val url = obj["versions"].array.first { element ->
            element["id"].string == mcVersion
        }["url"].string

        download {
            src(url)
            dest(outputFile(versionFile))
            onlyIfModified(true)
            useETag("strongOnly")
        }
    }
}

open class DownloadMcJars : DefaultTask() {
    @InputFile
    val versionFile: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val clientJar: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val serverJar: RegularFileProperty = project.objects.fileProperty()

    init {
        outputs.upToDateWhen {
            val obj = versionFile.file.json<JsonObject>()
            validateChecksum(clientJar.file, obj, "client") &&
                validateChecksum(serverJar.file, obj, "server")
        }
    }

    @TaskAction
    fun run() {
        val obj = versionFile.file.json<JsonObject>()

        val parentDir = serverJar.file.parentFile
        project.download {
            src(listOf(
                obj["downloads"]["client"]["url"].string,
                obj["downloads"]["server"]["url"].string
            ))
            dest(parentDir)
            onlyIfModified(true)
            useETag("strongOnly")
        }

        parentDir.resolve("server.jar").renameTo(serverJar.file)
        parentDir.resolve("client.jar").renameTo(clientJar.file)
    }

    private fun validateChecksum(file: File, obj: JsonObject, type: String): Boolean {
        if (!file.exists()) {
            return false
        }
        @Suppress("DEPRECATION")
        val hash = file.byteSource.hash(Hashing.sha1())
        val expectedHashString = obj["downloads"][type]["sha1"].string
        val expectedHash = HashCode.fromString(expectedHashString)
        return hash == expectedHash
    }
}

open class MergeJars : DefaultTask() {
    @InputFile
    val clientJar: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val serverJar: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputJar: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        JarMerger(clientJar.file, serverJar.file, outputFile(outputJar)).use { merger ->
            merger.merge()
        }
    }
}

open class SetupMcDeps : DefaultTask() {
    @InputFile
    val versionFile: RegularFileProperty = project.objects.fileProperty()

    @Input
    val config: Property<Configuration> = project.objects.property()

    @TaskAction
    fun run() {
        setup(project, versionFile.file, config.get())
    }

    companion object {
        fun setup(project: Project, versionFile: File, config: Configuration) {
            val obj = versionFile.json<JsonObject>()

            val conf = config.name
            for (library in obj["libraries"].array) {
                project.dependencies.add(conf, library["name"].string)
            }
        }
    }
}

open class MapIntermediaryJar @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @InputFiles
    val mcLibs: Property<Configuration> = project.objects.property()

    @InputFile
    val mcJar: RegularFileProperty = project.objects.fileProperty()

    @InputFile
    val mappingsFile: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val intermediaryJar: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val libs = mcLibs.map { it.resolve().map { file -> project.layout.projectDirectory.file(file.absolutePath) } }
        val queue = workerExecutor.noIsolation()
        queue.submit(MapWorker::class.java) {
            mcLibs.set(libs)
            mcJar.set(this@MapIntermediaryJar.mcJar)
            mappingsFile.set(this@MapIntermediaryJar.mappingsFile)
            intermediaryJar.set(this@MapIntermediaryJar.intermediaryJar)
        }
    }
}

interface MapParams : WorkParameters {
    val mcLibs: ListProperty<RegularFile>
    val mcJar: RegularFileProperty
    val mappingsFile: RegularFileProperty
    val intermediaryJar: RegularFileProperty
}

abstract class MapWorker : WorkAction<MapParams> {
    override fun execute() {
        val mappings = parameters.mappingsFile.path

        val remapper = TinyRemapper.newRemapper()
            .withMappings(TinyUtils.createTinyMappingProvider(mappings, "official", "intermediary"))
            .renameInvalidLocals(true)
            .rebuildSourceFilenames(true)
            .build()

        try {
            OutputConsumerPath.Builder(parameters.intermediaryJar.path).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(parameters.mcJar.path)
                remapper.readInputs(parameters.mcJar.path)

                val classpath = parameters.mcLibs.get()
                    .map { it.asFile }
                    .filter { it.isFile }
                    .map { it.toPath() }
                    .toTypedArray()
                remapper.readClassPath(*classpath)

                remapper.apply(outputConsumer)
            }
        } finally {
            remapper.finish()
        }
    }
}

open class CreateWorkspace @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        val queue = workerExecutor.noIsolation()
        queue.submit(CreateWorkspaceWorker::class.java) {
            inputJar.set(this@CreateWorkspace.inputJar)
            outputDir.set(this@CreateWorkspace.outputDir)
        }
    }
}

interface CreateWorkspaceParams : WorkParameters {
    val inputJar: RegularFileProperty
    val outputDir: DirectoryProperty
}

abstract class CreateWorkspaceWorker @Inject constructor(
    private val fileSystem: FileSystemOperations,
    private val archives: ArchiveOperations
) : WorkAction<CreateWorkspaceParams> {
    override fun execute() {
        val output = parameters.outputDir.file
        if (output.exists()) {
            output.deleteRecursively()
        }
        output.mkdirs()

        val srcDir = output.resolve("src/main/java")
        srcDir.mkdir()

        fileSystem.copy {
            from(archives.zipTree(parameters.inputJar))
            into(srcDir)
        }
    }
}

open class GeneratePatches @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @InputFile
    val originalJar: RegularFileProperty = project.objects.fileProperty()
    @InputDirectory
    val inputDir: DirectoryProperty = project.objects.directoryProperty()
    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun run() {
        workerExecutor.noIsolation().submit(GeneratePatchesWorker::class.java) {
            originalJar.set(this@GeneratePatches.originalJar)
            inputDir.set(this@GeneratePatches.inputDir)
            outputDir.set(this@GeneratePatches.outputDir)
        }
    }
}
interface GeneratePatchesParams : WorkParameters {
    val originalJar: RegularFileProperty
    val inputDir: DirectoryProperty
    val outputDir: DirectoryProperty
}
abstract class GeneratePatchesWorker : WorkAction<GeneratePatchesParams> {
    override fun execute() {
        val input = parameters.inputDir.file
        val output = parameters.outputDir.file

        output.deleteRecursively()
        output.mkdirs()

        val diffAlg = MyersDiff<String>()

        ZipFile(parameters.originalJar.file).use { zipFile ->
            input.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(input)
                    val entry = zipFile.getEntry(relative.path.replace(File.separatorChar, '/'))
                    val originalLines = zipFile.getInputStream(entry).reader().readLines()
                    val newLines = file.readLines()

                    val changes = diffAlg.computeDiff(originalLines, newLines, null)
                    if (changes.isEmpty()) {
                        return@forEach
                    }

                    val patch = Patch.generate(originalLines, newLines, changes)
                    val diffLines = UnifiedDiffUtils.generateUnifiedDiff(
                        "a/" + relative.path,
                        "b/" + relative.path,
                        originalLines,
                        patch,
                        3
                    )

                    val outputFile = output.resolve(relative).resolveSibling(relative.name + ".patch")
                    outputFile.parentFile.mkdirs()
                    outputFile.bufferedWriter().use { writer ->
                        for (line in diffLines) {
                            writer.appendln(line)
                        }
                    }
                }
        }
    }
}

open class ApplyPatches @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()
    @Optional
    @InputDirectory
    val patchDir: DirectoryProperty = project.objects.directoryProperty().convention(null)
    @OutputFile
    val outputJar: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val patches = patchDir.orNull
        if (patches == null) {
            inputJar.file.copyTo(outputFile(outputJar.file))
            return
        }

        workerExecutor.noIsolation().submit(ApplyPatchesWorker::class.java) {
            inputJar.set(this@ApplyPatches.inputJar)
            patchDir.set(patches)
            outputJar.set(this@ApplyPatches.outputJar)
        }
    }
}

interface ApplyPatchesParams : WorkParameters {
    val inputJar: RegularFileProperty
    val patchDir: DirectoryProperty
    val outputJar: RegularFileProperty
}
abstract class ApplyPatchesWorker : WorkAction<ApplyPatchesParams> {
    override fun execute() {
        val patchDir = parameters.patchDir.file

        ZipOutputStream(parameters.outputJar.file.outputStream()).use { zipOutput ->
            ZipFile(parameters.inputJar.file).use { zipFile ->
                for (entry in zipFile.entries()) {
                    zipOutput.putNextEntry(ZipEntry(entry.name))
                    try {
                        val patchFile = patchDir.resolve(entry.name + ".patch")
                        if (!patchFile.exists()) {
                            // Copy file
                            zipFile.getInputStream(entry).use { inputStream ->
                                inputStream.copyTo(zipOutput)
                            }
                        } else {
                            val patch = UnifiedDiffUtils.parseUnifiedDiff(patchFile.readLines())
                            val originalLines = zipFile.getInputStream(entry).reader().readLines()
                            val newLines = DiffUtils.patch(originalLines, patch)
                            zipOutput.bufferedWriter().let { writer ->
                                for (line in newLines) {
                                    writer.appendln(line)
                                }
                                writer.flush()
                            }
                        }
                    } finally {
                        zipOutput.closeEntry()
                    }
                }
            }
        }
    }
}
