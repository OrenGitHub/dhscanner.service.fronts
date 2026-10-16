package dhscanner.front.java;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
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

            if (node instanceof TypeCastTree) {
                // Transparent unwrap: `(T) expr` is emitted as just
                // `expr`. The target type is irrelevant to dhscanner
                // analyses -- same philosophy as the UNBOUNDED_WILDCARD
                // / MODIFIERS drops, except here we keep the interesting
                // child instead of returning null.
                TypeCastTree castTree = (TypeCastTree) node;
                scan(castTree.getExpression(), null);
                return null;
            }

            if (node instanceof ParenthesizedTree) {
                // Transparent unwrap: `( expr )` is emitted as just
                // `expr`. Parentheses carry no analysis signal beyond
                // operator precedence, which the AST already encodes
                // structurally. Nested parens `(((x)))` collapse
                // naturally via the recursive scan() call. Same shape
                // as the TypeCastTree branch above.
                ParenthesizedTree parenTree = (ParenthesizedTree) node;
                scan(parenTree.getExpression(), null);
                return null;
            }

            if (node.getKind() == Tree.Kind.UNBOUNDED_WILDCARD) {
                // `?` in `Foo<?>`. The downstream dhscanner AST only
                // keeps the raw type (`Foo`); the unbounded wildcard
                // argument is syntactic noise with no analysis value.
                // Eliding it here means `Class<?>` lands as
                // PARAMETERIZED_TYPE with a single IDENTIFIER("Class")
                // child -- the existing `parameterized_type` rule
                // already accepts that, and `parameterizedType` already
                // drops type-args and returns the base.
                return null;
            }

            if (node.getKind() == Tree.Kind.MODIFIERS) {
                // MODIFIERS is a wrapper around (a) a bitset of flag
                // keywords ( PUBLIC / PRIVATE / STATIC / FINAL / ... )
                // -- pure syntactic noise for downstream analyses --
                // and (b) a list of ANNOTATION children. The three
                // kinds that can carry a MODIFIERS ( CLASS, VARIABLE,
                // METHOD ) ALL hoist its annotations to a dedicated
                // top-level `annotations` key via emitAnnotations(),
                // so the wrapper itself has nothing left to contribute.
                // Making it invisible here means it never leaks into
                // any parent's `children:[]` array -- CLASS and VARIABLE
                // never scan() their MODIFIERS node to begin with
                // (they call emitAnnotations() on it directly), and
                // METHOD now gets the same enrichment below. This
                // keeps the design principle uniform: "MODIFIERS gone,
                // annotations always preserved" ( see JAVA_PROGRESS.md ).
                return null;
            }

            if (!firstInArray) {
                out.append(',');
            }
            firstInArray = false;

            out.append('{');
            // Javac's Tree.Kind collapses both constructors and regular
            // methods under Kind.METHOD; the only way to tell them apart
            // on the tree is MethodTree.getReturnType() returning null
            // for constructors ( per the Java spec -- regular methods
            // MUST declare a return type, even `void` ). We split them
            // here into kind "CONSTRUCTOR" vs kind "METHOD" so the
            // downstream grammar can discriminate with a clean
            // kind-token terminal ( `'CONSTRUCTOR'` vs `'METHOD'` )
            // instead of having to peek at the `text` payload for the
            // `<init>` sentinel. Semantically meaningful split too:
            // ctors have no return type, so the children shape is a
            // strict subset ( `[ PARAMETER*, BLOCK ]` ) of what a
            // regular method emits ( `[ returnType, PARAMETER*, BLOCK ]` ).
            String kindName = node.getKind().name();
            if (node instanceof MethodTree && ((MethodTree) node).getReturnType() == null) {
                kindName = "CONSTRUCTOR";
            }
            out.append("\"kind\":\"").append(kindName).append('"');
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

            // Any ClassTree (CLASS / INTERFACE / ENUM / ANNOTATION_TYPE /
            // RECORD) uses a richer envelope: `annotations` and `supers`
            // are hoisted to first-class keys instead of being flattened
            // into `children`, and the MODIFIERS node + type parameters
            // are omitted entirely because the downstream dhscanner AST
            // does not model them. `children` then contains ONLY the
            // class's member declarations.
            if (node instanceof ClassTree) {
                ClassTree classTree = (ClassTree) node;
                emitAnnotations(classTree.getModifiers());
                emitSupers(classTree);
                emitClassMembers(classTree);
            } else if (node instanceof VariableTree) {
                // Fields, method params, local vars, lambda params --
                // same annotation lifting + modifier dropping as
                // classes, plus the two distinct slots `getType()` and
                // `getInitializer()` are promoted to named keys
                // (`type`, `init`) instead of a 2-element positional
                // `children` array. `init` is omitted when absent so
                // downstream consumers can test key presence instead of
                // null-handling.
                VariableTree varTree = (VariableTree) node;
                emitAnnotations(varTree.getModifiers());
                emitNamedChild("type", varTree.getType());
                emitNamedChild("init", varTree.getInitializer());
            } else if (node instanceof BinaryTree) {
                // Binary-operator expressions ( `a + b`, `x == y`,
                // `n * m`, ... ). The javac `Tree.Kind` already encodes
                // the operator ( `PLUS` / `EQUAL_TO` / `MULTIPLY` / … )
                // via the kind name, so there's no separate operator
                // payload to hoist -- we just promote the two operands
                // from positional `children:[ left, right ]` into named
                // `left` + `right` keys ( symmetric with `IF`'s cond /
                // body promotion and `VARIABLE`'s type / init ).
                // `children:[]` is kept but empty, matching the shape
                // every other printer-enriched kind uses ( CLASS /
                // VARIABLE / METHOD / IF ).
                BinaryTree binaryTree = (BinaryTree) node;
                emitNamedChild("left",  binaryTree.getLeftOperand());
                emitNamedChild("right", binaryTree.getRightOperand());
                out.append(",\"children\":[]");
            } else if (node instanceof IfTree) {
                // `if (cond) then; else else;` -- hoist the condition
                // and the then-branch to named keys ( `cond`, `body` )
                // instead of relying on the generic positional
                // `children:[ cond, then, else? ]` shape. The optional
                // else-arm stays in `children:[]` ( zero or one entry )
                // -- symmetric with how `VARIABLE`'s optional `init`
                // slot is modelled as a presence/absence decision on
                // the named key. getCondition() per the Java spec is
                // always a `ParenthesizedTree` ( `if ( ... )` requires
                // the parens syntactically ) ; the recursive scan()
                // transparently unwraps the parens ( see the
                // ParenthesizedTree branch above ), so the emitted
                // `cond` value is directly the inner expression.
                IfTree ifTree = (IfTree) node;
                emitNamedChild("cond", ifTree.getCondition());
                emitNamedChild("body", ifTree.getThenStatement());
                out.append(",\"children\":[");
                boolean savedFirstInArray = firstInArray;
                firstInArray = true;
                scan(ifTree.getElseStatement(), null);
                firstInArray = savedFirstInArray;
                out.append(']');
            } else if (node instanceof MethodTree) {
                // Same annotation-hoisting pattern as CLASS / VARIABLE,
                // plus a second lift that promotes `getParameters()` to
                // a dedicated top-level `parameters` key. Each parameter
                // is emitted via emitParameter() as a slimmed envelope
                // ( kind "PARAMETER", loc, text, type ) rather than the
                // full VARIABLE shape ; same strategy as the earlier
                // CONSTRUCTOR vs METHOD split -- a dedicated printer-
                // level kind lets the grammar discriminate with a
                // clean kind-token terminal instead of overloading the
                // existing `variable` rule, which carries slots
                // ( annotations, init ) that method params in practice
                // never populate. `children` is then only `returnType`
                // ( `null` for ctors ) + body + the rare throws /
                // typeParameters / receiverParameter / defaultValue
                // trees, walked explicitly in javac's canonical order.
                // We DO NOT call super.scan() here anymore because its
                // default visitMethod() would re-visit the parameter
                // VariableTrees we just hoisted, double-emitting them.
                MethodTree methodTree = (MethodTree) node;
                emitAnnotations(methodTree.getModifiers());
                emitParameters(methodTree);
                out.append(",\"children\":[");
                boolean savedFirstInArray = firstInArray;
                firstInArray = true;
                for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                    scan(tp, null);
                }
                scan(methodTree.getReturnType(), null);
                scan(methodTree.getReceiverParameter(), null);
                for (Tree thr : methodTree.getThrows()) {
                    scan(thr, null);
                }
                scan(methodTree.getBody(), null);
                scan(methodTree.getDefaultValue(), null);
                firstInArray = savedFirstInArray;
                out.append(']');
            } else {
                out.append(",\"children\":[");
                // Enter a fresh "first child" context, scan children via
                // the default visit* implementations, then restore. Note
                // we save the OUTER value as `false` because any sibling
                // of `node` will need a leading comma.
                boolean savedFirstInArray = firstInArray;
                firstInArray = true;
                super.scan(node, unused);
                firstInArray = savedFirstInArray;
                out.append(']');
            }

            out.append('}');
            return null;
        }

        /**
         * Hoist a {@link ModifiersTree}'s annotations into a dedicated
         * top-level {@code annotations} JSON array on the enclosing
         * node (CLASS, VARIABLE, METHOD, ...). This replaces the
         * previous nesting under MODIFIERS, which the downstream
         * Haskell grammar does not need to walk through.
         */
        private void emitAnnotations(ModifiersTree mods) {
            out.append(",\"annotations\":[");
            boolean saved = firstInArray;
            firstInArray = true;
            for (AnnotationTree a : mods.getAnnotations()) {
                scan(a, null);
            }
            firstInArray = saved;
            out.append(']');
        }

        /**
         * Coalesce a {@link ClassTree}'s single optional
         * {@code extends} clause and its {@code implements} list into
         * one flat {@code supers} JSON array. The extends element (if
         * any) comes first, followed by the implements elements in
         * source order. Matches dhscanner's AST shape where
         * {@code stmtClassSupers} is a single {@code [SuperName]}.
         */
        private void emitSupers(ClassTree node) {
            out.append(",\"supers\":[");
            boolean saved = firstInArray;
            firstInArray = true;
            Tree ext = node.getExtendsClause();
            if (ext != null) {
                scan(ext, null);
            }
            for (Tree impl : node.getImplementsClause()) {
                scan(impl, null);
            }
            firstInArray = saved;
            out.append(']');
        }

        /**
         * Emit only the member declarations into {@code children}.
         * Modifiers, type parameters, extends, and implements are
         * explicitly skipped here because they are either dropped or
         * hoisted to sibling keys above.
         */
        private void emitClassMembers(ClassTree node) {
            out.append(",\"children\":[");
            boolean saved = firstInArray;
            firstInArray = true;
            for (Tree member : node.getMembers()) {
                scan(member, null);
            }
            firstInArray = saved;
            out.append(']');
        }

        /**
         * Hoist a {@link MethodTree}'s parameters into a dedicated
         * top-level {@code parameters} JSON array. Each entry uses the
         * slim {@link #emitParameter} shape rather than the generic
         * {@code VARIABLE} envelope -- see the big comment in
         * {@link #scan} for why we mint a dedicated kind.
         */
        private void emitParameters(MethodTree node) {
            out.append(",\"parameters\":[");
            boolean saved = firstInArray;
            firstInArray = true;
            for (VariableTree p : node.getParameters()) {
                emitParameter(p);
            }
            firstInArray = saved;
            out.append(']');
        }

        /**
         * Emit a single method / constructor parameter as a slim
         * {@code {"kind":"PARAMETER","loc":...,"text":...,"type":...}}
         * envelope. We intentionally do NOT lift annotations or an
         * initializer here: Java parameters cannot carry initializers,
         * and parameter annotations ({@code @NotNull String s} etc.)
         * are rare enough that growing the envelope now would be
         * YAGNI -- if a file surfaces one we add an {@code annotations}
         * slot in a later iteration, same way {@code VARIABLE} did.
         */
        private void emitParameter(VariableTree p) {
            if (!firstInArray) {
                out.append(',');
            }
            firstInArray = false;
            out.append('{');
            out.append("\"kind\":\"PARAMETER\"");
            emitLocation(p);
            out.append(",\"text\":");
            writeJsonString(sanitiseForLexer(p.getName().toString()));
            emitNamedChild("type", p.getType());
            out.append('}');
        }

        /**
         * Emit a single optional child under a named key:
         * {@code ,"keyName":{...}} when {@code child} is non-null,
         * nothing at all otherwise. Used for the per-kind slot
         * promotions where a positional child in {@code children}
         * is lifted to a semantic sub-object (e.g. {@code type} and
         * {@code init} on {@code VARIABLE}).
         */
        private void emitNamedChild(String keyName, Tree child) {
            if (child == null) {
                return;
            }
            out.append(",\"").append(keyName).append("\":");
            boolean saved = firstInArray;
            firstInArray = true;
            scan(child, null);
            firstInArray = saved;
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
