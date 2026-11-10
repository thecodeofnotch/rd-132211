import java.util.concurrent.Callable

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.nikohalfkino.strata"
version = (findProperty("appVersion") as String?) ?: "0.0.1"

repositories {
    mavenCentral()
}

val natives: Configuration by configurations.creating
natives.isTransitive = true

dependencies {
    implementation(group = "org.lwjgl.lwjgl", name = "lwjgl", version = "2.9.3")
    implementation(group = "org.lwjgl.lwjgl", name = "lwjgl_util", version = "2.9.3")
    implementation(group = "com.googlecode.soundlibs", name = "jorbis", version = "0.0.17.4")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-windows")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-linux")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-osx")
}

java {
    // JTalk code uses Java 9-11 APIs (String.strip/isBlank, InputStream.transferTo, ...), and jpackage
    // needs JDK 14+ anyway, so 8 was only ever nominal.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val appName = "Strata"
val mainClassName = "com.nikohalfkino.rubydung.RubyDung"

tasks.jar {
    manifest {
        attributes["Main-Class"] = mainClassName
    }
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

tasks.register<Copy>("extractNatives") {
    dependsOn(natives)
    from(natives.map { zipTree(it) })
    into("${project.projectDir}/run/natives")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("run") {
    jvmArgs = listOf(
        "-Dorg.lwjgl.librarypath=${project.projectDir}/run/natives",
        // keep options and worlds in run/ while developing, not in your real user folder
        "-Dstrata.dataDir=${project.projectDir}/run"
    )
    mainClass.set(mainClassName)
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = file("${project.projectDir}/run")
    dependsOn("extractNatives", "stageJTalk")
}

// Dedicated server with a small window (log + command line). Data (worlds, server/ settings) goes to run-server/.
//   gradlew runServer                      (opens the world settings window first)
//   gradlew runServer -PserverArgs="--world MyWorld --port 25566 --max-players 8"
// no natives needed: the server has no window of the game and never touches OpenGL
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Runs a dedicated server with a window showing its log and a command line."
    mainClass.set("com.nikohalfkino.rubydung.net.ServerLauncher")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = file("${project.projectDir}/run-server")
    jvmArgs = listOf(
        "-Xmx2g",
        "-Dstrata.dataDir=${project.projectDir}/run-server"
    )
    val extra = (findProperty("serverArgs") as String?)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
    // no serverArgs: the launcher asks for the world settings in a window. with serverArgs it skips that and starts at once
    args = if (extra.isEmpty()) emptyList() else listOf("--gui") + extra
    doFirst { workingDir.mkdirs() }
}

val osName = System.getProperty("os.name").lowercase()

val targetOs: String = (findProperty("targetOs") as String?) ?: when {
    osName.contains("mac") -> "macos"
    osName.contains("win") -> "windows"
    else -> "linux"
}

val nativesClassifier = when (targetOs) {
    "windows" -> "natives-windows"
    "macos" -> "natives-osx"
    else -> "natives-linux"
}

val iconExtension = when (targetOs) {
    "windows" -> "ico"
    "macos" -> "icns"
    else -> "png"
}

// ── Open JTalk (external native engine, not a Gradle dependency) ─────────────
//
//   jtalk/common/open_jtalk_dic_utf_8/   dictionary (same for every OS)
//   jtalk/common/<anything>.htsvoice     voice model
//   jtalk/windows/open_jtalk.exe (+ DLLs)
//   jtalk/linux/open_jtalk
//   jtalk/macos/open_jtalk
//
// Everything lands in <app>/jtalk/ with the binary (and its DLLs) in jtalk/bin/.
fun jtalkFiles(os: String) = copySpec {
    from("jtalk/common")
    from("jtalk/$os") {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }   // Gradle 8.3+; keeps the binary executable
    }
}

// For `gradlew run`: the game's working dir is run/, so run/jtalk is found by the default relative path.
tasks.register<Sync>("stageJTalk") {
    into("${project.projectDir}/run/jtalk")
    with(jtalkFiles(targetOs))
}

val jpackageVersion: String = run {
    val numeric = Regex("""^\d+(\.\d+){0,2}""").find(project.version.toString())?.value ?: "1.0.0"
    if (targetOs == "macos" && numeric.startsWith("0")) "1" + numeric.substring(1) else numeric
}

val packageInputDir = layout.buildDirectory.dir("jpackage-input")
val portableOutputDir = layout.buildDirectory.dir("package")      // portable app image (zipped by the workflow)
val installerOutputDir = layout.buildDirectory.dir("installer")   // .exe / .deb / .dmg

// Keep this UUID forever. Windows uses it to recognise "the same app" so a new installer upgrades
// the old install instead of installing next to it.
val windowsUpgradeUuid = "6f1c3a52-8d4e-4b7a-9c21-5e0a7b3d9f14"

tasks.register<Sync>("preparePackageInput") {
    dependsOn(tasks.shadowJar)
    into(packageInputDir)

    from(tasks.shadowJar.flatMap { it.archiveFile })

    from(Callable { natives.filter { it.name.contains(nativesClassifier) }.map { zipTree(it) } }) {
        exclude("META-INF/**")
        into("natives")
    }

    into("jtalk") {
        with(jtalkFiles(targetOs))
    }
}

// arguments shared by the portable image and the installer
fun jpackageArgs(type: String, outputDir: File): MutableList<String> {
    val args = mutableListOf(
        "jpackage",
        "--type", type,
        "--name", appName,
        "--app-version", jpackageVersion,
        "--input", packageInputDir.get().asFile.absolutePath,
        "--main-jar", tasks.shadowJar.get().archiveFileName.get(),
        "--main-class", mainClassName,
        "--dest", outputDir.absolutePath,
        "--java-options", "-Dorg.lwjgl.librarypath=\$APPDIR/natives",
        "--java-options", "-Djtalk.home=\$APPDIR/jtalk",
    )

    val iconFile = file("src/main/resources/icon.$iconExtension")
    if (iconFile.exists()) {
        args += listOf("--icon", iconFile.absolutePath)
    }
    return args
}

// Portable: a plain folder with a bundled Java runtime. The portable.txt marker tells the game
// (GameDirectory) to keep options and worlds inside that folder instead of the user's data folder.
tasks.register<Exec>("packagePortable") {
    group = "distribution"
    description = "Packages the game as a portable app image that stores its data next to itself."
    dependsOn("preparePackageInput")

    doFirst {
        val outputDir = portableOutputDir.get().asFile
        outputDir.deleteRecursively()
        commandLine(jpackageArgs("app-image", outputDir))
    }

    doLast {
        File(portableOutputDir.get().asFile, "$appName/portable.txt").writeText(
            "Portable mode: options and worlds are stored in this folder.\n" +
                    "Delete this file to store them in your user folder instead.\n"
        )
    }
}

// Regular: a real installer (Windows .exe, Linux .deb, macOS .dmg). The data goes to the user's
// data folder, which also keeps it safe when the game is updated or uninstalled.
tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Builds a native installer (exe on Windows, deb on Linux, dmg on macOS)."
    dependsOn("preparePackageInput")

    doFirst {
        val outputDir = installerOutputDir.get().asFile
        outputDir.deleteRecursively()

        val type = when (targetOs) {
            "windows" -> "exe"   // needs the WiX Toolset 3.x on PATH
            "macos" -> "dmg"
            else -> "deb"        // needs dpkg-deb and fakeroot
        }

        val args = jpackageArgs(type, outputDir)
        when (targetOs) {
            "windows" -> args += listOf(
                "--win-menu", "--win-shortcut", "--win-dir-chooser",
                "--win-per-user-install",   // no admin prompt, installs to %LOCALAPPDATA%
                "--win-upgrade-uuid", windowsUpgradeUuid,
            )
            "linux" -> args += listOf("--linux-shortcut", "--linux-menu-group", "Game")
        }

        commandLine(args)
    }
}

