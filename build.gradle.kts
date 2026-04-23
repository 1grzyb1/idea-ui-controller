plugins {
    kotlin("jvm") version "2.3.21"
    application
    id("com.gradleup.shadow") version "8.3.8"
}

group = "com.wkeska"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("com.wkeska.ideauicontroller.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    )
}

dependencies {
    implementation("com.intellij.remoterobot:ide-launcher:0.11.23")
    implementation("com.intellij.remoterobot:remote-robot:0.11.23")
    implementation("com.intellij.remoterobot:remote-fixtures:0.11.23")

    implementation("info.picocli:picocli:4.7.7")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("idea-ui-controller-fat.jar")
    manifest {
        attributes["Main-Class"] = "com.wkeska.ideauicontroller.MainKt"
    }
    mergeServiceFiles()
}

val stubScript = """#!/bin/sh
exec java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED -jar "${'$'}0" "${'$'}@"
"""

tasks.register("executable") {
    group = "distribution"
    description = "Build a single self-executing file (stub + shadow jar)."
    dependsOn(tasks.shadowJar)
    val outFile = layout.buildDirectory.file("libs/idea-ui-controller")
    outputs.file(outFile)
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val out = outFile.get().asFile
        out.outputStream().use { os ->
            os.write(stubScript.toByteArray())
            jar.inputStream().use { it.copyTo(os) }
        }
        out.setExecutable(true)
        println("Executable: ${out.absolutePath}")
    }
}
