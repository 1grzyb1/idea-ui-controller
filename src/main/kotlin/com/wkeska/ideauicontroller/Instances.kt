package com.wkeska.ideauicontroller

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal val HOME_DIR: Path = Path.of(System.getProperty("user.home"), ".idea-ui-controller")
private val INSTANCES_DIR: Path = HOME_DIR.resolve("instances")
private val INSTANCE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

/** The instance named by `--instance` or `IUC_INSTANCE`, `null` when neither is given. */
internal object SelectedInstance {
    var name: String? = System.getenv("IUC_INSTANCE")?.takeIf { it.isNotBlank() }
        set(value) {
            require(value == null || INSTANCE_NAME.matches(value)) { "Instance names are letters, digits, '_', '.', '-': $value" }
            field = value
        }
}

/**
 * One registered IDE. Several run side by side, each with its own state file, port, sandbox and (in Docker mode)
 * container. [pid] is the `runIde` CLI itself while the IDE starts, so the name and port stay reserved meanwhile.
 * [container] is the Docker container the IDE runs in, `null` when it runs directly on the host. [ide] is the IDE
 * directory, so the Linux IDE cache doesn't evict it while it runs. [viewPort] serves the live view (Docker only).
 */
internal data class State(
    val name: String,
    val pid: Long,
    val port: Int,
    val sandbox: Path,
    val container: String? = null,
    val ide: Path? = null,
    val viewPort: Int? = null,
) {
    val file: Path get() = fileOf(name)

    fun save() {
        Files.createDirectories(INSTANCES_DIR)
        val props = Properties().apply {
            setProperty("pid", pid.toString())
            setProperty("port", port.toString())
            setProperty("sandbox", sandbox.absolutePathString())
            container?.let { setProperty("container", it) }
            ide?.let { setProperty("ide", it.absolutePathString()) }
            viewPort?.let { setProperty("viewPort", it.toString()) }
        }
        // Atomically: other CLI processes read state files at any time
        val tmp = Files.createTempFile(INSTANCES_DIR, ".$name", ".tmp")
        tmp.outputStream().use { props.store(it, "idea-ui-controller instance $name") }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun clear() {
        file.deleteIfExists()
    }

    companion object {
        fun fileOf(name: String): Path = INSTANCES_DIR.resolve("$name.properties")

        fun load(name: String): State? {
            val file = fileOf(name)
            if (!file.exists()) return null
            val props = Properties().also { p -> file.inputStream().use { p.load(it) } }
            return State(
                name = name,
                pid = props.getProperty("pid").toLong(),
                port = props.getProperty("port").toInt(),
                sandbox = Path.of(props.getProperty("sandbox")),
                container = props.getProperty("container"),
                ide = props.getProperty("ide")?.let(Path::of),
                viewPort = props.getProperty("viewPort")?.toIntOrNull(),
            )
        }

        fun all(): List<State> {
            if (!INSTANCES_DIR.exists()) return emptyList()
            return Files.list(INSTANCES_DIR).use { s -> s.toList() }
                .map { it.fileName.toString() }
                .filter { it.endsWith(".properties") && !it.startsWith(".") }
                .mapNotNull { runCatching { load(it.removeSuffix(".properties")) }.getOrNull() }
                .sortedBy { it.name }
        }

        /** The instance a command talks to: the selected one, else the only live one (or the only registered one). */
        fun load(): State? {
            SelectedInstance.name?.let { return load(it) }
            val all = all()
            val live = all.filter { it.isLive() }
            check(live.size <= 1) {
                "${live.size} IDEs are running (${live.joinToString { it.name }}): pass --instance NAME or set IUC_INSTANCE"
            }
            return live.singleOrNull() ?: all.singleOrNull()
        }

        /** Runs [block] holding the lock that serializes reserving names, ports and sandboxes across CLI processes. */
        inline fun <T> locked(block: () -> T): T = withFileLock(INSTANCES_DIR.resolve(".lock"), block)
    }
}

/** The robot-server answers on [State.port]. */
internal fun State.isAlive(): Boolean = try {
    java.net.Socket().use { sock ->
        sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
        true
    }
} catch (_: Throwable) {
    false
}

/** The IDE (or the `runIde` still starting it) runs: its name, port and sandbox are taken. */
internal fun State.isLive(): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false) || isAlive()

internal inline fun <T> withFileLock(lockFile: Path, block: () -> T): T {
    Files.createDirectories(lockFile.parent)
    FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { return block() }
    }
}
