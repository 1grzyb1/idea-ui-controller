package com.wkeska.ideauicontroller

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.launcher.IdeDownloader
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

/** [container] is the Docker container the IDE runs in, `null` when it runs directly on the host. */
internal data class State(val pid: Long, val port: Int, val sandbox: Path, val container: String? = null) {
    fun save() {
        Files.createDirectories(STATE_DIR)
        val props = Properties().apply {
            setProperty("pid", pid.toString())
            setProperty("port", port.toString())
            setProperty("sandbox", sandbox.absolutePathString())
            container?.let { setProperty("container", it) }
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
                container = props.getProperty("container"),
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

// OkHttp's default 10s read timeout is shorter than many legitimate scripts (typing, waiting for a dialog). When the
// client gives up the script keeps running in the IDE and the next command queues behind it, so wait long enough.
private val READ_TIMEOUT_SECONDS: Long = System.getenv("IUC_READ_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120

internal fun robot(state: State): RemoteRobot = RemoteRobot(
    "http://127.0.0.1:${state.port}",
    OkHttpClient.Builder().readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build(),
)

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
        GetStateSummaryCommand::class,
        ScreenshotCommand::class,
        ParseScreenshotCommand::class,
        ClickAtCommand::class,
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
        SendKeyComboCommand::class,
        TypeTextCommand::class,
        FocusWindowCommand::class,
        TreeSelectCommand::class,
        LinkClickCommand::class,
        ClipboardCommand::class,
        FocusedComponentCommand::class,
        TraceActionsCommand::class,
        JbaLoginCommand::class,
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

// Isolated sandbox per run, no shared state. Blocks until the IDE is reachable, then returns.
@Command(name = "runIde", description = ["Launch an IDE with the robot-server plugin and register it as the active daemon."])
class RunIdeCommand : Callable<Int> {
    @Parameters(
        index = "0",
        description = [
            "Path to the IDE (e.g. /Applications/IntelliJ IDEA.app). With --docker also a Linux IDE directory,",
            "or a build to download: IU-263.7307.44, or IU-eap for the newest EAP (an expired EAP build won't start).",
        ],
    )
    lateinit var ide: String

    @Option(
        names = ["--docker"],
        description = [
            "Run the Linux build of the IDE in a Docker container on a virtual display (Xvfb), so keys and clicks don't touch the host.",
            "A macOS .app is swapped for the Linux build with the same build number, downloaded once into ~/.idea-ui-controller.",
        ],
    )
    var docker: Boolean = false

    @Option(names = ["--mount"], description = ["With --docker: host directory to mount into the container at the same path (e.g. a test project). May be repeated."])
    var mounts: List<Path> = emptyList()

    @Option(names = ["--port"], description = ["robot-server port (default: 8082)"])
    var port: Int = 8082

    @Option(names = ["--timeout-seconds"], description = ["Max seconds to wait for robot-server (default: 180)"])
    var timeoutSeconds: Long = 180

    @Option(names = ["--plugin"], description = ["Extra plugin archive (zip) or unpacked plugin directory to install into the sandbox. May be repeated."])
    var extraPlugins: List<Path> = emptyList()

    override fun call(): Int {
        val idePath = Path.of(ide)
        val buildSpec = !Files.exists(idePath) && DockerIdeLauncher.BUILD_SPEC.matches(ide)
        require(!buildSpec || docker) { "A build number ($ide) works only with --docker" }
        require(buildSpec || Files.isDirectory(idePath)) { "IDE path is not a directory: $idePath" }
        State.load()?.takeIf { it.isAlive() }?.let {
            System.err.println("An IDE is already registered (pid=${it.pid}, port=${it.port}). Run `stopIde` first.")
            return 1
        }
        State.clear()

        StepWorker.registerProcessor(StepLogger())
        val http = OkHttpClient()

        println("[1/4] Creating sandbox ...")
        val sandbox = if (docker) {
            Files.createDirectories(DockerIdeLauncher.SANDBOXES)
            Files.createTempDirectory(DockerIdeLauncher.SANDBOXES, "sandbox")
        } else {
            Files.createTempDirectory("idea-ui-controller-sandbox")
        }
        println("      ${sandbox.absolutePathString()}")

        println("[2/4] Downloading robot-server plugin (cached after first run) ...")
        val robotPlugin = IdeDownloader(http).downloadRobotPlugin(sandbox)
        println("      $robotPlugin")

        val pluginArchives: MutableList<Path> = mutableListOf(robotPlugin)
        for (extra in extraPlugins) {
            require(extra.exists()) { "Plugin path does not exist: $extra" }
            if (Files.isDirectory(extra)) {
                val zipped = sandbox.resolve("${extra.fileName}.zip")
                zipPluginDirectory(extra, zipped)
                println("      Packed plugin dir $extra -> $zipped")
                pluginArchives.add(zipped)
            } else {
                println("      Extra plugin archive: $extra")
                pluginArchives.add(extra.toAbsolutePath())
            }
        }

        val process = if (docker) {
            println("[3/4] Launching IDE in Docker container '${DockerIdeLauncher.CONTAINER_NAME}' ...")
            val linuxIde = if (buildSpec) DockerIdeLauncher.resolveBuildSpec(ide, http)
            else DockerIdeLauncher.resolveLinuxIde(idePath.toAbsolutePath(), http)
            println("      Linux IDE: $linuxIde")
            DockerIdeLauncher.launch(
                ide = linuxIde,
                sandbox = sandbox,
                plugins = pluginArchives,
                properties = mapOf("robot-server.port" to port),
                port = port,
                mounts = mounts,
            )
        } else {
            println("[3/4] Launching IDE process ...")
            DetachedIdeLauncher.launch(
                ide = idePath.toAbsolutePath(),
                sandbox = sandbox,
                plugins = pluginArchives,
                properties = mapOf("robot-server.port" to port),
            )
        }
        println("      pid=${process.pid()}")
        println("      IDE output: ${DetachedIdeLauncher.outputLogOf(sandbox)}")

        println("[4/4] Waiting up to ${timeoutSeconds}s for robot-server on :$port ...")
        val remote = RemoteRobot("http://127.0.0.1:$port", http)
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + timeoutSeconds * 1_000
        var lastLogMs = startMs
        var tcpReportedOpen = false
        while (true) {
            check(process.isAlive) {
                "IDE process (pid=${process.pid()}) exited unexpectedly while waiting for robot-server on :$port" +
                    expiredHint(sandbox)
            }
            val tcpOpen = try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200); true }
            } catch (_: Throwable) { false }
            if (tcpOpen && !tcpReportedOpen) {
                val elapsed = (System.currentTimeMillis() - startMs) / 1_000
                println("      [${elapsed}s] TCP :$port open — waiting for robot-server to accept requests ...")
                tcpReportedOpen = true
            }
            if (tcpOpen && runCatching { remote.callJs<Boolean>("true") }.getOrDefault(false)) break
            val nowMs = System.currentTimeMillis()
            if (nowMs >= deadlineMs) error("Timed out after ${timeoutSeconds}s waiting for robot-server on :$port")
            if (nowMs - lastLogMs >= 10_000) {
                val elapsed = (nowMs - startMs) / 1_000
                val status = if (tcpReportedOpen) "TCP open, robot-server not responding yet" else "TCP :$port not open yet"
                println("      [${elapsed}s] $status ...")
                lastLogMs = nowMs
            }
            Thread.sleep(200)
        }

        val elapsed = (System.currentTimeMillis() - startMs) / 1_000
        State(process.pid(), port, sandbox, DockerIdeLauncher.CONTAINER_NAME.takeIf { docker }).save()
        println("      [${elapsed}s] Robot-server is UP.")
        println("State written to $STATE_FILE.")
        return 0
    }
}

// An expired EAP build shows "EAP Build Expired" and exits
private fun expiredHint(sandbox: Path): String {
    val logs = Files.walk(sandbox, 3).use { s -> s.filter { it.fileName.toString().endsWith(".log") }.toList() }
    val pattern = Regex("(?i)(eap|build)[^\\n]{0,40}expired")
    val expired = logs.any { log -> runCatching { pattern.containsMatchIn(Files.readString(log)) }.getOrDefault(false) }
    return if (!expired) "" else "\nThe logs mention an expired build: an EAP build stops working ~30 days after its release. " +
        "Use the newest EAP: `runIde --docker IU-eap`."
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
        if (state.container != null) {
            runCatching { DockerIdeLauncher.remove() }
        } else runCatching {
            // Only for a host IDE: with Docker the port belongs to Docker Desktop's port forwarder
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

private val FOCUS_SUMMARY_JS = """
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
""".trimIndent()

private fun fetchFocusSummary(r: RemoteRobot): String =
    runCatching { r.callJs<String>(FOCUS_SUMMARY_JS, true) }
        .getOrElse { "Current window: <unknown>\nOther windows: []\n(focus probe failed: ${it.message?.take(160)})\n" }

private fun fetchComponentTree(port: Int): String =
    OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port/").build())
        .execute().use { it.body?.string() ?: "" }

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
        print(fetchFocusSummary(RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())))
        if (noTree) return 0
        println("--- component tree ---")
        print(fetchComponentTree(state.port))
        return 0
    }
}

