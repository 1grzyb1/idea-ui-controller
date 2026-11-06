package com.wkeska.ideauicontroller

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.Locator
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.Callable

// xpaths copied from getState carry HTML entities (`&larr; Back`) where the component has the plain character
internal fun xp(expr: String) = byXpath(decodeHtmlEntities(expr))

private fun <T : ComponentFixture> RemoteRobot.findAlive(type: Class<T>, locator: Locator, timeout: Duration): T {
    val pid = State.load()?.pid
    val deadline = System.nanoTime() + timeout.toNanos()
    val chunkNs = Duration.ofSeconds(2).toNanos()
    var last: Throwable = IllegalStateException("Timeout waiting for component")
    while (System.nanoTime() < deadline) {
        if (pid != null) {
            check(ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                "IDE process (pid=$pid) exited while waiting for component"
            }
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) break
        try {
            return find(type, locator, Duration.ofNanos(minOf(remaining, chunkNs)))
        } catch (e: Throwable) {
            last = e
        }
    }
    throw last
}

private fun RemoteRobot.welcomeFrame(timeout: Duration = Duration.ofSeconds(10)): ContainerFixture =
    findAlive(ContainerFixture::class.java, xp("//div[@class='FlatWelcomeFrame']"), timeout)

private fun RemoteRobot.ideFrame(timeout: Duration = Duration.ofSeconds(60)): ContainerFixture =
    findAlive(ContainerFixture::class.java, xp("//div[@class='IdeFrameImpl']"), timeout)

private fun RemoteRobot.editor(file: String?, timeout: Duration = Duration.ofSeconds(30)): ComponentFixture {
    if (file != null) {
        val tab = findAlive(
            ComponentFixture::class.java,
            xp("//div[@class='EditorTabs']//div[(@accessiblename='$file' or starts-with(@accessiblename,'$file,')) and @class='SimpleColoredComponent']"),
            timeout,
        )
        tab.click()
    }
    val loc = if (file != null)
        xp("//div[@accessiblename='Editor for $file' and @class='EditorComponentImpl']")
    else
        xp("//div[@class='EditorComponentImpl']")
    return findAlive(ComponentFixture::class.java, loc, timeout)
}

private fun clickFixture(f: ComponentFixture) = f.click()

// ---------- Generic JS ----------

private const val PRELUDE_HELP =
    "Prelude helpers: targetEditor(), targetProject(), loadPluginClass(pluginId, fqn), pluginClass(pluginId, fqn), normText(s), focusOwner(), onEdt(fn)."

/** The script of `runJs` / `callJs`: `--script`, `--file`, or the positional argument. */
abstract class ScriptOptions {
    @Option(names = ["--script", "-s"], description = ["The JS script body."])
    var script: String? = null

    @Option(names = ["--file", "-f"], description = ["Read the script from a file."])
    var file: java.nio.file.Path? = null

    @Parameters(index = "0", arity = "0..1", paramLabel = "SCRIPT", description = ["The JS script body, instead of --script."])
    var positional: String? = null

    @Option(names = ["--edt"], description = ["Run on the EDT (needed for editor models, documents, Swing)."])
    var edt: Boolean = false

    protected fun body(): String = listOfNotNull(script, file?.let { java.nio.file.Files.readString(it) }, positional)
        .singleOrNull() ?: error("Pass the script once: --script, --file or as the argument")
}

@Command(name = "runJs", description = ["Run an arbitrary robot runJs script (no return value).", PRELUDE_HELP])
class RunJsCommand : ScriptOptions(), Callable<Int> {
    override fun call(): Int {
        connect().runJs(withPrelude(body()), edt)
        return 0
    }
}

@Command(name = "callJs", description = ["Call an arbitrary robot callJs script and print the result.", PRELUDE_HELP])
class CallJsCommand : ScriptOptions(), Callable<Int> {
    override fun call(): Int {
        val r = connect().callJs<Serializable>(withPrelude(body()), edt)
        println(r)
        return 0
    }
}

// ---------- Welcome ----------

@Command(name = "welcomeVerify", description = ["Verify the Welcome frame is shown."])
class WelcomeVerifyCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10
    override fun call(): Int {
        connect().welcomeFrame(Duration.ofSeconds(timeoutSeconds))
        println("Welcome frame present.")
        return 0
    }
}

@Command(name = "welcomeNewProject", description = ["Click the 'New Project' action on the Welcome frame."])
class WelcomeNewProjectCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.welcomeFrame()
        frame.find(
            ComponentFixture::class.java,
            xp("//div[(@class='MainButton' and @text='New Project') or (@accessiblename='New Project' and @class='JButton')]"),
            Duration.ofSeconds(10),
        ).click()
        return 0
    }
}

@Command(name = "welcomeNewSolution", description = ["Click the 'New Solution' action (Rider)."])
class WelcomeNewSolutionCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.welcomeFrame()
        frame.find(
            ComponentFixture::class.java,
            xp("//div[(@class='MainButton' and @text='New Solution') or (@accessiblename='New Solution' and @class='JButton')]"),
            Duration.ofSeconds(10),
        ).click()
        return 0
    }
}

@Command(name = "welcomeMoreActions", description = ["Click 'More Actions' on the Welcome frame."])
class WelcomeMoreActionsCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.welcomeFrame()
        frame.find(
            ComponentFixture::class.java,
            xp("//div[@accessiblename='More Actions' and @class='ActionButton']"),
            Duration.ofSeconds(10),
        ).click()
        return 0
    }
}