// ── Dedicated server package (no game window, no natives, no JTalk) ──────────
//
//   gradlew packageServer -PappVersion=1.0.0   ->  build/server-package/Strata-Server  (app image with its own Java)
//
// starts ServerLauncher: a small window for the world settings, then the server with its log window.
// started with arguments it skips the window and works like ServerMain's command line.
val serverMainClass = "com.nikohalfkino.rubydung.net.ServerLauncher"
val serverInputDir = layout.buildDirectory.dir("server-input")
val serverOutputDir = layout.buildDirectory.dir("server-package")

tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("serverJar") {
    group = "distribution"
    archiveFileName.set("strata-server-${project.version}.jar")
    from(sourceSets["main"].output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest { attributes["Main-Class"] = serverMainClass }
}

tasks.register<Sync>("prepareServerInput") {
    dependsOn("serverJar")
    into(serverInputDir)
    from(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("serverJar").flatMap { it.archiveFile })
}

tasks.register<Exec>("packageServer") {
    group = "distribution"
    description = "Packages the dedicated server (with its world settings window) as a portable app image."
    dependsOn("prepareServerInput")

    doFirst {
        val outputDir = serverOutputDir.get().asFile
        outputDir.deleteRecursively()
        commandLine(
            "jpackage",
            "--type", "app-image",
            "--name", "$appName-Server",
            "--app-version", jpackageVersion,
            "--input", serverInputDir.get().asFile.absolutePath,
            "--main-jar", "strata-server-${project.version}.jar",
            "--main-class", serverMainClass,
            "--dest", outputDir.absolutePath,
            // XL / XXL worlds need more: raise it here or start with --max-memory style JAVA_TOOL_OPTIONS
            "--java-options", "-Xmx3g",
        )
    }

    doLast {
        File(serverOutputDir.get().asFile, "$appName-Server/portable.txt").writeText(
            "Portable mode: worlds and server settings are stored in this folder.\n"
        )
    }
}