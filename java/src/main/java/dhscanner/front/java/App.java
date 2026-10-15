package dhscanner.front.java;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP front for dhscanner's Java pipeline.
 *
 * Mirrors the per-language pattern used by the other fronts
 * (frontjs/frontts/frontpy/frontphp/frontgo/frontcs): expose exactly two
 * routes --
 *
 *   GET  /healthcheck         -> 200 {"healthy": true}
 *   POST /to/native/java/ast  -> native Java AST (JSON) for the uploaded source file
 *
 * We deliberately avoid Spring / Micronaut / Javalin / any HTTP framework
 * so the whole service has zero runtime dependencies beyond the JDK
 * itself. That lets the Dockerfile build a custom jlink runtime
 * containing only `jdk.compiler` + `jdk.httpserver` (plus their
 * transitive requires), which keeps the final image around 80 MB and
 * cold-starts in well under a second -- in the same spirit as the PHP
 * front using `php -S` directly instead of a Laravel skeleton.
 *
 * Parsing itself delegates to javac's own public tree API
 * (`com.sun.source.*`) in {@link AstPrinter}. That is the exact parser
 * that `javac` itself uses, which is the strongest "certified" guarantee
 * we can give for Java-language fidelity -- parallel to how the C# front
 * uses Roslyn (Microsoft's actual compiler), the Go front uses
 * `go/parser`, and the Python front uses the stdlib `ast` module.
 */
public final class App {

    private static final int PORT = 5000;

    private App() { /* no instances */ }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        // Small worker pool so concurrent file uploads from
        // `workers/native_parser` (asyncio.gather) don't serialize at the
        // front -- same reason the PHP front sets PHP_CLI_SERVER_WORKERS=4.
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.createContext("/healthcheck", App::handleHealthcheck);
        server.createContext("/to/native/java/ast", App::handleAst);
        server.start();
        System.err.println("[frontjava] listening on 0.0.0.0:" + PORT
            + " (threads=" + threads + ", java=" + System.getProperty("java.version") + ")");
    }

    private static void handleHealthcheck(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "method not allowed", "text/plain");
            return;
        }
        send(ex, 200, "{\"healthy\": true}", "application/json");
    }

    private static void handleAst(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "method not allowed", "text/plain");
            return;
        }

        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
            send(ex, 400, "expected multipart/form-data", "text/plain");
            return;
        }

        String filename = "Source.java";
        try {
            Multipart.Part file = Multipart.readSingleFileField(
                ex.getRequestBody(), contentType, "source");
            if (file == null) {
                send(ex, 400, "missing 'source' field in multipart body", "text/plain");
                return;
            }
            if (file.filename != null && !file.filename.isEmpty()) {
                filename = file.filename;
            }

            // Java sources on disk are overwhelmingly UTF-8; be lenient so
            // a single stray byte can't take the whole batch down (same
            // `errors='replace'` posture the Python worker uses when
            // reading our response).
            String source = new String(file.content, StandardCharsets.UTF_8);
            String ast = AstPrinter.printToJson(filename, source);
            // Reformat into indented JSON before shipping so that the
            // downstream Happy parse-error location reported by the
            // parsers service ( `JavaParser.y` -> `[line:col-line:col]` )
            // points at a human-readable line/column in the native AST
            // instead of a single-line byte-offset into a 40k-char
            // one-liner. The Haskell JavaLexer.x consumes `$white+` as
            // skipped whitespace, so the extra newlines/indent are
            // transparent to the grammar but let the agent-loop driver
            // ( and anyone eyeballing a failing file on disk ) land on
            // the exact native-AST node that trips the grammar.
            ast = prettyPrint(ast);
            send(ex, 200, ast, "application/json");
        } catch (Throwable t) {
            // Match the "front-side failure, drop this file" convention of
            // the other fronts: emit an empty body with HTTP 200 so the
            // native_parser worker records NATIVE_PARSING_EMPTY_AST for
            // this file but the rest of the batch keeps flowing. The PHP
            // front emits the literal string "ERROR" for the same reason;
            // on the worker side `len(native_ast) == 0` is already treated
            // as "empty AST" so we don't need a distinct sentinel.
            System.err.println("[frontjava] parse error for " + filename
                + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            send(ex, 200, "", "text/plain");
        }
    }

    /**
     * Reformat the compact JSON emitted by {@link AstPrinter#printToJson}
     * into a 2-space-indented form.
     *
     * <p>Zero-dep hand-rolled scanner (frontjava's whole point is "no
     * runtime deps beyond the JDK itself", so no Jackson / gson /
     * JSONP). {@link AstPrinter}'s {@code sanitiseForLexer} has already
     * stripped every {@code "} and {@code \} out of every string
     * payload before it reaches here, so "am I inside a JSON string?"
     * degenerates to a toggle on every {@code "}; the inner-string
     * branch therefore does no escape-handling of its own.
     *
     * <p>Empty objects / arrays stay on one line ({@code {}} and
     * {@code []}); every other structural token gets a newline + indent
     * after it. Keys and values are joined by {@code ": "} rather than
     * the bare colon so the output round-trips through any JSON tool
     * that doesn't tolerate the compact form.
     */
    private static String prettyPrint(String compact) {
        if (compact.isEmpty()) {
            return compact;
        }
        StringBuilder out = new StringBuilder((int) (compact.length() * 1.3));
        int indent = 0;
        boolean inString = false;
        for (int i = 0, n = compact.length(); i < n; i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    out.append(c);
                    inString = true;
                    break;
                case '{':
                case '[': {
                    char next = (i + 1 < n) ? compact.charAt(i + 1) : 0;
                    if ((c == '{' && next == '}') || (c == '[' && next == ']')) {
                        // Collapse "{}" / "[]" onto one line -- a
                        // standalone `annotations:[]` or `supers:[]`
                        // adds no information and costs two lines.
                        out.append(c).append(next);
                        i++;
                    } else {
                        out.append(c);
                        indent++;
                        out.append('\n');
                        appendIndent(out, indent);
                    }
                    break;
                }
                case '}':
                case ']':
                    indent--;
                    out.append('\n');
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c).append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(':').append(' ');
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder out, int level) {
        for (int k = 0; k < level; k++) {
            out.append("  ");
        }
    }

    private static void send(HttpExchange ex, int code, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        // sendResponseHeaders(code, 0) means "chunked"; use the exact
        // length so clients can skip chunked decoding.
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }
}