@Command(name = "welcomeImportProject", description = ["Click 'Get from Version Control...' on the Welcome frame."])
class WelcomeImportProjectCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.welcomeFrame()
        frame.find(
            ComponentFixture::class.java,
            xp("//div[@accessiblename='Get from Version Control...' and @class='JButton']"),
            Duration.ofSeconds(10),
        ).click()
        return 0
    }
}

@Command(name = "welcomeHeavyweightPopup", description = ["Wait for a HeavyWeightWindow (menu / popup) to appear."])
class WelcomeHeavyweightPopupCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10
    override fun call(): Int {
        val r = connect()
        r.welcomeFrame().find(
            ComponentFixture::class.java,
            xp("//div[@class='HeavyWeightWindow']"),
            Duration.ofSeconds(timeoutSeconds),
        )
        println("HeavyWeightWindow present.")
        return 0
    }
}

// ---------- Editor ----------

abstract class EditorScopedOptions {
    @Option(names = ["--file"], description = ["Editor tab title; if omitted, the focused editor, else the selected one of the active project."])
    var file: String? = null

    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 30
}

/**
 * Runs [body] on the EDT with `editor` bound to the editor of tab [file] (bringing it to front), or to `targetEditor()`
 * when [file] is null. [body] is a function body: it `return`s the value to print.
 */
internal fun RemoteRobot.editorCall(file: String?, timeout: Duration, body: String): String {
    val call = "String((function(editor) { $body })(editor))"
    if (file != null) {
        val e = editor(file, timeout)
        return callJs(e, withPrelude("var editor = component.getEditor(); $call"), true)
    }
    return callJs(
        withPrelude(
            """
            var editor = targetEditor();
            if (editor === null) throw 'No editor: the focus owner is ' + (focusOwner() === null ? '<none>' : focusOwner().getClass().getName()) + ' and no project has a selected text editor';
            $call
            """.trimIndent(),
        ),
        true,
    )
}

private fun RemoteRobot.flushEdt() = runJs(
    """
    // Let the X server deliver the events, then let the EDT process them and what they invokeLater'd
    java.lang.Thread.sleep(50);
    java.awt.EventQueue.invokeAndWait(new java.lang.Runnable({ run: function() {} }));
    java.awt.EventQueue.invokeAndWait(new java.lang.Runnable({ run: function() {} }));
    """.trimIndent(),
    false,
)

/** One line of caret/file/viewport facts, then the text around the caret (`|` marks each caret), at most [maxChars]. */
internal fun RemoteRobot.editorState(file: String?, timeout: Duration, maxChars: Int, extraJs: String?): String =
    editorCall(
        file, timeout,
        """
        var d = editor.getDocument();
        var cm = editor.getCaretModel();
        var c = cm.getPrimaryCaret();
        var lp = c.getLogicalPosition();
        var vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(d);
        var va = editor.getScrollingModel().getVisibleArea();
        var top = editor.xyToLogicalPosition(new java.awt.Point(0, va.y)).line + 1;
        var bottom = editor.xyToLogicalPosition(new java.awt.Point(0, va.y + Math.max(va.height - 1, 0))).line + 1;
        var shape = '?';
        try { shape = String(c.getVisualAttributes().getShape().name()); } catch (x) {}
        var out = 'file=' + (vf === null ? '<none>' : vf.getName()) + ' caret=' + (lp.line + 1) + ':' + (lp.column + 1) +
            ' offset=' + c.getOffset();
        if (cm.getCaretCount() > 1) out += ' carets=' + cm.getCaretCount();
        if (c.hasSelection()) out += ' selection=' + c.getSelectionStart() + '-' + c.getSelectionEnd();
        out += ' shape=' + shape + ' focus=' + editor.getContentComponent().hasFocus() +
            ' visible=' + top + '-' + bottom + ' lines=' + d.getLineCount();
        if (editor.isViewer()) out += ' viewer=true';
        ${extraJs?.let { "out += ' js=' + String((function(editor) { return ($it); })(editor));" } ?: ""}
        var max = $maxChars;
        if (max <= 0) return out;
        var text = String(d.getText());
        var lo = 0, hi = text.length, range = '';
        if (text.length > max) {
            lo = d.getLineStartOffset(Math.max(0, lp.line - 3));
            hi = d.getLineEndOffset(Math.min(d.getLineCount() - 1, lp.line + 3));
            if (hi - lo > max) { lo = Math.max(lo, c.getOffset() - Math.floor(max / 2)); hi = Math.min(hi, lo + max); }
            range = '[' + (d.getLineNumber(lo) + 1) + '-' + (d.getLineNumber(hi) + 1) + ']';
        }
        var offsets = [];
        var carets = cm.getAllCarets();
        for (var i = 0; i < carets.size(); i++) {
            var o = carets.get(i).getOffset();
            if (o >= lo && o <= hi) offsets.push(o);
        }
        offsets.sort(function(a, b) { return b - a; });
        var part = text.substring(lo, hi);
        for (var j = 0; j < offsets.length; j++) part = part.substring(0, offsets[j] - lo) + '|' + part.substring(offsets[j] - lo);
        return out + '\ntext' + range + '=' + JSON.stringify(part);
        """.trimIndent(),
    )