// Converts the raw component tree (~10 000 lines of XML) into a compact, structured element
// list (~15 lines) with ready-to-use xpaths. Keeps agent context consumption minimal and
// enables tight observe-act loops without raw tree parsing.
@Command(
    name = "getStateSummary",
    description = [
        "Print a focus summary followed by a compact list of interactive UI elements (buttons, fields, checkboxes, menus, trees, etc.).",
        "Use this as the default post-action observation. Fall back to getState when you need the raw tree for unknown layouts.",
    ],
)
class GetStateSummaryCommand : Callable<Int> {

    companion object {
        private val ALWAYS_SHOW = setOf(
            "JButton", "JBOptionButton", "MainButton",
            "ActionButton",
            "ActionMenuItem",
            "JCheckBox", "JBCheckBox",
            "JRadioButton",
            "JTextField", "JBTextField", "JTextArea", "JBTextArea",
            "EditorTextField", "BorderlessTextField", "ExtendableTextField",
            "JComboBox",
            "JList", "JBList",
            "JTree",
            "MyDialog",
            "HeavyWeightWindow",
        )
        private val SHOW_IF_TEXT = setOf(
            "ActionMenu",
            "ActionLink",
            "JLabel", "JBLabel",
            "SimpleColoredComponent",
        )
        private val CHECKBOX_CLASSES = setOf("JCheckBox", "JBCheckBox", "JRadioButton")
        private val ATTR = Regex("""(\w[\w-]*)="([^"]*)"""")
    }

    override fun call(): Int {
        val state = requireRunningState()
        print(fetchFocusSummary(RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())))

        val body = fetchComponentTree(state.port)
        println("--- interactive elements ---")
        var count = 0
        for (chunk in body.split(Regex("<div\\b")).drop(1)) {
            val tag = chunk.substringBefore(">")
            val attrs = ATTR.findAll(tag).associate { it.groupValues[1] to decodeHtmlEntities(it.groupValues[2]) }
            val cls = attrs["class"] ?: continue
            val inAlways = cls in ALWAYS_SHOW
            val inIfText = cls in SHOW_IF_TEXT
            if (!inAlways && !inIfText) continue

            val text = attrs["text"]?.takeIf { it.isNotBlank() }
            val accessibleName = attrs["accessiblename"]?.takeIf { it.isNotBlank() }
            val visibleText = attrs["visible_text"]?.takeIf { it.isNotBlank() }
            val label = accessibleName ?: text ?: visibleText ?: ""

            if (inIfText && label.isBlank()) continue

            val enabled = attrs["enabled"] != "false"
            val checked = attrs["checked"]
            val hasFocus = attrs["hasfocus"] == "true"

            fun String.q() = replace("'", "\\'")
            val xpath = when {
                accessibleName != null -> "//div[@accessiblename='${accessibleName.q()}' and @class='$cls']"
                text != null -> "//div[@text='${text.q()}' and @class='$cls']"
                else -> "//div[@class='$cls']"
            }

            val flags = buildList {
                if (!enabled) add("disabled")
                if (cls in CHECKBOX_CLASSES) add(if (checked == "true") "checked" else "unchecked")
                if (hasFocus) add("focused")
            }.joinToString(", ")

            val displayLabel = if (label.isNotBlank()) "\"${label.take(48)}\"" else "\"\""
            val flagStr = if (flags.isNotBlank()) "  [$flags]" else ""
            println("%-28s %-50s  %s%s".format("[$cls]", displayLabel, xpath, flagStr))
            count++
        }
        if (count == 0) println("(no interactive elements found)")
        return 0
    }
}

