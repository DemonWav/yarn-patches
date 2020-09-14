rootProject.name = "yarn-patches"

includeBuild("gradle-tasks")

// This really should be a composite build under `gradle-tasks`, but intellij doesn't know how to handle
// nested composite builds yet, so this is a workaround
includeBuild("gradle-tasks/decompiler")

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS_FULL

val workspaceDir = file("workspace")
if (workspaceDir.exists()) {
    workspaceDir.listFiles()?.forEach { dir ->
        val versionName = dir.name
        include(":$versionName")
        project(":$versionName").projectDir = file(dir)
        dir.listFiles()?.forEach { typeDir ->
            val typeName = typeDir.name
            include(":$versionName:$typeName")
            project(":$versionName:$typeName").let { project ->
                project.projectDir = file(typeDir)
                project.buildFileName = "../../../workspace.gradle.kts"
            }
        }
    }
}