/** `--state` on an input command: print [editorState] once the IDE has processed the input. */
class EditorStateOption {
    @Option(names = ["--state"], description = ["Afterwards print editorState (caret, file, text around the caret)."])
    var state: Boolean = false

    fun printIfRequested(r: RemoteRobot) {
        if (!state) return
        r.flushEdt()
        println(runCatching { r.editorState(null, Duration.ofSeconds(5), 400, null) }.getOrElse { "editorState: ${it.message}" })
    }
}

@Command(
    name = "editorState",
    description = [
        "Print the editor's state in two lines: file, caret line:col (1-based), offset, carets, selection, caret shape,",
        "focus, visible lines; then its text, `|` at each caret: all of it up to --max-chars, else the lines around the caret.",
    ],
)
class EditorStateCommand : EditorScopedOptions(), Callable<Int> {
    @Option(names = ["--max-chars"], description = ["Max text chars to print (default 400, 0 = no text)."])
    var maxChars: Int = 400

    @Option(names = ["--js"], description = ["Extra JS expression to print as js=..., with `editor` bound (prelude helpers available)."])
    var js: String? = null

    override fun call(): Int {
        println(connect().editorState(file, Duration.ofSeconds(timeoutSeconds), maxChars, js))
        return 0
    }
}

@Command(name = "editorVerify", description = ["Verify an editor is open."])
class EditorVerifyCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        connect().editorCall(file, Duration.ofSeconds(timeoutSeconds), "return '';")
        println("Editor${file?.let { " for '$it'" } ?: ""} present.")
        return 0
    }
}

@Command(name = "editorOpen", description = ["Click the editor tab matching --file to bring it to front and focus it."])
class EditorOpenCommand : Callable<Int> {
    @Option(names = ["--file"], required = true) lateinit var file: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 30
    override fun call(): Int {
        connect().editor(file, Duration.ofSeconds(timeoutSeconds))
        println("Opened '$file'.")
        return 0
    }
}

@Command(name = "editorGetText", description = ["Print the editor document text."])
class EditorGetTextCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        print(connect().editorCall(file, Duration.ofSeconds(timeoutSeconds), "return editor.getDocument().getText();"))
        return 0
    }
}

@Command(name = "editorGetSelectedText", description = ["Print the editor's currently selected text."])
class EditorGetSelectedTextCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        print(connect().editorCall(file, Duration.ofSeconds(timeoutSeconds), "return editor.getSelectionModel().getSelectedText() || '';"))
        return 0
    }
}

@Command(name = "editorCaretOffset", description = ["Print the primary caret offset."])
class EditorCaretOffsetCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        println(connect().editorCall(file, Duration.ofSeconds(timeoutSeconds), "return editor.getCaretModel().getOffset();"))
        return 0
    }
}

@Command(name = "editorCaretCount", description = ["Print the number of carets."])
class EditorCaretCountCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        println(connect().editorCall(file, Duration.ofSeconds(timeoutSeconds), "return editor.getCaretModel().getCaretCount();"))
        return 0
    }
}

@Command(
    name = "editorIsBlockCursor",
    description = [
        "Print true/false for a block/underscore caret shape. True indicates a Vim-style normal-mode caret; false covers BAR (insert) and DEFAULT (no Vim).",
    ],
)
class EditorIsBlockCursorCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        val v = connect().editorCall(
            file, Duration.ofSeconds(timeoutSeconds),
            """
            var name = String(editor.getCaretModel().getPrimaryCaret().getVisualAttributes().getShape().name());
            return name === 'BLOCK' || name === 'UNDERSCORE';
            """.trimIndent(),
        )
        println(v)
        return 0
    }
}

@Command(
    name = "editorCaretShape",
    description = ["Print the primary caret's visual shape name: BLOCK, UNDERSCORE, BAR, or DEFAULT."],
)
class EditorCaretShapeCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        println(
            connect().editorCall(
                file, Duration.ofSeconds(timeoutSeconds),
                "return editor.getCaretModel().getPrimaryCaret().getVisualAttributes().getShape().name();",
            ),
        )
        return 0
    }
}

@Command(
    name = "editorInjectText",
    description = [
        "Replace editor document text in an undoable write command; `--caret N` then puts the caret at offset N.",
        "Interprets \\n, \\t, \\r, \\\\ in --text so shell-quoted arguments can carry newlines and tabs.",
    ],
)
class EditorInjectTextCommand : EditorScopedOptions(), Callable<Int> {
    @Option(names = ["--text", "-t"], required = true) lateinit var text: String
    @Option(names = ["--caret"], description = ["Caret offset afterwards (default: unchanged)."]) var caret: Int? = null
    override fun call(): Int {
        val escaped = jsEscape(interpretEscapes(text))
        connect().editorCall(
            file, Duration.ofSeconds(timeoutSeconds),
            """
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(editor.getProject(), new java.lang.Runnable({
                run: function() { editor.getDocument().setText('$escaped'); }
            }));
            ${caret?.let { "editor.getCaretModel().moveToOffset($it);" } ?: ""}
            return '';
            """.trimIndent(),
        )
        return 0
    }
}