@Command(
    name = "screenshot",
    description = [
        "Capture the IDE using the robot-server's built-in screenshot and save it to a PNG. Prints the output path.",
        "Last-resort fallback — use getStateSummary for normal observation.",
        "Only reach for this when a visual element cannot be found through the component tree.",
    ],
)
class ScreenshotCommand : Callable<Int> {
    @Option(names = ["--output", "-o"], description = ["Output path for the PNG (default: auto temp file)."])
    var output: Path? = null

    override fun call(): Int {
        val outPath = output ?: Files.createTempFile("idea-ui-controller-screenshot-", ".png")
        val img = connect().getScreenshot()
        javax.imageio.ImageIO.write(img, "PNG", outPath.toFile())
        println(outPath.absolutePathString())
        return 0
    }
}

// Extends agent-readable context beyond the accessibility tree to every pixel on screen.
// Elements with no accessible name — toolbar icons, canvas widgets, visual-only indicators
// — become agent-operable through YOLO detection + Florence-2 captioning, with clickAt
// acting on the returned coordinates.
@Command(
    name = "parseScreenshot",
    description = [
        "Take a screenshot of the IDE and run OmniParser v2 locally (MPS on Apple Silicon, or CPU).",
        "Outputs a JSON array of detected UI elements: [{id, type, label, x1, y1, x2, y2, cx, cy, w, h}].",
        "Coordinates are screen-absolute pixels. Pass cx/cy directly to clickAt.",
        "Models (~39 MB total) are downloaded on first run to ~/.local/lib/idea-ui-controller/models/.",
        "Requires scripts/install.sh to have been run (sets up the Python venv + dependencies).",
    ],
)
class ParseScreenshotCommand : Callable<Int> {
    @Option(names = ["--output", "-o"], description = ["Save the screenshot PNG to this path (default: temp file deleted after parse)."])
    var output: Path? = null

