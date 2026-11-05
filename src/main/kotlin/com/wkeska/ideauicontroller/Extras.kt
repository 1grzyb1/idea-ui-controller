package com.wkeska.ideauicontroller

import com.intellij.remoterobot.RemoteRobot
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

// ---------- HTML entities ----------

// The component tree prints non-ASCII characters as HTML entities (`&larr; Back`, `Configure&hellip;`), while the
// components themselves hold the plain characters, so text and xpaths copied from getState have to be decoded first.
private val HTML_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "hellip" to "…", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
    "lArr" to "⇐", "rArr" to "⇒", "mdash" to "—", "ndash" to "–", "minus" to "−",
    "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "bull" to "•", "middot" to "·", "times" to "×", "copy" to "©", "reg" to "®", "trade" to "™",
    "deg" to "°", "plusmn" to "±", "sect" to "§", "euro" to "€", "thinsp" to " ", "ensp" to " ",
    "emsp" to " ", "zwnj" to "‌", "zwj" to "‍",
)

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);")

internal fun decodeHtmlEntities(s: String): String = ENTITY.replace(s) { m ->
    val name = m.groupValues[1]
    when {
        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
        else -> HTML_ENTITIES[name]
    } ?: m.value
}

// ---------- Script prelude ----------

private val JS_ENTITIES = HTML_ENTITIES.entries.joinToString(", ") { (k, v) -> "'$k': '${jsEscape(v)}'" }

/**
 * Helpers prepended to every `runJs` / `callJs` script. [targetEditor] is the focused editor, else the selected text
 * editor of [targetProject], the project of the active window.
 *
 * Bare Rhino package access (`com.example.Foo`) resolves classes through the robot-server's class loader, which sees
 * the platform but not other plugins: a plugin class then turns into a `JavaPackage` ("... is not a function").
 */
internal val JS_PRELUDE = """
    var __iucScope = this;
    function loadPluginClass(pluginId, fqn) {
        var PMC = com.intellij.ide.plugins.PluginManagerCore;
        var id = com.intellij.openapi.extensions.PluginId.getId(pluginId);
        var main = PMC.getPlugin(id);
        if (main === null) throw String("No plugin with id '" + pluginId + "'");
        var loaders = [main.getPluginClassLoader()];
        var modules = PMC.getPluginSet().getEnabledModules();
        for (var i = 0; i < modules.size(); i++) {
            var m = modules.get(i);
            if (m.getPluginId().equals(id) && m.getPluginClassLoader() !== null) loaders.push(m.getPluginClassLoader());
        }
        for (var j = 0; j < loaders.length; j++) {
            try { return java.lang.Class.forName(fqn, true, loaders[j]); } catch (e) {}
        }
        throw String("Class " + fqn + " not found in plugin '" + pluginId + "' (" +
            loaders.length + " class loaders searched; a file with @file:JvmName has no Kt suffix)");
    }
    function pluginClass(pluginId, fqn) {
        return new org.mozilla.javascript.NativeJavaClass(__iucScope, loadPluginClass(pluginId, fqn));
    }
    var __iucEntities = { $JS_ENTITIES };
    function normText(s) {
        if (s === null || s === undefined) return '';
        s = String(s).replace(/<[^>]*>/g, ' ');
        s = s.replace(/&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);/g, function(all, n) {
            if (n.charAt(0) === '#') {
                var code = (n.charAt(1) === 'x' || n.charAt(1) === 'X') ? parseInt(n.substring(2), 16) : parseInt(n.substring(1), 10);
                return isNaN(code) ? all : String.fromCharCode(code);
            }
            var v = __iucEntities[n];
            return v === undefined ? all : v;
        });
        return s.replace(/[\s ]+/g, ' ').replace(/^ | $/g, '');
    }
    function focusOwner() {
        return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }
    function targetProject() {
        var p = null;
        try { p = com.intellij.ide.impl.ProjectUtil.getActiveProject(); } catch (e) {}
        if (p !== null) return p;
        var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
        return ps.length > 0 ? ps[ps.length - 1] : null;
    }
    function targetEditor() {
        var f = focusOwner();
        if (f instanceof com.intellij.openapi.editor.impl.EditorComponentImpl) return f.getEditor();
        var p = targetProject();
        return p === null ? null : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).getSelectedTextEditor();
    }
    function onEdt(fn) {
        var result = null;
        var failure = null;
        var r = new java.lang.Runnable({ run: function() { try { result = fn(); } catch (e) { failure = e; } } });
        if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run(); else javax.swing.SwingUtilities.invokeAndWait(r);
        if (failure !== null) throw failure;
        return result;
    }
""".trimIndent()