@Command(name = "editorMoveToLine", description = ["Scroll to and place the caret at a 1-based line."])
class EditorMoveToLineCommand : EditorScopedOptions(), Callable<Int> {
    @Option(names = ["--line"], required = true) var line: Int = 0
    override fun call(): Int {
        require(line >= 1) { "--line must be >= 1" }
        connect().editorCall(
            file, Duration.ofSeconds(timeoutSeconds),
            """
            var offset = editor.getDocument().getLineStartOffset(${line - 1});
            editor.getCaretModel().moveToOffset(offset);
            editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(offset), com.intellij.openapi.editor.ScrollType.CENTER);
            return '';
            """.trimIndent(),
        )
        return 0
    }
}

// ---------- Idea Frame ----------

@Command(name = "ideVerify", description = ["Verify the main IDE frame is shown."])
class IdeVerifyCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 60
    override fun call(): Int {
        connect().ideFrame(Duration.ofSeconds(timeoutSeconds))
        println("IdeFrameImpl present.")
        return 0
    }
}

@Command(name = "ideProjectName", description = ["Print the active project name."])
class IdeProjectNameCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.ideFrame()
        println(r.callJs<String>(frame, "component.getProject().getName()", false))
        return 0
    }
}

// Every open project out of dumb mode. Read off the EDT and without a frame fixture: a modal dialog (e.g. a "Meet the
// new theme" popup) blocks EDT calls, and a frame found while the project is still opening goes stale.
private const val ALL_PROJECTS_SMART_JS = """
    var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
    var smart = projects.length > 0;
    for (var i = 0; i < projects.length; i++) {
        if (com.intellij.openapi.project.DumbService.isDumb(projects[i])) smart = false;
    }
    smart;
"""

@Command(name = "ideIsSmart", description = ["Print true if a project is open and no open project is in dumb mode."])
class IdeIsSmartCommand : Callable<Int> {
    override fun call(): Int {
        println(connect().callJs<Boolean>(ALL_PROJECTS_SMART_JS, false))
        return 0
    }
}

@Command(name = "ideWaitSmart", description = ["Block until a project is open and no open project is in dumb mode."])
class IdeWaitSmartCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 300
    override fun call(): Int {
        val r = connect()
        var lastError: Throwable? = null
        try {
            waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(2)) {
                runCatching { r.callJs<Boolean>(ALL_PROJECTS_SMART_JS, false) }
                    .onFailure { lastError = it }
                    .getOrDefault(false)
            }
        } catch (e: Exception) {
            lastError?.let { e.addSuppressed(it) }
            throw e
        }
        println("Smart mode reached.")
        return 0
    }
}

// ---------- Dialog ----------

@Command(name = "dialogVerify", description = ["Wait for a modal dialog with the given title."])
class DialogVerifyCommand : Callable<Int> {
    @Option(names = ["--title"], required = true) lateinit var title: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 20
    override fun call(): Int {
        connect().find(
            ContainerFixture::class.java,
            xp("//div[@title='$title' and @class='MyDialog']"),
            Duration.ofSeconds(timeoutSeconds),
        )
        println("Dialog '$title' present.")
        return 0
    }
}

@Command(name = "dialogTitle", description = ["Print the title of the currently shown MyDialog."])
class DialogTitleCommand : Callable<Int> {
    @Option(names = ["--title"], description = ["Filter by expected title."]) var title: String? = null
    override fun call(): Int {
        val r = connect()
        val loc = if (title != null)
            xp("//div[@title='$title' and @class='MyDialog']")
        else
            xp("//div[@class='MyDialog']")
        val d = r.find(ContainerFixture::class.java, loc, Duration.ofSeconds(10))
        println(r.callJs<String>(d, "component.getTitle();", false))
        return 0
    }
}

// ---------- Search Everywhere ----------

@Command(name = "searchEverywhereVerify", description = ["Wait for the Search Everywhere UI to be present."])
class SearchEverywhereVerifyCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10
    override fun call(): Int {
        val r = connect()
        val se = r.find(
            ComponentFixture::class.java,
            xp("//div[@accessiblename='Search everywhere' and @class='SearchEverywhereUI']"),
            Duration.ofSeconds(timeoutSeconds),
        )
        r.runJs(se, "robot.moveMouse(component);", false)
        println("SearchEverywhereUI present.")
        return 0
    }
}

// ---------- Action Menu ----------

@Command(name = "actionMenuVerify", description = ["Wait for a top-bar ActionMenu with the given text."])
class ActionMenuVerifyCommand : Callable<Int> {
    @Option(names = ["--text"], required = true) lateinit var text: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 60
    override fun call(): Int {
        val r = connect()
        val loc = xp("//div[@class='ActionMenu' and @text='$text']")
        waitFor(Duration.ofSeconds(timeoutSeconds)) { r.findAll(ComponentFixture::class.java, loc).isNotEmpty() }
        r.find(ComponentFixture::class.java, loc, Duration.ofSeconds(5))
        println("ActionMenu '$text' present.")
        return 0
    }
}

@Command(name = "actionMenuItem", description = ["Click an ActionMenuItem with the given text."])
class ActionMenuItemCommand : Callable<Int> {
    @Option(names = ["--text"], required = true) lateinit var text: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 60
    override fun call(): Int {
        val r = connect()
        val loc = xp("//div[@class='ActionMenuItem' and @text='$text']")
        waitFor(Duration.ofSeconds(timeoutSeconds)) { r.findAll(ComponentFixture::class.java, loc).isNotEmpty() }
        r.find(ComponentFixture::class.java, loc, Duration.ofSeconds(5)).click()
        return 0
    }
}

// ---------- Action Link ----------

