package dhscanner.front.java;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Native Java AST emitter.
 *
 * <h2>Why javac's public tree API ({@code com.sun.source.*})</h2>
 *
 * The parser we invoke here is literally the one {@code javac} itself
 * uses. Alternatives considered and rejected for the first version:
 *
 * <ul>
 *   <li><b>JavaParser</b> (javaparser.org) -- friendly API, but a
 *       third-party reimplementation; its syntactic acceptance lags
 *       behind each new JDK by weeks or months. We want "whatever
 *       {@code javac} accepts" so we track the Java language spec
 *       exactly.</li>
 *   <li><b>Eclipse JDT</b> -- powerful, but ships ~15 MB of OSGi
 *       infrastructure as transitive deps; blows up our jlink image.</li>
 *   <li><b>Spoon</b> -- built on top of JDT; same size issue, plus an
 *       extra abstraction layer we don't need.</li>
 * </ul>
 *
 * <h2>Output format</h2>
 *
 * A single JSON object (the {@code CompilationUnit}) whose shape is
 * uniform across every tree node:
 *
 * <pre>
 * {
 *   "kind": "&lt;Tree.Kind name, e.g. METHOD&gt;",
 *   "loc":  {"startLine": &lt;1-based&gt;, "startCol": &lt;1-based&gt;,
 *            "endLine": &lt;1-based&gt;, "endCol": &lt;1-based&gt;},
 *   "text": "&lt;payload for leaves that carry one -- see extractText()&gt;",
 *   "children": [ &lt;same shape, in syntax order&gt; ]
 * }
 * </pre>
 *
 * The {@code loc} key is present for every tree that has a mapped source
 * position (javac can emit synthetic trees with {@code start == -1}; we
 * omit {@code loc} for those -- see the {@code endPos &lt; 0} branches
 * below). The {@code text} key is only present on nodes that carry a
 * meaningful atomic payload beyond their children (identifier names,
 * literal values, modifier flags, etc.). Positional {@code children}
 * follow the order defined by {@link TreeScanner}'s per-kind visit
 * methods, matching how the C# front (Roslyn) exposes
 * {@code node.ChildNodes()} and how the TypeScript front walks
 * {@code node.getChildAt(i)}.
 *
 * <h2>Thread safety</h2>
 *
 * Every request constructs a fresh {@link JavaCompiler} task + a fresh
 * {@link JsonTreePrinter}. Nothing is shared across requests, so the
 * HTTP executor pool in {@link App} is free to run calls concurrently.
 */
final class AstPrinter {

    private AstPrinter() { /* no instances */ }

    static String printToJson(String filename, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // Means we're running on a JRE without jdk.compiler. The
            // Dockerfile explicitly jlinks jdk.compiler into the runtime
            // image, so this would be a packaging regression.
            throw new IllegalStateException(
                "no system Java compiler is available; container must bundle jdk.compiler");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new InMemoryJavaFile(filename, source);
        // -proc:none disables annotation processing. We only want the
        // parse tree; letting APs run would (a) be useless since no
        // classpath is set, and (b) risk a slow / failing SPI lookup.
        JavacTask task = (JavacTask) compiler.getTask(
            null, null, diagnostics, List.of("-proc:none"), null, List.of(file));

        Iterable<? extends CompilationUnitTree> units = task.parse();
        var it = units.iterator();
        if (!it.hasNext()) {
            return "";
        }
        CompilationUnitTree root = it.next();
        SourcePositions positions = Trees.instance(task).getSourcePositions();

        StringBuilder out = new StringBuilder(2048);
        new JsonTreePrinter(root, positions, out).scan(root, null);
        return out.toString();
    }