// On the script's first line, so line numbers in error messages stay the script's own
private val JS_PRELUDE_LINE = JS_PRELUDE.lines().joinToString(" ") { it.trim() }

internal fun withPrelude(script: String) = "$JS_PRELUDE_LINE $script"

// ---------- Hyperlinks ----------

// Collects every link in the showing windows, the active window first: <a> elements of HTML JEditorPanes, ActionLinks,
// LinkLabels and HyperlinkLabels. Then clicks the one whose text matches exactly, or else the only one containing it.
private fun linkClickScript(text: String, hrefOnly: Boolean) = """
    importClass(java.awt.Point);
    importClass(org.assertj.swing.core.MouseButton);
    var HTML = javax.swing.text.html.HTML;
    var target = normText('${jsEscape(text)}');
    var links = [];
    function isLinkComponent(c) {
        var n = String(c.getClass().getName());
        return c instanceof com.intellij.ui.components.ActionLink || c instanceof com.intellij.ui.components.labels.LinkLabel ||
            c instanceof com.intellij.ui.HyperlinkLabel || n.indexOf('Link') >= 0 && c instanceof javax.swing.AbstractButton;
    }
    function collectAnchors(pane) {
        var doc = pane.getDocument();
        var it = new javax.swing.text.ElementIterator(doc);
        var el, cur = null;
        while ((el = it.next()) !== null) {
            if (!el.isLeaf()) continue;
            var a = el.getAttributes().getAttribute(HTML.Tag.A);
            if (a === null) { cur = null; continue; }
            var href = a.getAttribute(HTML.Attribute.HREF);
            href = href === null ? '' : String(href);
            if (cur !== null && cur.end === el.getStartOffset() && cur.href === href) { cur.end = el.getEndOffset(); continue; }
            cur = { comp: pane, kind: 'html', start: el.getStartOffset(), end: el.getEndOffset(), href: href };
            links.push(cur);
        }
        for (var i = 0; i < links.length; i++) {
            var l = links[i];
            if (l.comp === pane && l.text === undefined) l.text = normText(doc.getText(l.start, l.end - l.start));
        }
    }
    function visit(c) {
        if (!c.isShowing()) return;
        if (c instanceof javax.swing.JEditorPane && c.getDocument() instanceof javax.swing.text.html.HTMLDocument) {
            collectAnchors(c);
        } else if (isLinkComponent(c)) {
            var t = normText(c.getText());
            var ac = c.getAccessibleContext();
            var acc = ac === null ? '' : normText(ac.getAccessibleName());
            links.push({ comp: c, kind: 'component', text: t.length > 0 ? t : acc, alt: acc, href: '' });
        }
        if (c instanceof java.awt.Container) {
            var children = c.getComponents();
            for (var i = 0; i < children.length; i++) visit(children[i]);
        }
    }
    var kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
    var windows = [];
    if (kfm.getActiveWindow() !== null) windows.push(kfm.getActiveWindow());
    var all = java.awt.Window.getWindows();
    for (var i = 0; i < all.length; i++) if (all[i] !== kfm.getActiveWindow()) windows.push(all[i]);
    for (var i = 0; i < windows.length; i++) if (windows[i].isShowing()) visit(windows[i]);

    var match = null;
    for (var i = 0; i < links.length && match === null; i++) {
        if (links[i].text === target || links[i].alt === target) match = links[i];
    }
    if (match === null) {
        var partial = [];
        var lower = target.toLowerCase();
        for (var i = 0; i < links.length; i++) if (links[i].text.toLowerCase().indexOf(lower) >= 0) partial.push(links[i]);
        if (partial.length === 1) match = partial[0];
    }
    if (match === null) {
        var names = [];
        for (var i = 0; i < links.length; i++) names.push("'" + links[i].text + "'");
        'NO_MATCH: links on screen: [' + names.join(', ') + ']';
    } else {
        var cls = String(match.comp.getClass().getName());
        var desc = (match.kind === 'html' ? 'HTML link' : cls.substring(cls.lastIndexOf('.') + 1)) + " '" + match.text + "'" +
            (match.href.length > 0 ? ' href=' + match.href : '');
        if (!$hrefOnly) {
            if (match.kind === 'html') {
                var r = match.comp.modelToView2D(Math.floor((match.start + match.end) / 2));
                robot.click(match.comp, new Point(Math.round(r.getCenterX()), Math.round(r.getCenterY())), MouseButton.LEFT_BUTTON, 1);
            } else if (match.comp instanceof javax.swing.AbstractButton) {
                robot.click(match.comp);
            } else {
                // HyperlinkLabel / LinkLabel: the link part isn't at a fixed position; doClick may open a modal, so invokeLater
                var comp = match.comp;
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                    new java.lang.Runnable({ run: function() { comp.doClick(); } }),
                    com.intellij.openapi.application.ModalityState.any());
            }
        }
        (${hrefOnly} ? 'Found: ' : 'Clicked: ') + desc;
    }
""".trimIndent()