@Command(name = "actionLinkClick", description = ["Click an ActionLink via its xpath, using the robot-center-click gesture."])
class ActionLinkClickCommand : Callable<Int> {
    @Option(names = ["--xpath"], required = true) lateinit var xpath: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10
    override fun call(): Int {
        val r = connect()
        val f = r.find(ComponentFixture::class.java, xp(xpath), Duration.ofSeconds(timeoutSeconds))
        r.runJs(
            f,
            """
            importClass(java.awt.Point);
            importClass(org.assertj.swing.core.MouseButton);
            const offset = component.getHeight() / 2;
            robot.click(component, new Point(offset, offset), MouseButton.LEFT_BUTTON, 1);
            """.trimIndent(),
            false,
        )
        return 0
    }
}

// ---------- Gutter ----------

@Command(name = "gutterVerify", description = ["Wait for the editor gutter."])
class GutterVerifyCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 30
    override fun call(): Int {
        val r = connect()
        val g = r.find(
            ComponentFixture::class.java,
            xp("//div[@class='EditorGutterComponentImpl']"),
            Duration.ofSeconds(timeoutSeconds),
        )
        r.runJs(g, "robot.moveMouse(component);", false)
        println("EditorGutterComponentImpl present.")
        return 0
    }
}

// ---------- Widgets (generic) ----------

internal fun jsEscape(s: String) = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

// Interprets \n, \t, \r, \\ in a user-supplied string so shell-quoted arguments can carry newlines and tabs.
// Unknown escape sequences (e.g. \x) pass through verbatim.
internal fun interpretEscapes(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> { append('\n'); i += 2; continue }
                't' -> { append('\t'); i += 2; continue }
                'r' -> { append('\r'); i += 2; continue }
                '\\' -> { append('\\'); i += 2; continue }
            }
        }
        append(c); i++
    }
}

@Command(name = "listSelect", description = ["Select an item in a JBList/JList by index or display text."])
class ListSelectCommand : Callable<Int> {
    @Option(names = ["--xpath"], required = true, description = ["Locator for the JList."])
    lateinit var xpath: String
    @Option(names = ["--index"], description = ["Zero-based row index."]) var index: Int = -1
    @Option(names = ["--text"], description = ["Match first row whose toString equals this."]) var text: String? = null
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        require((index >= 0) xor (text != null)) { "Provide exactly one of --index or --text" }
        val r = connect()
        val list = r.find(ComponentFixture::class.java, xp(xpath), Duration.ofSeconds(timeoutSeconds))
        val script = if (text != null) {
            val esc = jsEscape(text!!)
            """
            var model = component.getModel();
            var idx = -1;
            for (var i = 0; i < model.getSize(); i++) {
                var v = model.getElementAt(i);
                if (v != null && String(v.toString()) === '$esc') { idx = i; break; }
            }
            if (idx < 0) throw String("no list item matching '$esc'");
            component.setSelectedIndex(idx);
            component.ensureIndexIsVisible(idx);
            """.trimIndent()
        } else {
            """
            component.setSelectedIndex($index);
            component.ensureIndexIsVisible($index);
            """.trimIndent()
        }
        r.runJs(list, script, true)
        return 0
    }
}

@Command(name = "treeSelect", description = ["Select a row in a JTree by index or display text (last path component toString)."])
class TreeSelectCommand : Callable<Int> {
    @Option(names = ["--xpath"], required = true, description = ["Locator for the JTree."])
    lateinit var xpath: String
    @Option(names = ["--index"], description = ["Zero-based row index."]) var index: Int = -1
    @Option(names = ["--text"], description = ["Match first row whose last path component toString equals this."]) var text: String? = null
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        require((index >= 0) xor (text != null)) { "Provide exactly one of --index or --text" }
        val r = connect()
        val tree = r.find(ComponentFixture::class.java, xp(xpath), Duration.ofSeconds(timeoutSeconds))
        val script = if (text != null) {
            val esc = jsEscape(text!!)
            """
            var rowCount = component.getRowCount();
            var idx = -1;
            for (var i = 0; i < rowCount; i++) {
                var tp = component.getPathForRow(i);
                if (tp === null) continue;
                var last = tp.getLastPathComponent();
                if (last !== null && String(last.toString()) === '$esc') { idx = i; break; }
            }
            if (idx < 0) throw String("no tree row matching '$esc'");
            component.setSelectionRow(idx);
            component.scrollRowToVisible(idx);
            """.trimIndent()
        } else {
            """
            component.setSelectionRow($index);
            component.scrollRowToVisible($index);
            """.trimIndent()
        }
        r.runJs(tree, script, true)
        return 0
    }
}

internal fun RemoteRobot.setFieldByLabel(label: String, text: String) {
    val escLabel = jsEscape(label)
    val escText = jsEscape(text)
    runJs(
        """
        importClass(javax.swing.JLabel);
        importClass(javax.swing.text.JTextComponent);
        importClass(java.awt.Container);
        importClass(java.awt.Window);
        function findLabel(container) {
            var comps = container.getComponents();
            for (var i = 0; i < comps.length; i++) {
                var c = comps[i];
                if (c instanceof JLabel && '$escLabel' === String(c.getText())) return c;
                if (c instanceof Container) {
                    var r = findLabel(c);
                    if (r !== null) return r;
                }
            }
            return null;
        }
        var windows = Window.getWindows();
        var found = null;
        for (var i = 0; i < windows.length; i++) {
            if (!windows[i].isShowing()) continue;
            found = findLabel(windows[i]);
            if (found !== null) break;
        }
        if (found === null) throw String("no JLabel with text='$escLabel'");
        var target = found.getLabelFor();
        if (target === null) throw String("JLabel '$escLabel' has no labelFor");
        if (!(target instanceof JTextComponent)) throw String("labelFor is not a text component");
        target.setText('$escText');
        """.trimIndent(),
        true,
    )
}

