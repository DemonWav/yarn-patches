@file:Suppress("UnstableApiUsage")

package io.papermc.yarn

import net.fabricmc.loom.api.decompilers.DecompilationMetadata
import net.fabricmc.loom.util.ConsumingOutputStream
import net.fabricmc.loom.util.OperatingSystem
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.nio.file.Path
import java.text.MessageFormat
import java.util.Stack
import java.util.function.Consumer
import java.util.function.Supplier

open class DecompileJar : DefaultTask() {
    @InputFile
    val inputJar: RegularFileProperty = project.objects.fileProperty()
    @CompileClasspath
    val libraries: Property<Configuration> = project.objects.property()
    @Classpath
    val runtimeConfig: Property<Configuration> = project.objects.property()
    @InputFile
    val mappings: RegularFileProperty = project.objects.fileProperty()

    @OutputFile
    val outputJar: RegularFileProperty = project.objects.fileProperty()
        .convention(inputJar.sibling("-sources-base.jar"))
    @OutputFile
    val lineMapFile: RegularFileProperty = project.objects.fileProperty()
        .convention(inputJar.sibling("-sources-base.linemap"))

    @TaskAction
    fun run() {
        val threads = Runtime.getRuntime().availableProcessors()

        val libs = libraries.get().resolve().filter { it.isFile }.map { it.toPath() }
        val metadata = DecompilationMetadata(threads, mappings.path, libs)

        val decompiler = ForgedFlowerDecompiler(project, runtimeConfig)
        decompiler.decompile(inputJar.path, outputJar.path, lineMapFile.path, metadata)
    }

    private fun RegularFileProperty.sibling(suffix: String): Provider<RegularFile> {
        return project.layout.file(this.map {
            val file = it.asFile
            return@map file.resolveSibling("${file.name.substringBeforeLast('.')}$suffix")
        })
    }
}

class ForgedFlowerDecompiler(private val project: Project, private val config: Any) {
    fun decompile(compiledJar: Path, sourcesDestination: Path, linemapDestination: Path, metaData: DecompilationMetadata) {
        if (!OperatingSystem.is64Bit()) {
            throw UnsupportedOperationException("FernFlower decompiler requires a 64bit JVM to run due to the memory requirements")
        }
        project.logging.captureStandardOutput(LogLevel.LIFECYCLE)
        val options = mapOf(
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to "1",
            IFernflowerPreferences.BYTECODE_SOURCE_MAPPING to "1",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "1",
            IFernflowerPreferences.LOG_LEVEL to "trace",
            IFernflowerPreferences.THREADS to metaData.numberOfThreads
        )
        val args: MutableList<String> = ArrayList()
        options.forEach { (k: String?, v: Any?) -> args.add(MessageFormat.format("-{0}={1}", k, v)) }
        args.add(compiledJar.toAbsolutePath().toString())
        args.add("-o=" + sourcesDestination.toAbsolutePath().toString())
        args.add("-l=" + linemapDestination.toAbsolutePath().toString())
        args.add("-m=" + metaData.javaDocs.toAbsolutePath().toString())

        for (library in metaData.libraries) {
            args.add("-e=" + library.toAbsolutePath().toString())
        }
        val factory = project.serviceOf<ProgressLoggerFactory>()
        val progressGroup = factory.newOperation(javaClass).setDescription("Decompile")
        val loggerFactory = Supplier {
            val pl = factory.newOperation(javaClass, progressGroup)
            pl.description = "decompile worker"
            pl.started()
            pl
        }
        val freeLoggers = Stack<ProgressLogger>()
        val inUseLoggers: MutableMap<String, ProgressLogger?> = HashMap()
        progressGroup.started()

        val logFile = project.file("${project.buildDir}/forgedflower.log")
        val result = logFile.outputStream().buffered().use { logOut ->
            project.javaexec {
                classpath(config)
                main = "io.papermc.yarn.decompiler.MainKt"
                jvmArgs("-Xms200m", "-Xmx3G")
                args(args)
                errorOutput = logOut
                standardOutput = ConsumingOutputStream { line: String ->
                    if (line.startsWith("Listening for transport") || !line.contains("::")) {
                        return@ConsumingOutputStream
                    }
                    val sepIdx = line.indexOf("::")
                    val id = line.substring(0, sepIdx).trim { it <= ' ' }
                    val data = line.substring(sepIdx + 2).trim { it <= ' ' }
                    var logger = inUseLoggers[id]
                    val segs = data.split(" ".toRegex()).toTypedArray()
                    if (segs[0] == "waiting") {
                        if (logger != null) {
                            logger.progress("Idle..")
                            inUseLoggers.remove(id)
                            freeLoggers.push(logger)
                        }
                    } else {
                        if (logger == null) {
                            logger = if (!freeLoggers.isEmpty()) {
                                freeLoggers.pop()
                            } else {
                                loggerFactory.get()
                            }
                            inUseLoggers[id] = logger
                        }
                        logger!!.progress(data)
                    }
                }
            }
        }
        inUseLoggers.values.forEach(Consumer { obj: ProgressLogger? -> obj!!.completed() })
        freeLoggers.forEach(Consumer { obj: ProgressLogger -> obj.completed() })
        progressGroup.completed()
        result.rethrowFailure()
        result.assertNormalExitValue()
    }
}