    @Option(names = ["--threshold"], description = ["YOLO confidence threshold (default: 0.05)."])
    var threshold: Double = 0.05

    @Option(names = ["--device"], description = ["Inference device: auto|mps|cpu|cuda (default: auto)."])
    var device: String = "auto"

    @Option(names = ["--debug"], description = ["Print detection details to stderr."])
    var debug: Boolean = false

    override fun call(): Int {
        val helperPath = Path.of(System.getProperty("user.home"), ".local/lib/idea-ui-controller/omniparse.py")
        if (!helperPath.exists()) {
            System.err.println("error: omniparse.py not found at $helperPath. Re-run scripts/install.sh to install it.")
            return 1
        }

        val outPath = output ?: Files.createTempFile("idea-ui-controller-screenshot-", ".png")
        val deleteAfter = output == null

        try {
            val img = connect().getScreenshot()
            javax.imageio.ImageIO.write(img, "PNG", outPath.toFile())

            val venvPython = helperPath.parent.resolve("venv/bin/python3")
            val python = if (venvPython.exists()) venvPython.absolutePathString() else "python3"

            val cmd = buildList {
                addAll(listOf(python, helperPath.absolutePathString()))
                add(outPath.absolutePathString())
                addAll(listOf("--threshold", threshold.toString()))
                addAll(listOf("--device", device))
                if (debug) add("--debug")
            }

            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
            val errThread = Thread { proc.errorStream.copyTo(System.err) }.also { it.isDaemon = true; it.start() }
            val json = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            errThread.join(2_000)

            if (exitCode != 0) {
                System.err.println("error: omniparse.py exited with code $exitCode")
                return 1
            }
            print(json)
            return 0
        } finally {
            if (deleteAfter) outPath.toFile().delete()
        }
    }
}

@Command(
    name = "clickAt",
    description = [
        "Click at an absolute pixel coordinate on the screen via the AWT robot.",
        "Use cx/cy values from parseScreenshot output to interact with elements identified by OmniParser.",
    ],
)
class ClickAtCommand : Callable<Int> {
    @Parameters(index = "0", description = ["X coordinate in pixels (screen-absolute)."])
    var x: Int = 0

    @Parameters(index = "1", description = ["Y coordinate in pixels (screen-absolute)."])
    var y: Int = 0