@Command(name = "textFieldSet", description = ["Set text in a text field identified by its adjacent JLabel."])
class TextFieldSetCommand : Callable<Int> {
    @Option(names = ["--label"], required = true, description = ["The JLabel text whose labelFor is the target field."])
    lateinit var label: String
    @Option(names = ["--text"], required = true, description = ["New text (use '' to clear)."])
    lateinit var text: String

    override fun call(): Int {
        connect().setFieldByLabel(label, text)
        return 0
    }
}

@Command(name = "checkboxSet", description = ["Set a JCheckBox to --checked by its visible text."])
class CheckboxSetCommand : Callable<Int> {
    @Option(names = ["--text"], required = true, description = ["Checkbox label text."])
    lateinit var text: String
    @Option(names = ["--checked"], required = true, description = ["true or false."], arity = "1")
    var checked: Boolean = false
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        val r = connect()
        val cb = r.find(
            ComponentFixture::class.java,
            xp("//div[(@text='$text' or @accessiblename='$text') and (@class='JCheckBox' or @class='JBCheckBox')]"),
            Duration.ofSeconds(timeoutSeconds),
        )
        if (r.callJs<Boolean>(cb, "component.isSelected()", true) != checked) cb.click()
        // A mouse click misses a checkbox scrolled out of view or covered by a tooltip; doClick fires the same listeners
        if (r.callJs<Boolean>(cb, "component.isSelected()", true) != checked) {
            r.callJs<Boolean>(cb, "component.doClick(); true", true)
        }
        val now = r.callJs<Boolean>(cb, "component.isSelected()", true)
        println(if (now) "checked" else "unchecked")
        return if (now == checked) 0 else 1
    }
}

@Command(name = "dialogClickButton", description = ["Click a named button inside a modal dialog with the given title."])
class DialogClickButtonCommand : Callable<Int> {
    @Option(names = ["--title"], required = true) lateinit var title: String
    @Option(names = ["--button"], required = true) lateinit var button: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        val r = connect()
        val d = r.find(
            ContainerFixture::class.java,
            xp("//div[@title='$title' and @class='MyDialog']"),
            Duration.ofSeconds(timeoutSeconds),
        )
        d.find(
            ComponentFixture::class.java,
            xp(".//div[(@text='$button' or @accessiblename='$button') and (@class='JButton' or @class='JBOptionButton')]"),
            Duration.ofSeconds(timeoutSeconds),
        ).click()
        return 0
    }
}

// ---------- Keyboard primitives (focused window) ----------

// After a dialog closes the IDE can be left with no focus owner (no window manager on Xvfb): keys then go nowhere
// and the command still "succeeds". Say so instead of letting the agent retry the same keys.
private fun RemoteRobot.warnIfNoFocus() {
    val none = runCatching {
        callJs<Boolean>("java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() === null", true)
    }.getOrDefault(false)
    if (none) {
        System.err.println("warning: nothing in the IDE has keyboard focus, so keys go nowhere. openFile, focusWindow or a click on the target first.")
    }
}

@Command(
    name = "sendKey",
    description = [
        "Dispatch a named key to the focused window via the AWT robot.",
        "Name is the KeyEvent VK_* constant without the VK_ prefix (case-insensitive): ESCAPE, ENTER, TAB, BACK_SPACE, F1, LEFT, etc.",
    ],
)
class SendKeyCommand : Callable<Int> {
    @Option(names = ["--key"], required = true, description = ["VK_* constant name without the VK_ prefix (e.g. ESCAPE)."])
    lateinit var key: String
    @Option(names = ["--count"], description = ["Repeat count (default 1)."])
    var count: Int = 1
    @Option(names = ["--delay-ms"], description = ["Sleep between repeats in ms (default 60)."])
    var delayMs: Long = 60
    @Mixin val stateOption = EditorStateOption()

    override fun call(): Int {
        require(count >= 1) { "--count must be >= 1" }
        val fieldName = "VK_${key.uppercase()}"
        val vk = try {
            java.awt.event.KeyEvent::class.java.getField(fieldName).getInt(null)
        } catch (_: NoSuchFieldException) {
            error("Unknown key '$key'. Use a VK_* constant name without the VK_ prefix, e.g. ESCAPE, ENTER, F1.")
        }
        val r = connect()
        r.warnIfNoFocus()
        r.runJs(
            """
            for (var i = 0; i < $count; i++) {
                robot.pressAndReleaseKey($vk);
                if (i + 1 < $count) java.lang.Thread.sleep($delayMs);
            }
            """.trimIndent(),
            false,
        )
        stateOption.printIfRequested(r)
        return 0
    }
}