internal fun RemoteRobot.clickLinkByText(text: String, timeoutSeconds: Long, hrefOnly: Boolean = false): String {
    val script = withPrelude(linkClickScript(decodeHtmlEntities(text), hrefOnly))
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    while (true) {
        val result = callJs<String>(script, false)
        if (!result.startsWith("NO_MATCH")) return result
        if (System.currentTimeMillis() >= deadline) error("No link '$text'. ${result.removePrefix("NO_MATCH: ")}")
        Thread.sleep(500)
    }
}

@Command(
    name = "linkClick",
    description = [
        "Click a hyperlink by its text: an <a> inside an HTML JEditorPane / label, an ActionLink, LinkLabel or HyperlinkLabel.",
        "Text matches after stripping tags, decoding HTML entities (&larr;, &hellip;) and collapsing whitespace;",
        "exact match first, else the only link containing it (case-insensitive). The active window is searched first.",
    ],
)
class LinkClickCommand : Callable<Int> {
    @Option(names = ["--text"], required = true, description = ["Link text, e.g. 'copy the link' or '&larr; Back'."])
    lateinit var text: String

    @Option(names = ["--href"], description = ["Don't click; only print the link (with its href for HTML links)."])
    var hrefOnly: Boolean = false

    @Option(names = ["--timeout-seconds"], description = ["How long to wait for the link (default: 10)."])
    var timeoutSeconds: Long = 10

    override fun call(): Int {
        println(connect().clickLinkByText(text, timeoutSeconds, hrefOnly))
        return 0
    }
}

// ---------- Clipboard and focused component ----------

@Command(name = "clipboard", description = ["Print the IDE's clipboard text (CopyPasteManager), or set it with --set."])
class ClipboardCommand : Callable<Int> {
    @Option(names = ["--set"], description = ["Put this text into the IDE clipboard instead of printing it."])
    var set: String? = null

    override fun call(): Int {
        val r = connect()
        val value = set
        if (value != null) {
            r.runJs(
                withPrelude(
                    "com.intellij.openapi.ide.CopyPasteManager.getInstance()" +
                        ".setContents(new java.awt.datatransfer.StringSelection('${jsEscape(value)}'));",
                ),
                false,
            )
            return 0
        }
        val text = r.callJs<String>(
            """
            var t = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
            t === null ? '' : String(t);
            """.trimIndent(),
            false,
        )
        println(text)
        return 0
    }
}

@Command(
    name = "focusedComponent",
    description = [
        "Describe the keyboard focus owner: class, text (JTextComponent / label / button), accessible name,",
        "empty text, and all its client properties (e.g. JComponent.outline=error or a validation message).",
    ],
)
class FocusedComponentCommand : Callable<Int> {
    override fun call(): Int {
        val out = connect().callJs<String>(
            withPrelude(
                """
                var c = focusOwner();
                if (c === null) 'Focus owner: <none>';
                else onEdt(function() {
                    var lines = ['Focus owner: ' + c.getClass().getName()];
                    var w = javax.swing.SwingUtilities.getWindowAncestor(c);
                    if (w !== null) lines.push('Window: ' + (w.getTitle ? "'" + w.getTitle() + "' " : '') + '(' + w.getClass().getSimpleName() + ')');
                    if (c.getText) { try { lines.push('Text: ' + c.getText()); } catch (e) {} }
                    var ac = c.getAccessibleContext();
                    if (ac !== null && ac.getAccessibleName() !== null) lines.push('Accessible name: ' + ac.getAccessibleName());
                    if (c.getEmptyText) { try { lines.push('Empty text: ' + c.getEmptyText().getText()); } catch (e) {} }
                    if (c.getToolTipText && c.getToolTipText() !== null) lines.push('Tooltip: ' + c.getToolTipText());
                    if (c instanceof javax.swing.JComponent) {
                        // JComponent keeps client properties in a private ArrayTable with no public way to list the keys
                        try {
                            var f = java.lang.Class.forName('javax.swing.JComponent').getDeclaredField('clientProperties');
                            f.setAccessible(true);
                            var table = f.get(c);
                            if (table !== null) {
                                var getKeys = table.getClass().getDeclaredMethod('getKeys', java.lang.Class.forName('[Ljava.lang.Object;'));
                                getKeys.setAccessible(true);
                                var keys = getKeys.invoke(table, [null]);
                                for (var i = 0; i < keys.length; i++) lines.push('Client property ' + keys[i] + ' = ' + c.getClientProperty(keys[i]));
                            }
                        } catch (e) { lines.push('Client properties: <unavailable: ' + e + '>'); }
                    }
                    return lines.join('\n');
                });
                """.trimIndent(),
            ),
            false,
        )
        println(out)
        return 0
    }
}

