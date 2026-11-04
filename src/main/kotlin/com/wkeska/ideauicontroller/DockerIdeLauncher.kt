package com.wkeska.ideauicontroller

import com.intellij.remoterobot.launcher.Os
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Runs a Linux build of the IDE in a Docker container on a virtual X display (Xvfb).
 *
 * The AWT robot then types into and clicks on that virtual display, so the host keyboard, mouse and focus stay free.
 * The sandbox, the IDE and any extra [launch] mounts are mounted at the same absolute paths as on the host, so every
 * path in the sandbox config, in `runJs` scripts and in `idea.log` means the same file on both sides.
 */
internal object DockerIdeLauncher {

    const val CONTAINER_NAME = "idea-ui-controller"
    private const val IMAGE = "idea-ui-controller-xvfb:1"

    private val HOME: Path = Path.of(System.getProperty("user.home"), ".idea-ui-controller")
    private val LINUX_IDES: Path = HOME.resolve("linux-ides")
    private val DOWNLOADS: Path = HOME.resolve("downloads")

    /** Sandboxes live under the home directory: Docker Desktop always shares it with its VM. */
    val SANDBOXES: Path = HOME.resolve("sandboxes")

    // Product codes in product-info.json -> codes of the JetBrains releases API
    private val RELEASE_CODES = mapOf(
        "IU" to "IIU", "IC" to "IIC", "RD" to "RD", "CL" to "CL", "WS" to "WS",
        "PY" to "PCP", "PC" to "PCC", "GO" to "GO", "RM" to "RM", "PS" to "PS",
    )

    // Docker Desktop doesn't put its CLI on PATH unless the user opted in
    private val DOCKER_CANDIDATES = listOf(
        Path.of(System.getProperty("user.home"), ".docker/bin/docker"),
        Path.of("/usr/local/bin/docker"),
        Path.of("/opt/homebrew/bin/docker"),
        Path.of("/Applications/Docker.app/Contents/Resources/bin/docker"),
    )

    private val docker: Path by lazy {
        val onPath = System.getenv("PATH").orEmpty().split(':').map { Path.of(it, "docker") }
        (onPath + DOCKER_CANDIDATES).firstOrNull { Files.isExecutable(it) }
            ?: error("Docker CLI not found. Install and start Docker Desktop.")
    }

    fun launch(
        ide: Path,
        sandbox: Path,
        plugins: List<Path>,
        properties: Map<String, Any>,
        port: Int,
        mounts: List<Path>,
    ): Process {
        val prepared = DetachedIdeLauncher.prepare(
            ide, Os.LINUX, sandbox, plugins,
            // The robot-server binds to 127.0.0.1 by default, which a published container port can't reach
            // No update check: its balloon covers the welcome screen of an old build
            properties + mapOf("robot-server.host.public" to true, "ide.no.platform.update" to true),
        )
        return startContainer(ide, sandbox, port, mounts, prepared.environment, prepared.startupScript)
    }