@Command(
    name = "typeText",
    description = [
        "Type a literal string into the focused window via the AWT robot (one char at a time).",
        "Interprets \\n, \\t, \\r, and \\\\ escapes. Newline maps to VK_ENTER, tab to VK_TAB; other chars go through robot.type().",
        "Long text is sent in chunks, each a separate request, so no request runs into the HTTP read timeout and",
        "the command returns only after the last key is typed. --set replaces the focused text field's text instead.",
    ],
)
class TypeTextCommand : Callable<Int> {
    @Option(names = ["--text", "-t"], required = true, description = ["Literal text (supports \\n, \\t, \\r, \\\\ escapes)."])
    lateinit var text: String
    @Option(names = ["--delay-ms"], description = ["Sleep between keystrokes in ms (default 20)."])
    var delayMs: Long = 20
    @Option(
        names = ["--set"],
        description = ["Don't type: setText on the focused JTextComponent on the EDT (tokens, paths, anything long). No key events, no autocomplete."],
    )
    var set: Boolean = false
    @Mixin val stateOption = EditorStateOption()

    override fun call(): Int {
        val resolved = interpretEscapes(text)
        val r = connect()
        if (set) {
            val result = r.callJs<String>(
                withPrelude(
                    """
                    var c = focusOwner();
                    if (!(c instanceof javax.swing.text.JTextComponent)) 'NOT_TEXT: ' + (c === null ? '<none>' : c.getClass().getName());
                    else onEdt(function() { c.setText('${jsEscape(resolved)}'); return 'OK'; });
                    """.trimIndent(),
                ),
                false,
            )
            check(result == "OK") { "--set needs a focused text field. Focus owner: ${result.removePrefix("NOT_TEXT: ")}" }
            stateOption.printIfRequested(r)
            return 0
        }
        // The robot-server handles one request at a time: a request still typing after the client gave up keeps
        // typing and every later command waits behind it. Keep each request to a few seconds.
        val chunkSize = (3_000 / (delayMs + 10)).toInt().coerceIn(1, 200)
        r.warnIfNoFocus()
        for (chunk in resolved.chunked(chunkSize)) {
            r.runJs(
                """
                importClass(java.awt.event.KeyEvent);
                importClass(java.awt.event.InputEvent);
                // A plain java.awt.Robot: the assertj robot waits for an idle EDT after every press and release,
                // ~120 ms per shifted char. One wait at the end of the chunk keeps the order of later commands.
                var awt = global.get('iuc.awtRobot');
                if (awt === null) { awt = new java.awt.Robot(); global.put('iuc.awtRobot', awt); }
                var MODIFIERS = [
                    [InputEvent.SHIFT_DOWN_MASK | InputEvent.SHIFT_MASK, KeyEvent.VK_SHIFT],
                    [InputEvent.CTRL_DOWN_MASK | InputEvent.CTRL_MASK, KeyEvent.VK_CONTROL],
                    [InputEvent.ALT_DOWN_MASK | InputEvent.ALT_MASK, KeyEvent.VK_ALT],
                    [InputEvent.ALT_GRAPH_DOWN_MASK | InputEvent.ALT_GRAPH_MASK, KeyEvent.VK_ALT_GRAPH],
                    [InputEvent.META_DOWN_MASK | InputEvent.META_MASK, KeyEvent.VK_META],
                ];
                function press(code, modifiers) {
                    var held = [];
                    for (var m = 0; m < MODIFIERS.length; m++) {
                        if ((modifiers & MODIFIERS[m][0]) !== 0) { awt.keyPress(MODIFIERS[m][1]); held.push(MODIFIERS[m][1]); }
                    }
                    awt.keyPress(code);
                    awt.keyRelease(code);
                    for (var h = held.length - 1; h >= 0; h--) awt.keyRelease(held[h]);
                }
                var s = '${jsEscape(chunk)}';
                for (var i = 0; i < s.length; i++) {
                    var cc = s.charAt(i);
                    if (cc === '\n' || cc === '\r') press(KeyEvent.VK_ENTER, 0);
                    else if (cc === '\t') press(KeyEvent.VK_TAB, 0);
                    else {
                        var ks = org.assertj.swing.keystroke.KeyStrokeMap.keyStrokeFor(cc);
                        if (ks === null) robot.type(cc);
                        else press(ks.getKeyCode(), ks.getModifiers());
                    }
                    awt.delay($delayMs);
                }
                // Let the X server deliver the events, then let the EDT process them
                java.lang.Thread.sleep(50);
                java.awt.EventQueue.invokeAndWait(new java.lang.Runnable({ run: function() {} }));
                java.awt.EventQueue.invokeAndWait(new java.lang.Runnable({ run: function() {} }));
                """.trimIndent(),
                false,
            )
        }
        stateOption.printIfRequested(r)
        return 0
    }
}

// Modifier names people reach for that aren't VK_* constant names: there is no VK_CTRL, VK_CMD or VK_OPTION
private val MODIFIER_ALIASES = mapOf("CTRL" to "CONTROL", "CMD" to "META", "COMMAND" to "META", "OPTION" to "ALT")

private fun resolveVk(name: String): Int = try {
    java.awt.event.KeyEvent::class.java.getField("VK_${name.uppercase()}").getInt(null)
} catch (_: NoSuchFieldException) {
    error("Unknown key '$name'. Use a VK_* constant name without the VK_ prefix, e.g. S, ENTER, F5.")
}