    override fun call(): Int {
        connect().runJs(
            """
            importClass(java.awt.Point);
            importClass(org.assertj.swing.core.MouseButton);
            var p = new Point($x, $y);
            robot.moveMouse(p);
            robot.pressMouse(MouseButton.LEFT_BUTTON);
            robot.releaseMouse(MouseButton.LEFT_BUTTON);
            """.trimIndent(),
            false,
        )
        println("Clicked at ($x, $y)")
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
        val xpExpr = xpath
            ?: text?.let { t -> "//div[@text='$t' or @accessiblename='$t' or @visible_text='$t']" }
            ?: error("Provide --xpath or --text")
        val locator = xp(xpExpr)
        val state = requireRunningState()
        val r = robot(state)
        val matches = r.findAll(ComponentFixture::class.java, locator)
        if (matches.isEmpty()) {
            waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(1)) {
                r.findAll(ComponentFixture::class.java, locator).isNotEmpty()
            }
        }
        val all = r.findAll(ComponentFixture::class.java, locator)
        if (all.size > 1) System.err.println("Warning: ${all.size} matches for $xpExpr — clicking the first.")
        all.first().click()
        println("Clicked: $xpExpr")
        return 0
    }
}

@Command(
    name = "focusWindow",
    description = ["Bring a window to the front and give it focus. Matches by title substring (case-insensitive). With no filter, focuses the first visible window."],
)
class FocusWindowCommand : Callable<Int> {
    @Option(names = ["--title"], description = ["Case-insensitive title substring to match"])
    var title: String? = null

    override fun call(): Int {
        val filter = title?.replace("'", "\\'") ?: ""
        val r = connect()
        val result = r.callJs<String>(
            """
            importClass(java.awt.Window);
            importClass(java.awt.Frame);
            importClass(java.awt.Dialog);
            function describe(w) {
                var t = (w instanceof Frame || w instanceof Dialog) ? String(w.getTitle() || "") : "";
                return (t.length > 0 ? "'" + t + "' " : "") + "(" + w.getClass().getSimpleName() + ")";
            }
            var filter = '$filter';
            var all = Window.getWindows();
            var target = null;
            for (var i = 0; i < all.length; i++) {
                var w = all[i];
                if (!w.isShowing()) continue;
                if (filter.length > 0) {
                    var t = (w instanceof Frame || w instanceof Dialog) ? String(w.getTitle() || "") : "";
                    if (t.toLowerCase().indexOf(filter.toLowerCase()) < 0) continue;
                }
                target = w;
                break;
            }
            if (target === null) "NO_MATCH";
            else {
                if (target instanceof Frame) target.setState(java.awt.Frame.NORMAL);
                target.toFront();
                target.requestFocus();
                "Focused: " + describe(target);
            }
            """.trimIndent(),
            true,
        )
        if (result == "NO_MATCH") {
            System.err.println("No visible window matched${if (filter.isNotEmpty()) " title '$filter'" else ""}.")
            return 1
        }
        // On macOS, Java's toFront/requestFocus can't steal OS-level focus from another app.
        // Use osascript to activate the IntelliJ process at the OS level.
        val state = requireRunningState()
        runCatching {
            ProcessBuilder(
                "osascript", "-e",
                "tell application \"System Events\" to set frontmost of first application process whose unix id is ${state.pid} to true",
            ).start().waitFor()
        }
        println(result)
        return 0
    }
}

fun main(args: Array<String>) {
    val cli = CommandLine(App())
    // A one-line error instead of a 60-line client stack trace; IUC_DEBUG=1 prints the trace
    cli.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { e, _, _ ->
        if (System.getenv("IUC_DEBUG") == "1") e.printStackTrace()
        else {
            val ideSide = e as? com.intellij.remoterobot.client.IdeaSideException
            val root = generateSequence(e as Throwable) { it.cause }.last()
            val message = ideSide?.message ?: e.message ?: root.message
            System.err.println("error: ${e::class.simpleName}: $message")
            if (root !== e && root.message != null && root.message != message) System.err.println("cause: ${root::class.simpleName}: ${root.message}")
            if (e is java.net.SocketTimeoutException || root is java.net.SocketTimeoutException) {
                System.err.println("The script may still be running in the IDE; later commands wait for it (one request at a time).")
            }
        }
        1
    }
    exitProcess(cli.execute(*args))
}