// ---------- Action tracing ----------

// Rhino's `new Interface({...})` (JavaAdapter) only overrides abstract methods. AnActionListener has only default
// methods, so a JS listener built that way is never called; a java.lang.reflect.Proxy routes every method to JS.
private val TRACE_START_JS = """
    if (global.get('iuc.actions.connection') !== null) 'Already tracing.';
    else {
        var log = new java.util.concurrent.CopyOnWriteArrayList();
        var am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
        var iface = java.lang.Class.forName('com.intellij.openapi.actionSystem.ex.AnActionListener');
        function actionName(action) {
            var id = am.getId(action);
            return id === null ? String(action.getClass().getName()) : String(id);
        }
        function describe(action, event) {
            var input = event.getInputEvent();
            var how = input === null ? '' : input instanceof java.awt.event.KeyEvent
                ? '  key=' + javax.swing.KeyStroke.getKeyStrokeForEvent(input)
                : '  input=' + input.getClass().getSimpleName();
            return actionName(action) + '  place=' + event.getPlace() + how;
        }
        var handler = new java.lang.reflect.InvocationHandler({ invoke: function(proxy, method, args) {
            var name = String(method.getName());
            if (name === 'hashCode') return java.lang.Integer.valueOf(java.lang.System.identityHashCode(proxy));
            if (name === 'equals') return java.lang.Boolean.valueOf(proxy === args[0]);
            if (name === 'toString') return 'idea-ui-controller action tracer';
            var time = new java.text.SimpleDateFormat('HH:mm:ss.SSS').format(new java.util.Date());
            if (name === 'beforeActionPerformed' && args.length === 2) log.add(time + ' before ' + describe(args[0], args[1]));
            else if (name === 'afterActionPerformed' && args.length === 3) log.add(time + ' after  ' + describe(args[0], args[1]) + '  result=' + (args[2].isPerformed() ? 'performed' : 'ignored'));
            else if (name === 'beforeShortcutTriggered') {
                var ids = [];
                for (var i = 0; i < args[1].size(); i++) ids.push(actionName(args[1].get(i)));
                log.add(time + ' shortcut ' + args[0] + ' -> [' + ids.join(', ') + ']');
            }
            return null;
        } });
        var listener = java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), [iface], handler);
        var connection = com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect();
        connection.subscribe(com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC, listener);
        global.put('iuc.actions.log', log);
        global.put('iuc.actions.connection', connection);
        'Tracing actions.';
    }
""".trimIndent()

@Command(
    name = "traceActions",
    description = [
        "Record the actions the IDE executes (AnActionListener): --start, then reproduce, then --dump (prints and clears) and --stop.",
        "The recorder lives in the IDE until --stop or the IDE exits.",
    ],
)
class TraceActionsCommand : Callable<Int> {
    @Option(names = ["--start"], description = ["Subscribe the recorder."]) var start: Boolean = false
    @Option(names = ["--dump"], description = ["Print recorded actions and clear the buffer."]) var dump: Boolean = false
    @Option(names = ["--stop"], description = ["Print what's left and unsubscribe."]) var stop: Boolean = false

