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
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.system.exitProcess

// OkHttp's default 10s read timeout is shorter than many legitimate scripts (typing, waiting for a dialog). When the
// client gives up the script keeps running in the IDE and the next command queues behind it, so wait long enough.
private val READ_TIMEOUT_SECONDS: Long = System.getenv("IUC_READ_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120

internal fun robot(state: State): RemoteRobot = RemoteRobot(
    "http://127.0.0.1:${state.port}",
    OkHttpClient.Builder().readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build(),
)

internal fun requireRunningState(): State {
    val s = State.load() ?: error(
        SelectedInstance.name?.let { "No IDE registered as '$it'. Run `runIde --instance $it` first." } ?: "No IDE registered. Run `runIde` first.",
    )
    check(s.isAlive()) { "IDE '${s.name}' (pid=${s.pid}) doesn't answer on :${s.port}. Stale state at ${s.file}: `stopIde --instance ${s.name}`." }
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
        IdeCacheCommand::class,
        InstancesCommand::class,
        ViewCommand::class,
        RecordCommand::class,
        EditorStateCommand::class,
        OpenProjectCommand::class,
        OpenFileCommand::class,
        WaitForCommand::class,
        IdeLogCommand::class,
    ],
)
class App {
    @Option(
        names = ["--instance"],
        scope = CommandLine.ScopeType.INHERIT,
        paramLabel = "NAME",
        description = [
            "Which IDE to drive when several run (also IUC_INSTANCE). runIde registers it under NAME;",
            "without it a command uses the only running IDE.",
        ],
    )
    fun instance(name: String) {
        SelectedInstance.name = name
    }
}

@Command(name = "probe", description = ["Run one callJs('true') against the registered IDE and print the result or full exception."])
class ProbeCommand : Callable<Int> {
    @Option(names = ["--port"], description = ["robot-server port (default: the instance's)"])
    var port: Int? = null