    /**
     * {@link JavaFileObject} backed by an in-memory string. Lets us hand
     * raw source bytes to {@link JavaCompiler#getTask} without touching
     * the filesystem (every request would otherwise write the uploaded
     * file to {@code /tmp}, which is both slow and a cleanup hazard).
     */
    private static final class InMemoryJavaFile extends SimpleJavaFileObject {
        private final String content;
        InMemoryJavaFile(String name, String content) {
            // The URI only has to look like a file path; javac uses it
            // for error messages. Normalize backslashes so a Windows-side
            // filename doesn't produce a broken URI.
            super(URI.create("mem:///" + name.replace('\\', '/').replace(" ", "%20")),
                  Kind.SOURCE);
            this.content = content;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    /**
     * Emits the JSON described in the class doc. We override only
     * {@link TreeScanner#scan(Tree, Object)} so that <em>every</em> tree
     * node -- regardless of its {@link Tree.Kind} -- is wrapped in the
     * uniform object envelope, and the kind-specific
     * {@code visitXxx(xxxTree, p)} defaults keep walking children in
     * their canonical syntax order.
     */
    private static final class JsonTreePrinter extends TreeScanner<Void, Void> {

        private final CompilationUnitTree root;
        private final SourcePositions positions;
        private final LineMap lineMap;
        private final StringBuilder out;

        /**
         * Whether the next JSON value we are about to emit is the first
         * one in its surrounding JSON array -- if so, no leading comma.
         * Saved/restored around each nested {@code "children": [...]}
         * so the flag follows the array nesting rather than a global
         * "have we emitted anything yet" bit.
         */
        private boolean firstInArray = true;

        JsonTreePrinter(CompilationUnitTree root, SourcePositions positions, StringBuilder out) {
            this.root = root;
            this.positions = positions;
            this.lineMap = root.getLineMap();
            this.out = out;
        }

        @Override
        public Void scan(Tree node, Void unused) {
            if (node == null) {
                // Optional sub-trees (e.g. a class with no `extends`
                // clause) are elided. The downstream consumer walks
                // `children` and matches by `kind`, not by positional
                // index -- same convention as the C# front.
                return null;
            }

            if (!firstInArray) {
                out.append(',');
            }
            firstInArray = false;

            out.append('{');
            out.append("\"kind\":\"").append(node.getKind().name()).append('"');
            emitLocation(node);
            String text = extractText(node);
            if (text != null) {
                // Sanitise BEFORE serialising: strip embedded quotes,
                // backslashes, and newlines so the downstream Haskell
                // Alex lexer can use a simple [^"]* body for its
                // generic-string token (same convention as the TS /
                // JS / PHP fronts). This loses nothing a dhscanner
                // analysis cares about -- the payload is only read as
                // an identifier-shaped lexeme -- and it keeps the
                // JavaLexer.x string rule a one-liner.
                out.append(",\"text\":");
                writeJsonString(sanitiseForLexer(text));
            }
            out.append(",\"children\":[");

            // Enter a fresh "first child" context, scan children via the
            // default visit* implementations, then restore. Note we save
            // the OUTER value as `false` because any sibling of `node`
            // will need a leading comma.
            boolean savedFirstInArray = firstInArray;
            firstInArray = true;
            super.scan(node, unused);
            firstInArray = savedFirstInArray;

            out.append("]}");
            return null;
        }

        private void emitLocation(Tree node) {
            long start = positions.getStartPosition(root, node);
            long end   = positions.getEndPosition(root, node);
            if (start < 0) {
                // Synthetic / unmapped node (shouldn't happen for a
                // well-formed source, but javac does produce them for
                // some implicit constructs). Skip the loc key entirely
                // rather than emit garbage coordinates.
                return;
            }
            long sl = lineMap.getLineNumber(start);
            long sc = lineMap.getColumnNumber(start);
            // javac's getEndPosition reports -1 ("unknown") for some
            // trees whose end token wasn't tracked. Fall back to the
            // start position so the loc is at least valid JSON.
            long el = (end >= 0) ? lineMap.getLineNumber(end)   : sl;
            long ec = (end >= 0) ? lineMap.getColumnNumber(end) : sc;
            out.append(",\"loc\":{")
               .append("\"startLine\":").append(sl).append(',')
               .append("\"startCol\":").append(sc).append(',')
               .append("\"endLine\":").append(el).append(',')
               .append("\"endCol\":").append(ec)
               .append('}');
        }

        /**
         * Extract the "atomic payload" text for leaf-ish nodes. Returns
         * {@code null} if the node has nothing beyond its children worth
         * printing. Covers every {@link Tree.Kind} that carries an
         * identifier name, a literal value, modifier flags, or a
         * primitive type keyword -- i.e. everything the downstream
         * dhscanner-parser grammar would otherwise have to reach into
         * via a kind-specific accessor.
         */
        private static String extractText(Tree node) {
            switch (node.getKind()) {
                case IDENTIFIER:
                    return ((IdentifierTree) node).getName().toString();

                case MEMBER_SELECT:
                    // The LHS of the `.` is a child; the RHS is just a
                    // name (Name, not a Tree), so we have to lift it
                    // into text or it would be invisible.
                    return ((MemberSelectTree) node).getIdentifier().toString();

                case CLASS:
                case INTERFACE:
                case ENUM:
                case ANNOTATION_TYPE:
                case RECORD:
                    return ((ClassTree) node).getSimpleName().toString();

                case METHOD:
                    return ((MethodTree) node).getName().toString();

                case VARIABLE:
                    return ((VariableTree) node).getName().toString();

                case LABELED_STATEMENT:
                    return ((LabeledStatementTree) node).getLabel().toString();

                case BREAK: {
                    Name l = ((BreakTree) node).getLabel();
                    return (l == null) ? null : l.toString();
                }

                case CONTINUE: {
                    Name l = ((ContinueTree) node).getLabel();
                    return (l == null) ? null : l.toString();
                }

                case TYPE_PARAMETER:
                    return ((TypeParameterTree) node).getName().toString();

                case STRING_LITERAL:
                case CHAR_LITERAL:
                case INT_LITERAL:
                case LONG_LITERAL:
                case FLOAT_LITERAL:
                case DOUBLE_LITERAL:
                case BOOLEAN_LITERAL:
                case NULL_LITERAL: {
                    Object v = ((LiteralTree) node).getValue();
                    return (v == null) ? "null" : v.toString();
                }

                case PRIMITIVE_TYPE:
                    return ((PrimitiveTypeTree) node).getPrimitiveTypeKind().name();

                case MODIFIERS: {
                    // Flags are an EnumSet<Modifier> (not Tree nodes),
                    // so they wouldn't appear in `children` at all.
                    // Join as "PUBLIC STATIC" so downstream can split
                    // on whitespace. Annotations ARE children and will
                    // still be visited below.
                    var flags = ((ModifiersTree) node).getFlags();
                    if (flags.isEmpty()) {
                        return null;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (Modifier f : flags) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(f.name());
                    }
                    return sb.toString();
                }

                default:
                    return null;
            }
        }

        /**
         * Strip quotes, backslashes, newlines, tabs and control bytes
         * out of a text payload. Called from the single site that
         * emits a "text" JSON field. The downstream Alex lexer's
         * generic-string rule then becomes a simple
         * {@code \"[^\"]*\"} — see {@code JavaLexer.x}.
         */
        private static String sanitiseForLexer(String s) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0, n = s.length(); i < n; i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    continue;
                }
                if (c == '\n' || c == '\r' || c == '\t') {
                    b.append(' ');
                    continue;
                }
                if (c < 0x20) {
                    // Other control bytes get dropped silently;
                    // dhscanner analyses never care about them.
                    continue;
                }
                b.append(c);
            }
            return b.toString();
        }

        /**
         * Write a sanitised string as a JSON string literal. After
         * {@link #sanitiseForLexer} the content is guaranteed to
         * contain no quotes / backslashes / control bytes, so this
         * degenerates to "wrap in double-quotes".
         */
        private void writeJsonString(String s) {
            out.append('"').append(s).append('"');
        }
    }
}