    override fun call(): Int {
        require(listOf(start, dump, stop).count { it } == 1) { "Pass exactly one of --start, --dump, --stop" }
        val r = connect()
        if (start) {
            println(r.callJs<String>(TRACE_START_JS, false))
            return 0
        }
        val out = r.callJs<String>(
            """
            var log = global.get('iuc.actions.log');
            if (log === null) 'NOT_TRACING';
            else {
                var lines = [];
                for (var i = 0; i < log.size(); i++) lines.push(log.get(i));
                log.clear();
                if (${stop}) {
                    global.get('iuc.actions.connection').disconnect();
                    global.remove('iuc.actions.connection');
                    global.remove('iuc.actions.log');
                }
                lines.join('\n');
            }
            """.trimIndent(),
            false,
        )
        if (out == "NOT_TRACING") {
            System.err.println("Not tracing. Run `traceActions --start` first.")
            return 1
        }
        if (out.isNotEmpty()) println(out)
        return 0
    }
}

// ---------- JetBrains Account login (EAP) ----------

private const val LOGIN_DIALOG_XPATH = "//div[@class='MyDialog' and starts-with(@title, 'Log in to')]"

@Command(
    name = "jbaLogin",
    description = [
        "Log a fresh EAP sandbox into JetBrains Account through the modal 'Log in to ...' dialog, in two steps.",
        "1) Without a token: clicks 'Log in to JetBrains Account' and 'Troubles?', prints the OAuth URL.",
        "   Open it in a browser on the host, log in and copy the 'IDE authorization token'.",
        "2) With --token / --token-file / --token -: puts the token into the field, clicks 'Check Token' ONCE and then 'Get Started'.",
        "The URL and the token belong to this dialog: after stopIde start again from step 1. A rejected token is burnt.",
    ],
)
class JbaLoginCommand : Callable<Int> {
    @Option(names = ["--token"], description = ["The IDE authorization token; '-' reads it from stdin."])
    var token: String? = null

    @Option(names = ["--token-file"], description = ["File with the IDE authorization token."])
    var tokenFile: Path? = null

    @Option(names = ["--timeout-seconds"], description = ["How long to wait for each dialog step (default: 30)."])
    var timeoutSeconds: Long = 30

    override fun call(): Int {
        val r = connect()
        check(r.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(LOGIN_DIALOG_XPATH)).isNotEmpty()) {
            "No 'Log in to ...' dialog is showing."
        }
        val value = when {
            tokenFile != null -> Files.readString(tokenFile!!)
            token == "-" -> System.`in`.bufferedReader().readText()
            else -> token
        }?.trim()
        return if (value == null) printLoginUrl(r) else submitToken(r, value)
    }

    private fun printLoginUrl(r: RemoteRobot): Int {
        val loginButton = "//div[@class='MyDialog']//div[@class='JButton' and @accessiblename='Log in to JetBrains Account']"
        if (r.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(loginButton)).isNotEmpty()) {
            r.find(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(loginButton)).click()
        }
        // Without a browser in the container the dialog waits for one; "Troubles?" shows the token field and the URL
        if (r.callJs<String>(withPrelude(LINK_PRESENT_JS.replace("TEXT", "copy the link")), false) != "yes") {
            r.clickLinkByText("Troubles?", timeoutSeconds)
        }
        val found = r.clickLinkByText("copy the link", timeoutSeconds, hrefOnly = true)
        val url = found.substringAfter(" href=", "").replace(" ", "%20")
        check(url.startsWith("http")) { "The 'copy the link' link has no URL: $found" }
        println(url)
        System.err.println(
            "Open the URL in a browser, log in, copy the 'IDE authorization token' and run " +
                "`jbaLogin --token-file FILE` (or `--token -` from stdin). Don't restart the IDE in between.",
        )
        return 0
    }

    private fun submitToken(r: RemoteRobot, value: String): Int {
        require(value.isNotEmpty()) { "Empty token" }
        // Clear the error of an earlier attempt, so only this attempt's validation result counts
        val set = r.callJs<String>(
            withPrelude(
                """
                $TOKEN_FIELD_JS
                if (field === null) 'NO_FIELD';
                else onEdt(function() {
                    field.putClientProperty('JComponent.outline', null);
                    field.putClientProperty('Error.text', null);
                    field.setText('${jsEscape(value)}');
                    field.requestFocusInWindow();
                    return 'OK';
                });
                """.trimIndent(),
            ),
            false,
        )
        check(set == "OK") { "No token field in the login dialog. Run `jbaLogin` without a token first." }

        val checkButton = "//div[@class='MyDialog']//div[@class='JButton' and @accessiblename='Check Token']"
        r.find(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(checkButton), java.time.Duration.ofSeconds(timeoutSeconds)).click()
        println("Clicked 'Check Token' (once - don't repeat it with the same token).")

        val getStarted = "//div[@class='MyDialog']//div[@class='JButton' and @accessiblename='Get Started']"
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (r.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(LOGIN_DIALOG_XPATH)).isEmpty()) {
                println("Logged in: the login dialog is closed.")
                return 0
            }
            val buttons = r.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, xp(getStarted))
            if (buttons.isNotEmpty()) {
                buttons.first().click()
                // Nothing has focus once the modal is gone, so keys would go nowhere
                r.runJs(
                    """
                    var frames = java.awt.Frame.getFrames();
                    for (var i = 0; i < frames.length; i++) {
                        if (frames[i].isShowing() && String(frames[i].getClass().getSimpleName()) === 'IdeFrameImpl') { frames[i].toFront(); frames[i].requestFocus(); break; }
                    }
                    """.trimIndent(),
                    true,
                )
                println("Logged in: clicked 'Get Started'.")
                return 0
            }
            val error = r.callJs<String>(
                "$TOKEN_FIELD_JS\nfield === null || String(field.getClientProperty('JComponent.outline')) !== 'error' ? '' : String(field.getClientProperty('Error.text'));",
                false,
            )
            if (error.isNotEmpty()) {
                System.err.println("Token rejected: $error. A token works once: run `jbaLogin` again for a new URL (after stopIde + runIde if it keeps failing).")
                return 1
            }
            Thread.sleep(1000)
        }
        val texts = r.callJs<String>(LOGIN_DIALOG_TEXTS_JS, false)
        System.err.println("No 'Get Started' after ${timeoutSeconds}s. Dialog text:\n$texts")
        return 1
    }
}