    override fun call(): Int {
        val p = port ?: State.load()?.port ?: error("No IDE registered. Pass --port.")
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

    @Option(
        names = ["--split"],
        paramLabel = "PROJECT",
        description = [
            "With --docker: Remote Development split mode. The IDE runs as a backend (serverMode) on PROJECT, and the",
            "JetBrains Client bundled with it connects to it. --plugin goes into both, the robot-server into the client.",
        ],
    )
    var splitProject: Path? = null

    @Option(names = ["--port"], description = ["robot-server port (default: the first free one from 8082)"])
    var requestedPort: Int? = null

    @Option(names = ["--timeout-seconds"], description = ["Max seconds to wait for robot-server (default: 180)"])
    var timeoutSeconds: Long = 180

    @Option(names = ["--plugin"], description = ["Extra plugin archive (zip) or unpacked plugin directory to install into the sandbox. May be repeated."])
    var extraPlugins: List<Path> = emptyList()

    @Option(names = ["--restart"], description = ["Stop the IDE registered under this instance name first (instead of failing when it runs)."])
    var restart: Boolean = false

    @Option(names = ["--no-view"], description = ["With --docker: no live view of the display (noVNC in a browser)."])
    var noView: Boolean = false

    @Option(names = ["--view-control"], description = ["With --docker: the live view also takes mouse and keyboard (default: watch only)."])
    var viewControl: Boolean = false

    override fun call(): Int {
        val idePath = Path.of(ide)
        val buildSpec = !Files.exists(idePath) && DockerIdeLauncher.BUILD_SPEC.matches(ide)
        require(!buildSpec || docker) { "A build number ($ide) works only with --docker" }
        require(splitProject == null || docker) { "--split works only with --docker" }
        require(buildSpec || Files.isDirectory(idePath)) { "IDE path is not a directory: $idePath" }
        val container = { name: String -> "${DockerIdeLauncher.CONTAINER_PREFIX}$name".takeIf { docker } }
        // Reserve the name, the port and the sandbox before anything slow, so parallel runIde calls don't collide
        val reserved = State.locked {
            for (stale in State.all().filter { !it.isLive() }) {
                stale.container?.let { runCatching { DockerIdeLauncher.remove(it) } }
                stale.clear()
            }
            val live = State.all()
            val name = SelectedInstance.name
                ?: listOf("default").plus((2..99).map { "ide$it" }).first { n -> live.none { it.name == n } }
            live.firstOrNull { it.name == name }?.let {
                if (!restart) {
                    System.err.println("IDE '$name' is already running (pid=${it.pid}, port=${it.port}). Pass --restart, or another --instance.")
                    return 1
                }
                stopInstance(it)
            }
            if (!docker) {
                live.firstOrNull { it.container == null && it.name != name }?.let {
                    System.err.println(
                        "IDE '${it.name}' already runs on the host: two host IDEs fight over the real keyboard and mouse. " +
                            "Use --docker for parallel IDEs.",
                    )
                    return 1
                }
            }
            val taken = State.all().flatMap { listOfNotNull(it.port, it.viewPort) }.toSet()
            val port = requestedPort?.also { p -> require(p !in taken) { "Port $p is used by another instance" } }
                ?: (8082..8182).first { it !in taken && isPortFree(it) }
            val viewPort = if (docker && !noView) (6080..6180).first { it !in taken && isPortFree(it) } else null
            val sandbox = if (docker) {
                // Leftovers of IDEs that crashed or weren't stopped; never another instance's
                val used = State.all().map { it.sandbox.toAbsolutePath().normalize() }.toSet()
                DockerIdeLauncher.pruneSandboxes(keep = used)
                Files.createDirectories(DockerIdeLauncher.SANDBOXES)
                Files.createTempDirectory(DockerIdeLauncher.SANDBOXES, "sandbox")
            } else {
                Files.createTempDirectory("idea-ui-controller-sandbox")
            }
            State(name, ProcessHandle.current().pid(), port, sandbox, container(name), viewPort = viewPort).also { it.save() }
        }
        try {
            return launch(reserved, buildSpec, idePath)
        } catch (e: Throwable) {
            reserved.container?.let { runCatching { DockerIdeLauncher.remove(it) } }
            reserved.clear()
            throw e
        }
    }

    private fun launch(reserved: State, buildSpec: Boolean, idePath: Path): Int {
        val (name, _, port, sandbox) = reserved
        println("[1/4] Instance '$name', port $port, sandbox ${sandbox.absolutePathString()}")

        StepWorker.registerProcessor(StepLogger())
        val http = OkHttpClient()

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

        // No "Trust project" dialog when an agent opens a project
        val ideProperties = mapOf("robot-server.port" to port, "idea.trust.all.projects" to true)
        val process = if (docker) {
            val container = reserved.container!!
            val view = reserved.viewPort?.let { DockerIdeLauncher.View(it, viewControl) }
            println("[3/4] Launching IDE in Docker container '$container' ...")
            // Resolved and registered under the cache lock, so no parallel eviction deletes it in between
            val linuxIde = DockerIdeLauncher.withCacheLock {
                val resolved = if (buildSpec) DockerIdeLauncher.resolveBuildSpec(ide, http)
                else DockerIdeLauncher.resolveLinuxIde(idePath.toAbsolutePath(), http)
                reserved.copy(ide = resolved).save()
                resolved
            }
            DockerIdeLauncher.withCacheLock { DockerIdeLauncher.evict(protect = State.all().mapNotNull { it.ide }.toSet()) }
            println("      Linux IDE: $linuxIde")
            val project = splitProject
            if (project != null) {
                println("      Split mode: backend on $project, backend output: ${sandbox.resolve("backend/ide-output.log")}")
                DockerIdeLauncher.launchSplit(
                    ide = linuxIde,
                    sandbox = sandbox,
                    project = project,
                    backendPlugins = pluginArchives - robotPlugin,
                    clientPlugins = pluginArchives,
                    properties = ideProperties,
                    port = port,
                    mounts = mounts,
                    container = container,
                    view = view,
                )
            } else DockerIdeLauncher.launch(
                ide = linuxIde,
                sandbox = sandbox,
                plugins = pluginArchives,
                properties = ideProperties,
                port = port,
                mounts = mounts,
                container = container,
                view = view,
            )
        } else {
            println("[3/4] Launching IDE process ...")
            DetachedIdeLauncher.launch(
                ide = idePath.toAbsolutePath(),
                sandbox = sandbox,
                plugins = pluginArchives,
                properties = ideProperties,
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
        State.load(name)!!.copy(pid = process.pid()).save()
        println("      [${elapsed}s] Robot-server is UP.")
        reserved.viewPort?.let { println("Live view${if (viewControl) "" else " (watch only)"}: ${DockerIdeLauncher.viewUrl(it)}") }
        println("Instance '$name' registered. When several IDEs run, pass --instance $name to every command.")
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

private fun isPortFree(port: Int): Boolean = try {
    java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)); true }
} catch (_: java.io.IOException) {
    false
}

/** Stops the IDE of [state], deletes its sandbox and its state file. */
internal fun stopInstance(state: State) {
    // A running recording would lose its index when the container goes
    if (state.container != null) runCatching { stopRecording(state) }
    runCatching {
        RemoteRobot("http://127.0.0.1:${state.port}", OkHttpClient())
            .runJs("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true);", false)
    }
    runCatching { ProcessHandle.of(state.pid).ifPresent { it.destroy() } }
    if (state.container != null) {
        runCatching { DockerIdeLauncher.remove(state.container) }
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
    state.clear()
}

@Command(name = "stopIde", description = ["Stop the IDE (the selected instance, else the only one) and delete its sandbox."])
class StopIdeCommand : Callable<Int> {
    @Option(names = ["--all"], description = ["Stop every registered IDE, also those of other agents."])
    var all: Boolean = false

    override fun call(): Int {
        val states = if (all) State.all() else listOfNotNull(State.load())
        if (states.isEmpty()) println("No IDE registered.")
        for (state in states) {
            stopInstance(state)
            println("Stopped '${state.name}' (pid=${state.pid}).")
        }
        return 0
    }
}

@Command(name = "instances", description = ["List the registered IDEs: name, port, alive, container, sandbox."])
class InstancesCommand : Callable<Int> {
    override fun call(): Int {
        val states = State.all()
        if (states.isEmpty()) println("No IDE registered.")
        for (s in states) {
            val status = if (s.isAlive()) "up" else if (s.isLive()) "starting" else "dead"
            val view = s.viewPort?.let { "\t" + DockerIdeLauncher.viewUrl(it) } ?: ""
            println("${s.name}\tport=${s.port}\t$status\t${s.container ?: "host"}\t${s.sandbox}$view")
        }
        return 0
    }
}

@Command(
    name = "ideCache",
    description = [
        "List the Linux IDEs downloaded for --docker (most recently used first) with their size.",
        "--keep N deletes all but the N most recently used (never one a running IDE uses), plus unfinished downloads.",
    ],
)
class IdeCacheCommand : Callable<Int> {
    @Option(names = ["--keep"], paramLabel = "N", description = ["Delete all but the N most recently used Linux IDEs (0 deletes all unused)."])
    var keep: Int? = null

    override fun call(): Int {
        val keep = keep
        if (keep != null) {
            val deleted = DockerIdeLauncher.withCacheLock {
                DockerIdeLauncher.evict(protect = State.all().mapNotNull { it.ide }.toSet(), keep = keep.coerceAtLeast(0))
            }
            val sandboxes = State.locked {
                DockerIdeLauncher.pruneSandboxes(keep = State.all().map { it.sandbox.toAbsolutePath().normalize() }.toSet())
            }
            println("Deleted ${deleted.size + sandboxes.size} entries.")
        }
        val ides = DockerIdeLauncher.cachedIdes()
        if (ides.isEmpty()) println("No Linux IDEs cached.")
        for (ide in ides) {
            println("${DockerIdeLauncher.sizeOf(ide)}\t${Files.getLastModifiedTime(ide).toInstant()}\t$ide")
        }
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
        "Capture the screen to a PNG and print its path. --window / --xpath crop to the active window or a component,",
        "--scale shrinks it: an image costs tokens by its pixel count. Cropped, it prints the origin to map pixels back to",
        "clickAt coordinates: screen = origin + pixel / scale. Use getStateSummary for normal observation.",
    ],
)
class ScreenshotCommand : Callable<Int> {
    @Option(names = ["--output", "-o"], description = ["Output path for the PNG (default: auto temp file)."])
    var output: Path? = null

    @Option(names = ["--window"], description = ["Only the active window (e.g. a dialog)."])
    var window: Boolean = false

    @Option(names = ["--xpath"], description = ["Only the first component matching this xpath."])
    var xpath: String? = null

    @Option(names = ["--scale"], description = ["Scale factor, e.g. 0.5 (default 1)."])
    var scale: Double = 1.0

    override fun call(): Int {
        require(scale > 0 && scale <= 1) { "--scale must be in (0, 1]" }
        val outPath = output ?: Files.createTempFile("idea-ui-controller-screenshot-", ".png")
        val r = connect()
        var img = r.getScreenshot()
        var origin: java.awt.Rectangle? = null
        if (window || xpath != null) {
            val boundsJs = "var p = c.getLocationOnScreen(); p.x + ',' + p.y + ',' + c.getWidth() + ',' + c.getHeight() + ',' + " +
                "java.awt.Toolkit.getDefaultToolkit().getScreenSize().width"
            val bounds = if (xpath != null) {
                r.callJs<String>(r.find(ComponentFixture::class.java, xp(xpath!!), Duration.ofSeconds(10)), "var c = component; $boundsJs", true)
            } else {
                r.callJs<String>(
                    "var c = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(); " +
                        "if (c === null) throw 'No active window'; $boundsJs",
                    true,
                )
            }
            val (x, y, w, h, screenWidth) = bounds.split(',').map { it.trim().toDouble().toInt() }
            // A HiDPI screenshot has more pixels than the screen has points
            val f = img.width.toDouble() / screenWidth
            val crop = java.awt.Rectangle((x * f).toInt(), (y * f).toInt(), (w * f).toInt(), (h * f).toInt())
                .intersection(java.awt.Rectangle(0, 0, img.width, img.height))
            require(!crop.isEmpty) { "The component is off screen: $bounds" }
            img = img.getSubimage(crop.x, crop.y, crop.width, crop.height)
            origin = java.awt.Rectangle(x, y, w, h)
        }
        if (scale < 1) {
            val scaled = java.awt.image.BufferedImage((img.width * scale).toInt(), (img.height * scale).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, scaled.width, scaled.height, null)
                dispose()
            }
            img = scaled
        }
        javax.imageio.ImageIO.write(img, "PNG", outPath.toFile())
        val info = listOfNotNull(
            origin?.let { "origin=${it.x},${it.y}" },
            "size=${img.width}x${img.height}",
            "scale=$scale".takeIf { scale < 1 },
        )
        println("${outPath.absolutePathString()} ${info.joinToString(" ")}")
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

    @CommandLine.Mixin val stateOption = EditorStateOption()

    override fun call(): Int {
        val r = connect()
        r.runJs(
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
        stateOption.printIfRequested(r)
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

// The mistakes agents make over and over, and what fixes them
private fun errorHint(message: String): String? = when {
    "JavaPackage" in message ->
        "a plugin's classes aren't visible to plain package access: use pluginClass('<plugin id>', '<fqn>')"
    "Access to Editor models" in message || "Read access is allowed from inside read-action" in message ->
        "run it with --edt (or use editorState / editorGetText)"
    "Access is allowed from write thread only" in message || "Write access is allowed" in message ->
        "change documents with --edt inside WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function() { ... } })), or use editorInjectText"
    "is ambiguous" in message ->
        "Rhino can't choose a Java overload: pass a new java.lang.Runnable({ run: ... }) instead of a function, or convert arguments with String(x)"
    "Found more than one" in message -> "the xpath matches several components: narrow it (add @class, or an ancestor //div[@class='...']//)"
    "Failed to find" in message -> "check what's on screen with getStateSummary"
    else -> null
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
            errorHint("$message ${root.message}")?.let { System.err.println("hint: $it") }
            if (e is java.net.SocketTimeoutException || root is java.net.SocketTimeoutException) {
                System.err.println("The script may still be running in the IDE; later commands wait for it (one request at a time).")
            }
        }
        1
    }
    exitProcess(cli.execute(*args))
}
