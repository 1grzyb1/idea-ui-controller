package com.wkeska.ideauicontroller

import com.intellij.remoterobot.fixtures.ComponentFixture
import picocli.CommandLine
import picocli.CommandLine.Command
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

// Every command is a new JVM. The stub passes `-XX:AOTCache=<binary>.aot` (JEP 483), which cuts a command from ~0.27 s
// to ~0.16 s; the JVM silently ignores a missing or outdated cache. The cache is trained on a real IDE, so the first
// command that reaches one trains it in the background.

private val TRAINING_ENV = "IUC_AOT_TRAINING"

/** Starts training the class cache when it's missing or older than the binary; returns at once. */
internal fun trainAotCacheInBackground(state: State) {
    if (System.getenv(TRAINING_ENV) != null) return
    val binary = System.getProperty("iuc.binary")?.let(Path::of)?.takeIf { it.exists() } ?: return
    val cache = binary.resolveSibling("${binary.fileName}.aot")
    if (cache.exists() && cache.getLastModifiedTime() >= binary.getLastModifiedTime()) return
    val lock = binary.resolveSibling("${binary.fileName}.aot.training")
    // Left behind by a killed training
    if (lock.exists() && lock.getLastModifiedTime().toInstant() < Instant.now().minus(Duration.ofMinutes(10))) lock.deleteIfExists()
    try {
        Files.createFile(lock)
    } catch (_: FileAlreadyExistsException) {
        return
    }
    runCatching {
        val javaBin = ProcessHandle.current().info().command().get()
        val tmp = cache.resolveSibling("${cache.fileName}.${ProcessHandle.current().pid()}.tmp")
        // Same classpath and module options as the stub, or the JVM rejects the cache
        val script = "\"$1\" -XX:AOTCacheOutput=\"$2\" -Xlog:aot=off -Xlog:cds=off " +
            "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED " +
            "-Diuc.binary=\"$3\" -jar \"$3\" --instance \"$4\" aotTraining && mv \"$2\" \"$5\"; rm -f \"$2\" \"$2.config\" \"$6\""
        ProcessBuilder("/bin/sh", "-c", script, "sh", javaBin, tmp.toString(), binary.toString(), state.name, cache.toString(), lock.toString())
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { it.environment()[TRAINING_ENV] = "1" }
            .start()
    }.onFailure { lock.deleteIfExists() }
}

@Command(
    name = "aotTraining",
    hidden = true,
    description = ["The workload the class cache is trained on: a few read-only RPCs and argument parsing."],
)
class AotTrainingCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        r.callJs<Boolean>("true")
        r.callJs<String>(stateSummaryJs(allWindows = false, maxRows = 3), true)
        r.findAll(ComponentFixture::class.java, xp("//div[@class='IdeFrameImpl']"))
        for (args in listOf(
            arrayOf("sendKey", "--key", "ENTER", "--state"),
            arrayOf("typeText", "-t", "x"),
            arrayOf("sendKeyCombo", "--key", "A", "--modifier", "CTRL"),
            arrayOf("editorState"),
            arrayOf("runJs", "-s", "true"),
        )) CommandLine(App()).parseArgs(*args)
        return 0
    }
}