private const val TOKEN_FIELD_JS = """
    var field = null;
    function findTokenField(c) {
        if (field !== null || !c.isShowing()) return;
        if (c instanceof com.intellij.ui.components.JBTextField && String(c.getEmptyText().getText()).indexOf('token') >= 0) { field = c; return; }
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) findTokenField(ch[i]); }
    }
    var loginWindows = java.awt.Window.getWindows();
    for (var i = 0; i < loginWindows.length; i++) {
        if (loginWindows[i] instanceof javax.swing.JDialog && String(loginWindows[i].getTitle()).indexOf('Log in to') === 0) findTokenField(loginWindows[i]);
    }
"""

private const val LINK_PRESENT_JS = """
    var found = 'no';
    function find(c) {
        if (found === 'yes' || !c.isShowing()) return;
        if (c instanceof javax.swing.JEditorPane && normText(c.getText()).indexOf('TEXT') >= 0) { found = 'yes'; return; }
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) find(ch[i]); }
    }
    var ws = java.awt.Window.getWindows();
    for (var i = 0; i < ws.length; i++) find(ws[i]);
    found;
"""

private const val LOGIN_DIALOG_TEXTS_JS = """
    var texts = [];
    function collect(c) {
        if (!c.isShowing()) return;
        if (c instanceof javax.swing.JLabel || c instanceof javax.swing.JEditorPane) {
            var t = String(c.getText() || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
            if (t.length > 0) texts.push(t);
        }
        if (c instanceof javax.swing.JComponent && c.getClientProperty('JComponent.outline') !== null) texts.push('[' + c.getClass().getSimpleName() + ' outline=' + c.getClientProperty('JComponent.outline') + ']');
        if (c instanceof java.awt.Container) { var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) collect(ch[i]); }
    }
    var ws = java.awt.Window.getWindows();
    for (var i = 0; i < ws.length; i++) if (ws[i] instanceof javax.swing.JDialog && String(ws[i].getTitle()).indexOf('Log in to') === 0) collect(ws[i]);
    texts.join('\n');
"""

// ---------- Projects and files ----------

@Command(
    name = "openFile",
    description = [
        "Open a file in the active project's editor and focus it. A relative PATH is resolved against the project dir.",
        "--line/--column (1-based) place the caret.",
    ],
)
class OpenFileCommand : Callable<Int> {
    @picocli.CommandLine.Parameters(index = "0", paramLabel = "PATH") lateinit var path: String
    @Option(names = ["--line"]) var line: Int? = null
    @Option(names = ["--column"]) var column: Int = 1
    @Option(names = ["--state"], description = ["Afterwards print editorState."]) var state: Boolean = false

