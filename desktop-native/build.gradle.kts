import groovy.json.JsonSlurper
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    val jewelVersion = "0.42.0-263.7307.200"
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:$jewelVersion")
    implementation("org.jetbrains.jewel:jewel-markdown-int-ui-standalone-styling:$jewelVersion")
    implementation("org.jetbrains.jewel:jewel-markdown-extensions-gfm-tables:$jewelVersion")
    runtimeOnly("com.jetbrains.intellij.platform:icons:263.7307.200")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(kotlin("test-junit"))
}

abstract class StudioBuildIdentity : ValueSource<String, StudioBuildIdentity.Parameters> {
    interface Parameters : ValueSourceParameters {
        val script: RegularFileProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine("node.exe", parameters.script.get().asFile.absolutePath)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}

val desktop = layout.projectDirectory.dir("../desktop")
val identityJson = providers.of(StudioBuildIdentity::class) {
    parameters.script.set(desktop.file("scripts/release.cjs"))
}
val installerVersion = identityJson.map {
    val identity = JsonSlurper().parseText(it) as Map<*, *>
    (identity["installerVersion"] as String).substringBefore('-')
}
val buildIdentityDir = layout.buildDirectory.dir("generated/studio")
val generateIdentity by tasks.registering {
    inputs.property("identity", identityJson)
    outputs.dir(buildIdentityDir)
    doLast {
        val output = buildIdentityDir.get().file("studio-build.json").asFile
        output.parentFile.mkdirs()
        output.writeText(identityJson.get())
    }
}

tasks.processResources {
    from(generateIdentity)
    from(desktop.file("assets/logo.png")) { rename { "studio-logo.png" } }
    from(layout.projectDirectory.file("../frontend/public/studio-logo.png")) { rename { "studio-titlebar-logo.png" } }
    // §6.2: reuse the built-in plugins' format readers without a browser or a second copy of each parser.
    for ((plugin, source) in listOf("mesh_preview" to "MeshPreview", "fbx_preview" to "FbxPreview", "vox_preview" to "VoxPreview")) {
        from(layout.projectDirectory.file("../backend/plugins_builtin/$plugin/frontend/$source.js")) {
            into("native-model")
            rename { "$source.mjs" }
        }
    }
}

// §2.7: the same wheel, SPA and provisioned dependencies ship in both desktop builds.
val prepareStudioRuntime by tasks.registering(Exec::class) {
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        desktop.file("scripts/prepare-runtime.ps1").asFile.absolutePath)
}
val appResources = layout.buildDirectory.dir("studio-resources")
val studioMain = "studio.voxarium.desktop.MainKt"
val stageStudioRuntime by tasks.registering(Sync::class) {
    dependsOn(prepareStudioRuntime)
    into(appResources.map { it.dir("windows-x64") })
    from(desktop.dir("build/runtime")) { into("studio-backend") }
    from(desktop.file("scripts/stop-backend.ps1"))
    from(desktop.file("scripts/launch-native-update.ps1"))
}

compose.desktop {
    application {
        mainClass = studioMain
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            packageName = "Voxarium Studio"
            packageVersion = installerVersion.get()
            description = "Local workspace for AI-assisted game development"
            vendor = "Voxarium Studio"
            modules("java.net.http", "java.naming", "jdk.crypto.ec", "jdk.accessibility")
            appResourcesRootDir.set(appResources)
            windows {
                iconFile.set(desktop.file("assets/icon.ico"))
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageStudioRuntime)
}

tasks.withType<AbstractJPackageTask>().configureEach {
    // Compose 1.12.1 reads this directory when building the Windows app image.
    // jpackage --type app-image only requires the JDK tools. NSIS builds the installer.
    if (targetFormat == TargetFormat.AppImage) {
        wixToolsetDir.convention(project.layout.dir(javaHome.map { File(it, "bin") }))
    }
}

// Compose marks javaHome internal. Rebuild the image when switching JDKs or JBR builds.
tasks.withType<AbstractJvmToolOperationTask>().configureEach {
    inputs.file(javaHome.map { File(it, "release") }).withPathSensitivity(PathSensitivity.NONE)
}

tasks.register<JavaExec>("runStudio") {
    group = "application"
    description = "Runs the native client against the checkout's Studio runtime."
    mainClass.set(studioMain)
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = layout.projectDirectory.dir("..").asFile
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
