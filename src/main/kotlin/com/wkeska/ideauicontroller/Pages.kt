package com.wkeska.ideauicontroller

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.Callable

private fun xp(expr: String) = byXpath(expr)

private fun RemoteRobot.welcomeFrame(timeout: Duration = Duration.ofSeconds(10)): ContainerFixture =
    find(ContainerFixture::class.java, xp("//div[@class='FlatWelcomeFrame']"), timeout)

private fun RemoteRobot.ideFrame(timeout: Duration = Duration.ofSeconds(60)): ContainerFixture =
    find(ContainerFixture::class.java, xp("//div[@class='IdeFrameImpl']"), timeout)

private fun RemoteRobot.editor(file: String?, timeout: Duration = Duration.ofSeconds(30)): ComponentFixture {
    if (file != null) {
        val tab = find(
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
    return find(ComponentFixture::class.java, loc, timeout)
}

private fun clickFixture(f: ComponentFixture) = f.click()

// ---------- Generic JS ----------

@Command(name = "runJs", description = ["Run an arbitrary robot runJs script (no return value)."])
class RunJsCommand : Callable<Int> {
    @Option(names = ["--script", "-s"], required = true, description = ["The JS script body."])
    lateinit var script: String

    @Option(names = ["--edt"], description = ["Run on the EDT."])
    var edt: Boolean = false

    override fun call(): Int {
        connect().runJs(script, edt)
        return 0
    }
}

@Command(name = "callJs", description = ["Call an arbitrary robot callJs script and print the result."])
class CallJsCommand : Callable<Int> {
    @Option(names = ["--script", "-s"], required = true, description = ["The JS script body."])
    lateinit var script: String

    @Option(names = ["--edt"], description = ["Run on the EDT."])
    var edt: Boolean = false

    override fun call(): Int {
        val r = connect().callJs<Serializable>(script, edt)
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
    @Option(names = ["--file"], description = ["Editor tab title; if omitted, first EditorComponentImpl is used."])
    var file: String? = null

    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 30
}

@Command(name = "editorVerify", description = ["Verify an editor is open."])
class EditorVerifyCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        connect().editor(file, Duration.ofSeconds(timeoutSeconds))
        println("Editor${file?.let { " for '$it'" } ?: ""} present.")
        return 0
    }
}

@Command(name = "editorOpen", description = ["Click the editor tab matching --file to bring it to front."])
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
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val text = r.callJs<String>(e, "component.getEditor().getDocument().getText()", true)
        print(text)
        return 0
    }
}

@Command(name = "editorGetSelectedText", description = ["Print the editor's currently selected text."])
class EditorGetSelectedTextCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val text = r.callJs<String>(
            e,
            "(component.getEditor().getSelectionModel().getSelectedText() || '')",
            true,
        )
        print(text)
        return 0
    }
}

@Command(name = "editorCaretOffset", description = ["Print the primary caret offset."])
class EditorCaretOffsetCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val v = r.callJs<Int>(e, "component.getEditor().getCaretModel().getOffset()", true)
        println(v)
        return 0
    }
}

@Command(name = "editorCaretCount", description = ["Print the number of carets."])
class EditorCaretCountCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val v = r.callJs<Int>(e, "component.getEditor().getCaretModel().getCaretCount()", true)
        println(v)
        return 0
    }
}

@Command(name = "editorIsBlockCursor", description = ["Print true/false for block/underscore caret (IdeaVim)."])
class EditorIsBlockCursorCommand : EditorScopedOptions(), Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val v = r.callJs<Boolean>(
            e,
            "com.maddyhome.idea.vim.helper.CaretVisualAttributesHelperKt.hasBlockOrUnderscoreCaret(component.getEditor())",
            true,
        )
        println(v)
        return 0
    }
}