    override fun call(): Int {
        val r = connect()
        println(r.openFile(path, line, column))
        if (state) println(r.editorState(null, java.time.Duration.ofSeconds(5), 400, null))
        return 0
    }
}

internal fun RemoteRobot.openFile(path: String, line: Int?, column: Int): String = callJs(
    withPrelude(
        """
        var p = targetProject();
        if (p === null) throw 'No project is open';
        var path = '${jsEscape(path)}';
        if (path.charAt(0) !== '/') path = p.getBasePath() + '/' + path;
        var f = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
        if (f === null) throw 'No such file: ' + path;
        var d = ${if (line != null) "new com.intellij.openapi.fileEditor.OpenFileDescriptor(p, f, ${line - 1}, ${column - 1})" else "new com.intellij.openapi.fileEditor.OpenFileDescriptor(p, f)"};
        var e = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).openTextEditor(d, true);
        if (e === null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).openFile(f, true);
        'Opened ' + path + (e === null ? ' (not a text editor)' : '');
        """.trimIndent(),
    ),
    true,
)

@Command(
    name = "openProject",
    description = [
        "Open a project directory (or .sln, pom.xml, ...) in a new frame, wait until it's open and indexed, optionally open --file.",
        "runIde trusts all projects, so there is no trust dialog. With --docker, PATH has to be --mount'ed.",
    ],
)
class OpenProjectCommand : Callable<Int> {
    @picocli.CommandLine.Parameters(index = "0", paramLabel = "PATH") lateinit var path: Path
    @Option(names = ["--file"], description = ["File to open afterwards, relative to the project dir or absolute."]) var file: String? = null
    @Option(names = ["--line"], description = ["1-based caret line in --file."]) var line: Int? = null
    @Option(names = ["--no-wait-smart"], description = ["Don't wait for indexing to finish."]) var noWaitSmart: Boolean = false
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 180
    @Option(names = ["--state"], description = ["Afterwards print editorState."]) var state: Boolean = false

    override fun call(): Int {
        val r = connect()
        val abs = path.toAbsolutePath().normalize()
        val name = abs.fileName.toString().substringBeforeLast('.').takeIf { Files.isRegularFile(abs) } ?: abs.fileName.toString()
        val openJs = """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            var found = 'no';
            for (var i = 0; i < ps.length; i++) {
                var b = String(ps[i].getBasePath());
                if (b === '${jsEscape(abs.toString())}' || b === '${jsEscape(abs.parent?.toString() ?: "")}' || String(ps[i].getName()) === '${jsEscape(name)}')
                    found = ps[i].isInitialized() ? 'yes' : 'opening';
            }
            found;
        """.trimIndent()
        if (r.callJs<String>(openJs, false) == "no") {
            r.runJs(
                """
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function() {
                    com.intellij.ide.impl.ProjectUtil.openOrImport(java.nio.file.Paths.get('${jsEscape(abs.toString())}'), null, true);
                });
                """.trimIndent(),
                false,
            )
        }
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (runCatching { r.callJs<String>(openJs, false) }.getOrDefault("no") != "yes") {
            check(System.currentTimeMillis() < deadline) {
                "Project $abs didn't open within ${timeoutSeconds}s. Focus: " + focusLine(r)
            }
            Thread.sleep(500)
        }
        if (!noWaitSmart) {
            while (!runCatching { r.callJs<Boolean>("com.intellij.openapi.project.DumbService.isDumb(${projectByNameJs(name)}) === false", false) }.getOrDefault(false)) {
                check(System.currentTimeMillis() < deadline) { "Project $abs still indexing after ${timeoutSeconds}s" }
                Thread.sleep(1000)
            }
        }
        println("Project '$name' open${if (noWaitSmart) "" else " and smart"}. Focus: ${focusLine(r)}")
        file?.let { println(r.openFile(it, line, 1)) }
        if (state) println(r.editorState(null, java.time.Duration.ofSeconds(5), 400, null))
        return 0
    }

    private fun projectByNameJs(name: String) = """
        (function() {
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (String(ps[i].getName()) === '${jsEscape(name)}') return ps[i];
            return ps[ps.length - 1];
        })()
    """.trimIndent()

    private fun focusLine(r: RemoteRobot): String = runCatching {
        r.callJs<String>(
            """
            var w = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            w === null ? '<no active window>' : (w.getClass().getSimpleName() + (w.getTitle ? " '" + w.getTitle() + "'" : ''));
            """.trimIndent(),
            false,
        )
    }.getOrDefault("?")
}

// ---------- Waiting ----------

