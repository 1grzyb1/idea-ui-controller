package com.wkeska.ideauicontroller

import com.intellij.remoterobot.launcher.Ide
import com.intellij.remoterobot.launcher.Os
import com.intellij.remoterobot.launcher.extractZip
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Launches a sandboxed IDE like remote-robot's `IdeLauncher.launchIde`, but detached from the CLI's stdio.
 *
 * `IdeLauncher` starts the IDE with `inheritIO()`, so the IDE keeps the CLI's stdout and stderr open after `runIde`
 * returns. Anything reading the CLI's output (a pipe, `$(...)`, an agent's shell tool) then waits until the IDE exits.
 * Here the IDE output goes to [outputLogOf] the sandbox instead.
 */
internal object DetachedIdeLauncher {

    fun outputLogOf(sandbox: Path): Path = sandbox.resolve("ide-output.log")

    fun launch(ide: Path, sandbox: Path, plugins: List<Path>, properties: Map<String, Any>): Process {
        val prepared = prepare(ide, Os.hostOS(), sandbox, plugins, properties)
        val processBuilder = ProcessBuilder(prepared.startupScript.absolutePathString())
            .redirectErrorStream(true)
            .redirectOutput(outputLogOf(sandbox).toFile())
        processBuilder.environment().putAll(prepared.environment)
        return processBuilder.start()
    }

    /** What it takes to start [ide] built for [os]: its startup script and the environment pointing it at the sandbox. */
    class PreparedIde(val startupScript: Path, val environment: Map<String, String>)

    /**
     * Installs [plugins] and writes the sandbox config. All paths stay absolute host paths, so a container mounts them 1:1.
     *
     * With [client], prepares the JetBrains Client bundled with the IDE (`bin/jetbrains_client.sh`) instead of the IDE.
     */
    fun prepare(
        ide: Path,
        os: Os,
        sandbox: Path,
        plugins: List<Path>,
        properties: Map<String, Any>,
        client: Boolean = false,
    ): PreparedIde {
        Files.createDirectories(sandbox)
        val pluginsDir = Files.createTempDirectory(sandbox, "plugins")
        plugins.forEach { installPlugin(it, pluginsDir) }
        val ideProperties = sandboxProperties(sandbox, pluginsDir) + defaultIdeProperties(ide, os) + properties
        val startupScript = if (client) binDir(ide, os).resolve("jetbrains_client.sh") else startupScript(ide, os)
        return PreparedIde(startupScript, launchEnvironment(ide, os, sandbox, ideProperties, client))
    }

    private fun installPlugin(plugin: Path, pluginsDir: Path) {
        if (plugin.name.endsWith(".jar")) {
            Files.copy(plugin, pluginsDir.resolve(plugin.name))
        } else {
            extractZip(plugin, pluginsDir)
        }
    }

    private fun sandboxProperties(sandbox: Path, pluginsDir: Path): Map<String, Any> = mapOf(
        "idea.config.path" to Files.createTempDirectory(sandbox, "config").toAbsolutePath().also(::skipOnboarding),
        "idea.system.path" to Files.createTempDirectory(sandbox, "system").toAbsolutePath(),
        "idea.plugins.path" to pluginsDir.toAbsolutePath(),
        "idea.log.path" to Files.createTempDirectory(sandbox, "log").toAbsolutePath(),
        "jb.privacy.policy.text" to "<!--999.999-->",
        "jb.consents.confirmation.enabled" to "false",
        "native.mac.file.chooser.enabled" to "false",
        "ide.mac.file.chooser.native" to "false",
        "idea.is.internal" to "true",
    )

    // The "Meet the Islands Theme" / new UI tour dialog takes the focus from the first project an agent opens.
    // NewUiOnboardingUtil.shouldProposeOnboarding is false once it was proposed for some version.
    private fun skipOnboarding(config: Path) {
        val options = Files.createDirectories(config.resolve("options"))
        Files.writeString(
            options.resolve("other.xml"),
            """
            <application>
              <component name="PropertiesComponent">
                <property name="experimental.ui.onboarding.proposed.version" value="999.0" />
              </component>
            </application>
            """.trimIndent(),
        )
    }

