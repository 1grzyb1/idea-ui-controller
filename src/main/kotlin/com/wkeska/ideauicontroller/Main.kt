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

internal data class State(val pid: Long, val port: Int, val sandbox: Path) {
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

internal fun State.isAlive(): Boolean = try {
    java.net.Socket().use { sock ->
        sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
        true
    }
} catch (_: Throwable) {
    false
}

internal fun robot(state: State): RemoteRobot = RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())

internal fun requireRunningState(): State {
    val s = State.load() ?: error("No IDE registered. Run `runIde` first.")
    check(s.isAlive()) { "Registered IDE (pid=${s.pid}) is not alive. Stale state at $STATE_FILE." }
    return s
}

internal fun connect(): RemoteRobot = robot(requireRunningState())

internal fun zipPluginDirectory(sourceDir: Path, destZip: Path) {
    val top = sourceDir.fileName.toString()
    java.util.zip.ZipOutputStream(Files.newOutputStream(destZip)).use { zos ->
        Files.walk(sourceDir).use { stream ->
            stream.forEach { p ->
                if (p == sourceDir) return@forEach
                val rel = sourceDir.relativize(p).toString().replace('\\', '/')
                val entryName = if (Files.isDirectory(p)) "$top/$rel/" else "$top/$rel"
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                if (!Files.isDirectory(p)) Files.copy(p, zos)
                zos.closeEntry()
            }
        }
    }
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
        RunJsCommand::class,
        CallJsCommand::class,
        WelcomeVerifyCommand::class,
        WelcomeNewProjectCommand::class,
        WelcomeNewSolutionCommand::class,
        WelcomeMoreActionsCommand::class,
        WelcomeImportProjectCommand::class,
        WelcomeHeavyweightPopupCommand::class,
        EditorVerifyCommand::class,
        EditorOpenCommand::class,
        EditorGetTextCommand::class,
        EditorGetSelectedTextCommand::class,
        EditorCaretOffsetCommand::class,
        EditorCaretCountCommand::class,
        EditorIsBlockCursorCommand::class,
        EditorCaretShapeCommand::class,
        EditorInjectTextCommand::class,
        EditorMoveToLineCommand::class,
        IdeVerifyCommand::class,
        IdeProjectNameCommand::class,
        IdeIsSmartCommand::class,
        IdeWaitSmartCommand::class,
        DialogVerifyCommand::class,
        DialogTitleCommand::class,
        SearchEverywhereVerifyCommand::class,
        ActionMenuVerifyCommand::class,
        ActionMenuItemCommand::class,
        ActionLinkClickCommand::class,
        GutterVerifyCommand::class,
        ListSelectCommand::class,
        TextFieldSetCommand::class,
        CheckboxSetCommand::class,
        DialogClickButtonCommand::class,
        CreateScratchFileCommand::class,
        ActionLinkClickByTextCommand::class,
        ProjectCreateFromDialogCommand::class,
        SendKeyCommand::class,
        TypeTextCommand::class,
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

    @Option(names = ["--plugin"], description = ["Extra plugin archive (zip) or unpacked plugin directory to install into the sandbox. May be repeated."])
    var extraPlugins: List<Path> = emptyList()

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

        val pluginArchives: MutableList<Path> = mutableListOf(robotPlugin)
        for (extra in extraPlugins) {
            require(extra.exists()) { "Plugin path does not exist: $extra" }
            if (Files.isDirectory(extra)) {
                val zipped = sandbox.resolve("${extra.fileName}.zip")
                zipPluginDirectory(extra, zipped)
                println("Packed plugin dir $extra -> $zipped")
                pluginArchives.add(zipped)
            } else {
                println("Extra plugin archive: $extra")
                pluginArchives.add(extra.toAbsolutePath())
            }
        }

        val process = IdeLauncher.launchIde(
            pathToIde = idePath.toAbsolutePath(),
            additionalProperties = mapOf("robot-server.port" to port),
            additionalVmOptions = emptyList(),
            requiredPluginsArchives = pluginArchives,
            ideSandboxDir = sandbox,
        )
        println("IDE pid=${process.pid()}. Waiting up to ${timeoutSeconds}s for robot-server on :$port ...")

        val remote = RemoteRobot("http://127.0.0.1:$port", http)
        waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofMillis(200)) {
            val tcpOpen = try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200); true }
            } catch (_: Throwable) { false }
            tcpOpen && runCatching { remote.callJs<Boolean>("true") }.getOrDefault(false)
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
        runCatching {
            RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())
                .runJs("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true);", false)
        }
        runCatching { ProcessHandle.of(state.pid).ifPresent { it.destroy() } }
        runCatching {
            val p = ProcessBuilder("lsof", "-iTCP:${state.port}", "-sTCP:LISTEN", "-t").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.lineSequence().filter { it.isNotBlank() }.forEach { pidStr ->
                pidStr.trim().toLongOrNull()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
            }
        }
        runCatching { state.sandbox.toFile().deleteRecursively() }
        State.clear()
        println("Stopped pid=${state.pid}.")
        return 0
    }
}

@Command(
    name = "getState",
    description = [
        "Print a focus summary (current window + other visible windows) followed by the full UI component tree.",
    ],
)
class GetStateCommand : Callable<Int> {
    @Option(names = ["--no-tree"], description = ["Skip the full XML tree; print only the focus summary."])
    var noTree: Boolean = false

    override fun call(): Int {
        val state = requireRunningState()
        val r = RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())

        val summary = runCatching {
            r.callJs<String>(
                """
                importClass(java.awt.Window);
                importClass(java.awt.Frame);
                importClass(java.awt.Dialog);
                importClass(java.awt.KeyboardFocusManager);
                function describe(w) {
                    if (w === null) return "<none>";
                    var title = "";
                    if (w instanceof Frame || w instanceof Dialog) {
                        var t = w.getTitle();
                        if (t !== null) title = String(t);
                    }
                    var cls = w.getClass().getSimpleName();
                    return (title.length > 0 ? "'" + title + "' " : "") + "(" + cls + ")";
                }
                var kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                var current = kfm.getFocusedWindow();
                if (current === null) current = kfm.getActiveWindow();
                var others = [];
                var all = Window.getWindows();
                for (var i = 0; i < all.length; i++) {
                    var w = all[i];
                    if (w === current) continue;
                    if (!w.isShowing()) continue;
                    others.push(describe(w));
                }
                "Current window: " + describe(current) + "\n" +
                "Other windows: [" + others.join(", ") + "]\n";
                """.trimIndent(),
                true,
            )
        }.getOrElse { "Current window: <unknown>\nOther windows: []\n(focus probe failed: ${it.message?.take(160)})\n" }

        print(summary)
        if (noTree) return 0

        println("--- component tree ---")
        val body = OkHttpClient().newCall(
            Request.Builder().url("http://127.0.0.1:${state.port}/").build(),
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