    /**
     * Runs [ide] in Remote Development split mode: its backend (`serverMode`) opens [project], and the JetBrains Client
     * of the same build connects to it as the frontend, both in one container.
     *
     * The robot-server goes into the frontend only, which is where the UI and the frontend parts of plugins run. Every
     * other plugin in [plugins] is installed on both sides.
     */
    fun launchSplit(
        ide: Path,
        sandbox: Path,
        project: Path,
        plugins: List<Path>,
        robotPlugin: Path,
        properties: Map<String, Any>,
        port: Int,
        mounts: List<Path>,
    ): Process {
        val project = project.toAbsolutePath().normalize()
        // The project opens right away, without the welcome screen, so trust it up front
        val common = mapOf("ide.no.platform.update" to true, "idea.trust.all.projects" to true)
        val backend = DetachedIdeLauncher.prepare(
            ide, Os.LINUX, Files.createDirectories(sandbox.resolve("backend")), plugins, common,
        )
        // `listOf`: a Path is an Iterable<Path>, so `plugins + robotPlugin` would add its name segments
        val frontend = DetachedIdeLauncher.prepareClient(
            ide, Files.createDirectories(sandbox.resolve("frontend")), plugins + listOf(robotPlugin),
            properties + common + mapOf("robot-server.host.public" to true),
        )
        // The shell launcher: the native one isn't known to take `serverMode`
        val backendScript = backend.startupScript.let {
            if (it.name.endsWith(".sh")) it else it.resolveSibling("${it.name}.sh")
        }
        val backendLog = sandbox.resolve("backend-output.log")
        val script = sandbox.resolve("split-mode.sh")
        script.writeText(
            """
            |#!/bin/sh
            |# Starts the backend, waits for the join link it prints, then the JetBrains Client connected to it
            |"${backendScript.absolutePathString()}" serverMode "${project.absolutePathString()}" > "${backendLog.absolutePathString()}" 2>&1 &
            |i=0
            |while [ ${'$'}i -lt 600 ]; do
            |  LINK=${'$'}(grep -o 'Join link: tcp://[^ ]*' "${backendLog.absolutePathString()}" | head -1 | sed 's/Join link: //')
            |  [ -n "${'$'}LINK" ] && break
            |  sleep 1; i=${'$'}((i + 1))
            |done
            |[ -n "${'$'}LINK" ] || { echo "The backend printed no join link, see ${backendLog.absolutePathString()}"; exit 1; }
            |echo "Join link: ${'$'}LINK"
            |# The link already has a #fragment, so remoteId goes after it
            |exec "${frontend.startupScript.absolutePathString()}" thinClient "${'$'}LINK&remoteId=Split%20Mode"
            |""".trimMargin()
        )
        script.toFile().setExecutable(true)

        // A solution file opens too; its directory is what the container needs
        val projectDir = if (Files.isRegularFile(project)) project.parent else project
        return startContainer(ide, sandbox, port, mounts + listOf(projectDir), backend.environment + frontend.environment, script)
    }

    private fun startContainer(
        ide: Path,
        sandbox: Path,
        port: Int,
        mounts: List<Path>,
        environment: Map<String, String>,
        startupScript: Path,
    ): Process {
        buildImage()
        remove()

        val command = mutableListOf(
            docker.absolutePathString(), "run", "--rm", "--name", CONTAINER_NAME,
            "--shm-size", "1g",
            "-p", "127.0.0.1:$port:$port",
            "-e", "LANG=C.UTF-8",
            "-v", "${ide.absolutePathString()}:${ide.absolutePathString()}:ro",
            "-v", "${sandbox.absolutePathString()}:${sandbox.absolutePathString()}",
        )
        for (mount in mounts) {
            val path = mount.toAbsolutePath().normalize()
            Files.createDirectories(path)
            command += listOf("-v", "${path.absolutePathString()}:${path.absolutePathString()}")
        }
        environment.forEach { (key, value) -> command += listOf("-e", "$key=$value") }
        command += listOf(IMAGE, startupScript.absolutePathString())

        return dockerProcess(command)
            .redirectErrorStream(true)
            .redirectOutput(DetachedIdeLauncher.outputLogOf(sandbox).toFile())
            .start()
    }

    /** Force-removes the container; a no-op when it doesn't exist. */
    fun remove() {
        dockerProcess(listOf(docker.absolutePathString(), "rm", "-f", CONTAINER_NAME))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(60, TimeUnit.SECONDS)
    }

    /**
     * Returns a Linux build of [ide]: [ide] itself when it already is one, otherwise the Linux build with the same
     * product code and build number, downloaded and unpacked once into [LINUX_IDES].
     */
    fun resolveLinuxIde(ide: Path, http: OkHttpClient): Path {
        if (ide.resolve("product-info.json").exists()) return ide
        // A macOS .app, or its unwrapped `Contents` directory as the IntelliJ Platform Gradle plugin extracts it
        val productInfo = listOf("Contents/Resources/product-info.json", "Resources/product-info.json")
            .map(ide::resolve)
            .firstOrNull { it.exists() } ?: ide.resolve("Contents/Resources/product-info.json")
        require(productInfo.exists()) { "Not an IDE installation (no product-info.json): $ide" }
        val json = productInfo.readText()
        return linuxBuild(jsonString(json, "productCode"), jsonString(json, "buildNumber"), http)
    }

