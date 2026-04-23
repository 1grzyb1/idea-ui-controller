package com.wkeska.ideauicontroller

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.launcher.IdeDownloader
import com.intellij.remoterobot.launcher.IdeLauncher
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.StepLogger
import com.intellij.remoterobot.stepsProcessing.StepWorker
import com.intellij.remoterobot.utils.waitFor
import okhttp3.OkHttpClient
import okhttp3.Request
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

private val STATE_DIR: Path = Path.of(System.getProperty("user.home"), ".idea-ui-controller")
private val STATE_FILE: Path = STATE_DIR.resolve("state.properties")

private data class State(val pid: Long, val port: Int, val sandbox: Path) {
    fun save() {
        Files.createDirectories(STATE_DIR)
        val props = Properties().apply {
            setProperty("pid", pid.toString())
            setProperty("port", port.toString())
            setProperty("sandbox", sandbox.absolutePathString())
        }
        STATE_FILE.outputStream().use { props.store(it, "idea-ui-controller state") }
    }

    companion object {
        fun load(): State? {
            if (!STATE_FILE.exists()) return null
            val props = Properties().also { p -> STATE_FILE.inputStream().use { p.load(it) } }
            return State(
                pid = props.getProperty("pid").toLong(),
                port = props.getProperty("port").toInt(),
                sandbox = Path.of(props.getProperty("sandbox")),
            )
        }

        fun clear() {
            STATE_FILE.deleteIfExists()
        }
    }
}

private fun State.isAlive(): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

private fun robot(state: State): RemoteRobot = RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())

private fun requireRunningState(): State {
    val s = State.load() ?: error("No IDE registered. Run `runIde` first.")
    check(s.isAlive()) { "Registered IDE (pid=${s.pid}) is not alive. Stale state at $STATE_FILE." }
    return s
}

@Command(
    name = "idea-ui-controller",
    mixinStandardHelpOptions = true,
    version = ["0.1.0"],
    subcommands = [
        RunIdeCommand::class,
        StopIdeCommand::class,
        GetStateCommand::class,
        ClickButtonCommand::class,
        ProbeCommand::class,
    ],
)
class App

@Command(name = "probe", description = ["Run one callJs('true') against the registered IDE and print the result or full exception."])
class ProbeCommand : Callable<Int> {
    @Option(names = ["--port"], description = ["robot-server port (default: read from state or 8082)"])
    var port: Int? = null

    override fun call(): Int {
        val p = port ?: State.load()?.port ?: 8082
        val r = RemoteRobot("http://127.0.0.1:$p", OkHttpClient())
        try {
            val v = r.callJs<Boolean>("true")
            println("OK: $v")
            return 0
        } catch (t: Throwable) {
            System.err.println("FAIL: ${t::class.qualifiedName}: ${t.message}")
            t.printStackTrace(System.err)
            return 1
        }
    }
}

@Command(name = "runIde", description = ["Launch an IDE with the robot-server plugin and register it as the active daemon."])
class RunIdeCommand : Callable<Int> {
    @Parameters(index = "0", description = ["Path to the IDE (e.g. /Applications/IntelliJ IDEA.app)"])
    lateinit var idePath: Path

    @Option(names = ["--port"], description = ["robot-server port (default: 8082)"])
    var port: Int = 8082

    @Option(names = ["--timeout-seconds"], description = ["Max seconds to wait for robot-server (default: 180)"])
    var timeoutSeconds: Long = 180

    override fun call(): Int {
        require(Files.isDirectory(idePath)) { "IDE path is not a directory: $idePath" }
        State.load()?.takeIf { it.isAlive() }?.let {
            System.err.println("An IDE is already registered (pid=${it.pid}, port=${it.port}). Run `stopIde` first.")
            return 1
        }
        State.clear()

        StepWorker.registerProcessor(StepLogger())
        val http = OkHttpClient()
        val sandbox = Files.createTempDirectory("idea-ui-controller-sandbox")
        println("Sandbox: ${sandbox.absolutePathString()}")

        val robotPlugin = IdeDownloader(http).downloadRobotPlugin(sandbox)
        println("Robot plugin: $robotPlugin")

        val process = IdeLauncher.launchIde(
            pathToIde = idePath.toAbsolutePath(),
            additionalProperties = mapOf("robot-server.port" to port),
            additionalVmOptions = emptyList(),
            requiredPluginsArchives = listOf(robotPlugin),
            ideSandboxDir = sandbox,
        )
        println("IDE pid=${process.pid()}. Waiting up to ${timeoutSeconds}s for robot-server on :$port ...")

        val remote = RemoteRobot("http://127.0.0.1:$port", http)
        waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(3)) {
            runCatching { remote.callJs<Boolean>("true") }.getOrDefault(false)
        }

        State(process.pid(), port, sandbox).save()
        println("Robot-server is UP. State written to $STATE_FILE.")
        return 0
    }
}

@Command(name = "stopIde", description = ["Stop the registered IDE daemon and clean up its sandbox."])
class StopIdeCommand : Callable<Int> {
    override fun call(): Int {
        val state = State.load()
        if (state == null) {
            println("No state file.")
            return 0
        }
        ProcessHandle.of(state.pid).ifPresent { it.destroy() }
        runCatching { state.sandbox.toFile().deleteRecursively() }
        State.clear()
        println("Stopped pid=${state.pid}.")
        return 0
    }
}

@Command(name = "getState", description = ["Print the UI component tree of the registered IDE."])
class GetStateCommand : Callable<Int> {
    override fun call(): Int {
        val state = requireRunningState()
        val body = OkHttpClient().newCall(
            Request.Builder().url("http://127.0.0.1:${state.port}/").build()
        ).execute().use { it.body?.string() ?: "" }
        print(body)
        return 0
    }
}

@Command(name = "clickButton", description = ["Find a button in the active IDE by xpath or text and click it."])
class ClickButtonCommand : Callable<Int> {
    @Option(names = ["--xpath"], description = ["Raw XPath locator"])
    var xpath: String? = null

    @Option(names = ["--text"], description = ["Match by visible text or accessible name"])
    var text: String? = null

    @Option(names = ["--timeout-seconds"], description = ["How long to wait for the component (default: 10)"])
    var timeoutSeconds: Long = 10

    override fun call(): Int {
        val xp = xpath
            ?: text?.let { t -> "//div[@text='$t' or @accessiblename='$t' or @visible_text='$t']" }
            ?: error("Provide --xpath or --text")
        val state = requireRunningState()
        val r = robot(state)
        val matches = r.findAll(ComponentFixture::class.java, byXpath(xp))
        if (matches.isEmpty()) {
            waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(1)) {
                r.findAll(ComponentFixture::class.java, byXpath(xp)).isNotEmpty()
            }
        }
        val all = r.findAll(ComponentFixture::class.java, byXpath(xp))
        if (all.size > 1) System.err.println("Warning: ${all.size} matches for $xp — clicking the first.")
        all.first().click()
        println("Clicked: $xp")
        return 0
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(App()).execute(*args))
}
