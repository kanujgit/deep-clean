#!/usr/bin/env kscript

@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202")

import Deep_clean.CommandLineArguments
import org.docopt.Docopt
import java.io.File
import java.nio.file.Paths

typealias CommandLineArguments = Map<String, Any>

val usage = """
This script nukes all build caches from Gradle/Android projects.
Run this in a Gradle/Android project folder.

Usage: deep-clean [options]

Options:
    -d --dry-run  Don't delete anything. Useful for testing. Implies --verbose.
    -n --nuke     ⚠️  THIS IS DANGEROUS SHIT ⚠️  Super-deep clean
                  This includes clearing out global folders, including:
                   * the global Gradle cache
                   * the wrapper-downloaded Gradle distros
                   * the Gradle daemon data (logs, locks, etc.)
                   * the Android build cache
                  Nukes the entire thing from orbit — it's the only way to be sure.
    -v --verbose  Print detailed information about all commands.
"""

val userHome = File(System.getProperty("user.home"))
val gradleHome = locateGradleHome()

assert(userHome.exists(), { "Unable to determine the user home folder, aborting..." })

val parsedArgs: CommandLineArguments = Docopt(usage)
    .withVersion("deep-clean 1.1.0")
    .parse(args.toList())

val nukeItFromOrbit: Boolean = parsedArgs.isFlagSet("--nuke", "-n")
val dryRun: Boolean = parsedArgs.isFlagSet("--dry-run", "-d")
val verbose: Boolean = dryRun || parsedArgs.isFlagSet("--verbose", "-v")

if (dryRun) println("\nℹ️  This is a dry-run.\n")

val wetRun = dryRun.not()
val gradlew = "./gradlew" + if (isOsWindows()) ".bat" else ""

Runtime.getRuntime().apply {
    println("⏳ Executing Gradle clean...")
    execOnWetRun("$gradlew clean -q")
        ?.printOutput(onlyErrors = false)

    println("🔫 Killing Gradle daemons...")
    execOnWetRun("$gradlew --stop")
        ?.printOutput()

    println("🔫 Killing ADB server...")
    execOnWetRun("adb kill-server")
        ?.printIfNoError("Adb server killed.")
    execOnWetRun("killall adb")

    val currentDir = File(Paths.get("").toAbsolutePath().toString())

    println("🔥 Removing every 'build' folder...")
    currentDir.deleteSubfoldersMatching { it.name.toLowerCase() == "build" }

    println("🔥 Removing every '.gradle' folder...")
    currentDir.deleteSubfoldersMatching { it.name.toLowerCase() == ".gradle" }

    if (nukeItFromOrbit) nukeGlobalCaches()

    println("🔫 Killing Kotlin compile daemon...")
    println("\tℹ️  Note: this kills any CLI Java instance running (including this script)")
    execOnWetRun("killall java")
}

fun locateGradleHome() = System.getenv("GRADLE_HOME")?.let { File(it) } ?: File(userHome, ".gradle")

fun CommandLineArguments.isFlagSet(vararg flagAliases: String): Boolean =
    flagAliases.map { this[it] as Boolean? }.first { it != null }!!

fun Runtime.execOnWetRun(command: String) = if (wetRun) exec(command) else null

fun Process.printOutput(onlyErrors: Boolean = true) {
    if (onlyErrors.not()) {
        inputStream.bufferedReader().lines().forEach { println("\t$it") }
    }
    errorStream.bufferedReader().lines().forEach { println("\t$it") }
}

fun Process.printIfNoError(message: String) {
    if (errorStream.bufferedReader().lineSequence().none()) {
        println("\t$message")
    }
}

fun File.deleteSubfoldersMatching(matcher: (file: File) -> Boolean) {
    this.listFiles { file -> file.isDirectory }
        .filter(matcher)
        .onEach { if (verbose) println("\tDeleting directory: ${it.absolutePath}") }
        .forEach { if (wetRun) it.deleteRecursively() }
}

fun Runtime.nukeGlobalCaches() {
    println()
    println("☢️ ☢️ ☢️ ☢️  WARNING: nuke mode activated ☢️ ☢️ ☢️ ☢️ ")
    println()
    println("                     __,-~~/~    `---.")
    println("                   _/_,---(      ,    )")
    println("               __ /        <    /   )  \\___")
    println("- ------===;;;'====------------------===;;;===----- -  -")
    println("                  \\/  ~\"~\"~\"~\"~\"~\\~\"~)~\"/")
    println("                  (_ (   \\  (     >    \\)")
    println("                   \\_( _ <         >_>'")
    println("                      ~ `-i' ::>|--\"")
    println("                          I;|.|.|")
    println("                         <|i::|i|`.")
    println("                        (` ^'\"`-' \")")
    println("------------------------------------------------------------------")
    println("")
    println("This will affect system-wide caches for Gradle and IDEs.")
    println("⚠️  You will lose local version history and other IDE data! ⚠️")
    println()

    println("⏳ Clearing Android Gradle build cache...")
    exec("$gradlew cleanBuildCache")

    println("🔥 Clearing ${Ide.IntelliJIdea} caches...")
    clearIdeCache(Ide.IntelliJIdea)

    println("🔥 Clearing ${Ide.AndroidStudio} caches...")
    clearIdeCache(Ide.AndroidStudio)

    println("🔥 Clearing Gradle global cache directories: build-scan-data, caches, daemon, wrapper...")
    gradleHome.deleteSubfoldersMatching {
        it.name.toLowerCase() == "build-scan-data" ||
            it.name.toLowerCase() == "caches" ||
            it.name.toLowerCase() == "daemon" ||
            it.name.toLowerCase() == "wrapper"
    }
}

fun clearIdeCache(ide: Ide) {
    locateCacheFolderFor(ide)
        .onEach {
            println("\tClearing cache for $ide ${extractVersion(it.parentFile, ide)}...")
            if (verbose) println("\t  Deleting directory: ${it.absolutePath}")
        }
        .forEach { if (wetRun) it.deleteRecursively() }
}

fun extractVersion(it: File, ide: Ide): String {
    val versionName = it.name.substringAfter(ide.folderPrefix)
    return if (versionName.startsWith("Preview")) {
        "${versionName.substring("Preview".length)} Preview"
    } else {
        versionName
    }
}

sealed class Ide(private val name: String, val folderPrefix: String) {
    object IntelliJIdea : Ide("IntelliJ IDEA", "IntelliJIdea")
    object AndroidStudio : Ide("Android Studio", "AndroidStudio")

    override fun toString() = name
}

fun locateCacheFolderFor(ide: Ide): List<File> {
    return when {
        isOsWindows() || isOsLinux() -> {
            userHome.listFiles { file -> file.isDirectory }
                .filter { it.name.startsWith(".${ide.folderPrefix}") }
                .map { File(it, "system") }
        }
        isOsMacOs() -> {
            File(userHome, "Library/Caches")
                .listFiles { file -> file.isDirectory }
                .filter { it.name.startsWith(ide.folderPrefix, ignoreCase = true) }
                .map { File(it, "system") }
        }
        else -> {
            println("\tUnsupported OS, skipping.")
            emptyList()
        }
    }
}

fun isOsWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
fun isOsLinux() = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
fun isOsMacOs() = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