@Command(
    name = "waitFor",
    description = ["Wait until a component matching --text or --xpath shows up (or, with --gone, disappears). Use instead of sleep."],
)
class WaitForCommand : Callable<Int> {
    @Option(names = ["--xpath"]) var xpath: String? = null
    @Option(names = ["--text"], description = ["Visible text or accessible name, as in clickButton --text."]) var text: String? = null
    @Option(names = ["--gone"]) var gone: Boolean = false
    @Option(names = ["--timeout-seconds"]) var timeoutSeconds: Long = 10

    override fun call(): Int {
        val expr = xpath ?: text?.let { "//div[@text='$it' or @accessiblename='$it' or @visible_text='$it']" }
            ?: error("Provide --xpath or --text")
        val r = connect()
        val locator = xp(expr)
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSeconds * 1000
        while (true) {
            val present = runCatching { r.findAll(com.intellij.remoterobot.fixtures.ComponentFixture::class.java, locator).isNotEmpty() }.getOrDefault(false)
            if (present != gone) break
            check(System.currentTimeMillis() < deadline) { "Still ${if (gone) "present" else "absent"} after ${timeoutSeconds}s: $expr" }
            Thread.sleep(250)
        }
        println("${if (gone) "Gone" else "Found"} after ${System.currentTimeMillis() - start} ms: $expr")
        return 0
    }
}

// ---------- idea.log ----------


@Command(
    name = "ideLog",
    description = [
        "Print the running IDE's idea.log, filtered: --since-mark (only lines after the last --mark), --grep, --errors, --tail.",
        "Typical: `ideLog --mark`, reproduce, `ideLog --since-mark --grep MARKER`. Lines are cut at --width chars.",
    ],
)
class IdeLogCommand : Callable<Int> {
    @Option(names = ["--mark"], description = ["Remember the current end of the log and print nothing else."]) var mark: Boolean = false
    @Option(names = ["--since-mark"], description = ["Only lines written after the last --mark."]) var sinceMark: Boolean = false
    @Option(names = ["--grep"], description = ["Only lines matching this regex (Java syntax)."]) var grep: String? = null
    @Option(names = ["--errors"], description = ["Only ERROR entries, each with its next 2 lines (the exception)."]) var errors: Boolean = false
    @Option(names = ["--tail"], description = ["Print at most the last N matching lines (default 40)."]) var tail: Int = 40
    @Option(names = ["--width"], description = ["Cut lines at N chars (default 300)."]) var width: Int = 300
    @Option(names = ["--backend"], description = ["Split mode: the backend's log instead of the client's."]) var backend: Boolean = false

    override fun call(): Int {
        val state = State.load() ?: error("No IDE registered. Run `runIde` first.")
        val sandbox = state.sandbox
        val logMark = state.file.resolveSibling("${state.name}.logmark")
        val logs = Files.walk(sandbox, 4).use { s ->
            s.filter { it.fileName.toString() == "idea.log" && ("${it}".contains("/backend/") == backend) }.toList()
        }
        val log = logs.maxByOrNull { Files.getLastModifiedTime(it) } ?: error("No idea.log under $sandbox")
        val lines = Files.readAllLines(log, Charsets.UTF_8)
        if (mark) {
            Files.writeString(logMark, "$log\n${lines.size}")
            println("Marked $log at line ${lines.size}.")
            return 0
        }
        var from = 0
        if (sinceMark) {
            val (markedLog, count) = (if (Files.exists(logMark)) Files.readAllLines(logMark) else emptyList()) + listOf("", "")
            if (markedLog == log.toString()) from = count.toIntOrNull()?.coerceAtMost(lines.size) ?: 0
            else System.err.println("No --mark for $log: printing from the start.")
        }
        val regex = grep?.let { Regex(it) }
        val picked = mutableListOf<String>()
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            if (errors) {
                if (line.contains(" ERROR - ") && (regex == null || regex.containsMatchIn(line))) {
                    picked += lines.subList(i, minOf(i + 3, lines.size)).joinToString("\n") { it.take(width) }
                    i += 3
                    continue
                }
            } else if (regex == null || regex.containsMatchIn(line)) picked += line.take(width)
            i++
        }
        val shown = picked.takeLast(tail)
        println("$log: lines ${from + 1}-${lines.size}, ${picked.size} matching${if (shown.size < picked.size) ", last ${shown.size} shown" else ""}")
        shown.forEach(::println)
        return 0
    }
}