@Command(
    name = "sendKeyCombo",
    description = [
        "Dispatch a modifier+key combination to the focused window via the AWT robot.",
        "Key is a VK_* constant without the VK_ prefix (case-insensitive). --modifier may be repeated.",
        "Examples: --key S --modifier META  (Cmd+S),  --key Z --modifier META --modifier SHIFT  (Cmd+Shift+Z).",
    ],
)
class SendKeyComboCommand : Callable<Int> {
    @Option(names = ["--key"], required = true, description = ["VK_* constant name without the VK_ prefix (e.g. S, Z, ENTER)."])
    lateinit var key: String
    @Option(names = ["--modifier"], description = ["Modifier key(s): META (or CMD), CONTROL (or CTRL), SHIFT, ALT (or OPTION). May be repeated."])
    var modifiers: List<String> = emptyList()
    @Mixin val stateOption = EditorStateOption()

    override fun call(): Int {
        val mainVk = resolveVk(key)
        // `--modifier CTRL,SHIFT` and `--modifier CTRL+SHIFT` mean the same as repeating --modifier
        val modVks = modifiers.flatMap { it.split(',', '+') }.filter { it.isNotBlank() }
            .map { resolveVk(MODIFIER_ALIASES[it.trim().uppercase()] ?: it.trim()) }
        val press = modVks.joinToString("\n") { "robot.pressKey($it);" }
        val release = modVks.reversed().joinToString("\n") { "robot.releaseKey($it);" }
        val r = connect()
        r.warnIfNoFocus()
        r.runJs(
            """
            $press
            robot.pressAndReleaseKey($mainVk);
            $release
            """.trimIndent(),
            false,
        )
        stateOption.printIfRequested(r)
        return 0
    }
}

// ---------- High-level flows ----------

@Command(
    name = "actionLinkClickByText",
    description = ["Click a link by its visible text (e.g. onboarding 'Skip'). Same as linkClick: also HTML links, entities decoded."],
)
class ActionLinkClickByTextCommand : Callable<Int> {
    @Option(names = ["--text"], required = true) lateinit var text: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        println(connect().clickLinkByText(text, timeoutSeconds))
        return 0
    }
}

@Command(
    name = "projectCreateFromDialog",
    description = ["Open the New Project wizard from the Welcome frame, fill name + location, click Create, wait for IdeFrame."],
)
class ProjectCreateFromDialogCommand : Callable<Int> {
    @Option(names = ["--name"], required = true) lateinit var name: String
    @Option(names = ["--location"], description = ["Project directory. If omitted, the wizard's default is kept."])
    var location: String? = null
    @Option(names = ["--timeout-seconds"], description = ["How long to wait for the IdeFrame after clicking Create."])
    var timeoutSeconds: Long = 120

    override fun call(): Int {
        val r = connect()
        r.welcomeFrame().find(
            ComponentFixture::class.java,
            xp("//div[(@class='MainButton' and @text='New Project') or (@accessiblename='New Project' and @class='JButton')]"),
            Duration.ofSeconds(10),
        ).click()
        r.find(
            ContainerFixture::class.java,
            xp("//div[@title='New Project' and @class='MyDialog']"),
            Duration.ofSeconds(30),
        )
        r.setFieldByLabel("Name:", name)
        location?.let { r.setFieldByLabel("Location:", it) }
        r.find(
            ContainerFixture::class.java,
            xp("//div[@title='New Project' and @class='MyDialog']"),
            Duration.ofSeconds(5),
        ).find(
            ComponentFixture::class.java,
            xp(".//div[(@text='Create' or @accessiblename='Create') and (@class='JButton' or @class='JBOptionButton')]"),
            Duration.ofSeconds(10),
        ).click()
        r.ideFrame(Duration.ofSeconds(timeoutSeconds))
        println("Project '$name' created.")
        return 0
    }
}

@Command(name = "createScratchFile", description = ["Create a scratch file in the current project and open it in an editor."])
class CreateScratchFileCommand : Callable<Int> {
    @Option(names = ["--name"], required = true, description = ["File name including extension, e.g. scratch.kt"])
    lateinit var name: String
    @Option(names = ["--language"], description = ["IntelliJ Language ID (e.g. 'kotlin', 'JAVA'). Default: plain text."])
    var language: String = "TEXT"
    @Option(names = ["--content"], description = ["Initial text. Interprets \\n, \\t, \\r, \\\\ escapes."])
    var content: String = ""

    override fun call(): Int {
        val r = connect()
        val frame = r.ideFrame()
        val escName = jsEscape(name)
        val escContent = jsEscape(interpretEscapes(content))
        val escLang = jsEscape(language)
        r.runJs(
            frame,
            """
            importClass(com.intellij.ide.scratch.ScratchRootType);
            importClass(com.intellij.openapi.fileEditor.FileEditorManager);
            importClass(com.intellij.lang.Language);
            importClass(com.intellij.openapi.fileTypes.PlainTextLanguage);
            importClass(com.intellij.openapi.application.ApplicationManager);
            importClass(com.intellij.openapi.application.WriteAction);
            var project = component.getProject();
            if (project === null) throw String("no active project; open one first");
            ApplicationManager.getApplication().invokeAndWait(function() {
                WriteAction.run(function() {
                    var lang = Language.findLanguageByID('$escLang');
                    if (lang === null) lang = PlainTextLanguage.INSTANCE;
                    var file = ScratchRootType.getInstance().createScratchFile(project, '$escName', lang, '$escContent');
                    if (file !== null) FileEditorManager.getInstance(project).openFile(file, true);
                });
            });
            """.trimIndent(),
            false,
        )
        println("Scratch '$name' created.")
        return 0
    }
}