    private fun defaultIdeProperties(ide: Path, os: Os): Map<String, Any> {
        val properties = Properties()
        Files.newBufferedReader(binDir(ide, os).resolve("idea.properties")).use { properties.load(it) }
        return properties.entries.associate { it.key.toString() to it.value }
    }

    private fun launchEnvironment(
        ide: Path,
        os: Os,
        sandbox: Path,
        ideProperties: Map<String, Any>,
        client: Boolean,
    ): Map<String, String> {
        val propertiesFile = sandbox.resolve("idea.properties")
        Files.newBufferedWriter(propertiesFile).use { writer ->
            Properties().apply { ideProperties.forEach { (key, value) -> setProperty(key, value.toString()) } }
                .store(writer, null)
        }
        val vmOptionsFile = sandbox.resolve("idea.vmoptions")
        val defaultVmOptions = if (client) binDir(ide, os).resolve("jetbrains_client64.vmoptions") else defaultVmOptionsFile(ide, os)
        vmOptionsFile.writeText(defaultVmOptions.toFile().readText())

        if (client) {
            return mapOf(
                "JETBRAINS_CLIENT_PROPERTIES" to propertiesFile.absolutePathString(),
                "JETBRAINS_CLIENT_VM_OPTIONS" to vmOptionsFile.absolutePathString(),
            )
        }
        val product = ideProduct(ide, os)
        return mapOf(
            product.getIdePropertiesEnvVarName() to propertiesFile.absolutePathString(),
            product.getVmOptionsEnvVarName() to vmOptionsFile.absolutePathString(),
        )
    }

    private fun defaultVmOptionsFile(ide: Path, os: Os): Path {
        val candidates = Files.list(binDir(ide, os)).use { files ->
            files.filter { it.name.endsWith(".vmoptions") && !it.name.contains("(client|cwm)".toRegex()) }.toList()
        }
        return candidates.singleOrNull()
            ?: candidates.singleOrNull { it.name.contains("64") }
            ?: throw FileNotFoundException("failed to find the default vmoptions file in ${binDir(ide, os)}")
    }

    private fun ideProduct(ide: Path, os: Os): Ide {
        val buildTxt = if (os == Os.MAC) ide.resolve("Contents/Resources/build.txt") else ide.resolve("build.txt")
        return Ide.withCode(Files.readAllLines(buildTxt).single().substringBefore("-"))
    }

    private fun startupScript(ide: Path, os: Os): Path {
        val name = when (ideProduct(ide, os)) {
            Ide.IDEA_COMMUNITY, Ide.IDEA_ULTIMATE -> "idea"
            Ide.CLION -> "clion"
            Ide.WEBSTORM -> "webstorm"
            Ide.RUBY_MINE -> "rubymine"
            Ide.PYCHARM, Ide.PYCHARM_COMMUNITY -> "pycharm"
            Ide.RIDER -> "rider"
        }
        return when (os) {
            // The native launcher where there is one: the IDE nags about being started from the script
            Os.LINUX -> ide.resolve("bin/$name").takeIf { Files.isExecutable(it) } ?: ide.resolve("bin/$name.sh")
            Os.WINDOWS -> ide.resolve("bin/${name}64.exe")
            Os.MAC -> ide.resolve("Contents/MacOS/${macExecutableName(ide)}")
        }
    }

    private fun macExecutableName(ide: Path): String {
        val process = ProcessBuilder("defaults", "read", "${ide.absolutePathString()}/Contents/Info", "CFBundleExecutable")
            .start()
        process.waitFor(10, TimeUnit.SECONDS)
        return process.inputStream.bufferedReader().readText().trim()
    }

    private fun binDir(ide: Path, os: Os): Path = ide.resolve(if (os == Os.MAC) "Contents/bin" else "bin")
}