    /** `IU-263.7307.44`, or `IU-eap` for the newest EAP build. */
    val BUILD_SPEC = Regex("([A-Z]{2})-(eap|\\d+\\.\\d+(?:\\.\\d+)*)")

    /** The Linux build for a [BUILD_SPEC] like `IU-263.7307.44` or `IU-eap`, downloaded and unpacked once. */
    fun resolveBuildSpec(spec: String, http: OkHttpClient): Path {
        val match = BUILD_SPEC.matchEntire(spec) ?: error("Not a build spec like IU-263.7307.44 or IU-eap: $spec")
        val (productCode, version) = match.destructured
        val build = if (version == "eap") latestEapBuild(productCode, http) else version
        println("      Build: $productCode-$build")
        return linuxBuild(productCode, build, http)
    }

    private fun latestEapBuild(productCode: String, http: OkHttpClient): String {
        val code = RELEASE_CODES[productCode] ?: error("Don't know the releases API code for product $productCode")
        val url = "https://data.services.jetbrains.com/products/releases?code=$code&type=eap&latest=true"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        return Regex(""""build"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("No EAP build of $productCode in $url")
    }

    private fun linuxBuild(productCode: String, build: String, http: OkHttpClient): Path {
        val arch = dockerArchitecture()

        val target = LINUX_IDES.resolve("$productCode-$build-$arch")
        if (target.resolve("product-info.json").exists()) return target

        val archive = DOWNLOADS.resolve("$productCode-$build-linux-$arch.tar.gz")
        if (!archive.exists()) download(downloadUrl(productCode, build, arch, http), archive, http)

        println("      Unpacking $archive ...")
        val partial = LINUX_IDES.resolve("$productCode-$build-$arch.partial")
        partial.toFile().deleteRecursively()
        Files.createDirectories(partial)
        run(listOf("tar", "-xzf", archive.absolutePathString(), "-C", partial.absolutePathString(), "--strip-components", "1"))
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    private fun downloadUrl(productCode: String, build: String, arch: String, http: OkHttpClient): String {
        val code = RELEASE_CODES[productCode] ?: error("Don't know the releases API code for product $productCode")
        val url = "https://data.services.jetbrains.com/products/releases?code=$code&type=release,eap,rc&build=$build"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val key = if (arch == "aarch64") "linuxARM64" else "linux"
        return Regex(""""$key"\s*:\s*\{\s*"link"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("No $key download for $productCode build $build in $url")
    }

    private fun download(url: String, target: Path, http: OkHttpClient) {
        println("      Downloading $url (once, ~1.5 GB) ...")
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code} for $url" }
                response.body!!.byteStream().use { Files.copy(it, partial, StandardCopyOption.REPLACE_EXISTING) }
            }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Builds the Xvfb runtime image from the Dockerfile bundled in the CLI; Docker's layer cache makes repeats instant. */
    private fun buildImage() {
        val context = Files.createTempDirectory("idea-ui-controller-docker")
        try {
            for (name in listOf("Dockerfile", "entrypoint.sh")) {
                javaClass.getResourceAsStream("/docker/$name")!!.use { Files.copy(it, context.resolve(name)) }
            }
            run(listOf(docker.absolutePathString(), "build", "-q", "-t", IMAGE, context.absolutePathString()))
        } finally {
            context.toFile().deleteRecursively()
        }
    }

    private fun dockerArchitecture(): String =
        run(listOf(docker.absolutePathString(), "info", "--format", "{{.Architecture}}")).trim()
            .let { if (it == "arm64") "aarch64" else it }

    private fun jsonString(json: String, key: String): String =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: error("No \"$key\" in product-info.json")

    // The docker CLI runs its credential helpers from PATH
    private fun dockerProcess(command: List<String>): ProcessBuilder = ProcessBuilder(command).also {
        it.environment()["PATH"] = "${docker.parent.absolutePathString()}:${System.getenv("PATH").orEmpty()}"
    }

    private fun run(command: List<String>): String {
        val process = dockerProcess(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "`${command.joinToString(" ")}` failed:\n$output" }
        return output
    }
}