@Command(name = "editorInjectText", description = ["Replace editor document text via a write action."])
class EditorInjectTextCommand : EditorScopedOptions(), Callable<Int> {
    @Option(names = ["--text", "-t"], required = true) lateinit var text: String
    override fun call(): Int {
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        r.runJs(
            e,
            """
            importClass(com.intellij.openapi.application.ApplicationManager);
            importClass(com.intellij.openapi.application.WriteAction);
            const editor = component.getEditor();
            ApplicationManager.getApplication().invokeAndWait(function() {
                WriteAction.run(function() {
                    editor.getDocument().setText('$escaped');
                });
            });
            """.trimIndent(),
            false,
        )
        return 0
    }
}

@Command(name = "editorMoveToLine", description = ["Scroll to and place the caret at a 1-based line."])
class EditorMoveToLineCommand : EditorScopedOptions(), Callable<Int> {
    @Option(names = ["--line"], required = true) var line: Int = 0
    override fun call(): Int {
        require(line >= 1) { "--line must be >= 1" }
        val r = connect()
        val e = r.editor(file, Duration.ofSeconds(timeoutSeconds))
        r.runJs(
            e,
            """
            importClass(com.intellij.openapi.editor.ScrollType);
            const editor = component.getEditor();
            const document = editor.getDocument();
            const offset = document.getLineStartOffset(${line - 1});
            editor.getCaretModel().moveToOffset(offset);
            editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(offset), ScrollType.CENTER);
            """.trimIndent(),
            true,
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

@Command(name = "ideIsSmart", description = ["Print true if the IDE is in smart mode (not dumb)."])
class IdeIsSmartCommand : Callable<Int> {
    override fun call(): Int {
        val r = connect()
        val frame = r.ideFrame()
        val smart = r.callJs<Boolean>(
            frame,
            "component.project == null ? false : !com.intellij.openapi.project.DumbService.isDumb(component.project);",
            true,
        )
        println(smart)
        return 0
    }
}

@Command(name = "ideWaitSmart", description = ["Block until the IDE is in smart mode."])
class IdeWaitSmartCommand : Callable<Int> {
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 300
    override fun call(): Int {
        val r = connect()
        val frame = r.ideFrame()
        waitFor(Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(5)) {
            runCatching {
                r.callJs<Boolean>(
                    frame,
                    "component.project == null ? false : !com.intellij.openapi.project.DumbService.isDumb(component.project);",
                    true,
                )
            }.getOrDefault(false)
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

private fun jsEscape(s: String) = s
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

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
            if (idx < 0) throw new java.lang.RuntimeException("no list item matching '$esc'");
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
        if (found === null) throw new java.lang.RuntimeException("no JLabel with text='$escLabel'");
        var target = found.getLabelFor();
        if (target === null) throw new java.lang.RuntimeException("JLabel '$escLabel' has no labelFor");
        if (!(target instanceof JTextComponent)) throw new java.lang.RuntimeException("labelFor is not a text component");
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
        val current = r.callJs<Boolean>(cb, "component.isSelected()", true)
        if (current != checked) cb.click()
        return 0
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

// ---------- High-level flows ----------

@Command(name = "actionLinkClickByText", description = ["Click an ActionLink by its visible text (e.g. onboarding 'Skip')."])
class ActionLinkClickByTextCommand : Callable<Int> {
    @Option(names = ["--text"], required = true) lateinit var text: String
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        val r = connect()
        val f = r.find(
            ComponentFixture::class.java,
            xp("//div[@class='ActionLink' and (@text='$text' or @visible_text='$text' or @accessiblename='$text')]"),
            Duration.ofSeconds(timeoutSeconds),
        )
        r.runJs(
            f,
            """
            importClass(java.awt.Point);
            importClass(org.assertj.swing.core.MouseButton);
            var offset = component.getHeight() / 2;
            robot.click(component, new Point(offset, offset), MouseButton.LEFT_BUTTON, 1);
            """.trimIndent(),
            false,
        )
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
    @Option(names = ["--content"], description = ["Initial text."])
    var content: String = ""

    override fun call(): Int {
        val r = connect()
        val frame = r.ideFrame()
        val escName = jsEscape(name)
        val escContent = jsEscape(content)
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
            if (project === null) throw new java.lang.RuntimeException("no active project; open one first");
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
