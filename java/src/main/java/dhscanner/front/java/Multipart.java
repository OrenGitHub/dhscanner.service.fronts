package dhscanner.front.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal {@code multipart/form-data} reader, scoped to the exact wire
 * format produced by {@code aiohttp.FormData} in
 * {@code workers/native_parser/main.py}:
 *
 * <pre>
 * --&lt;boundary&gt;\r\n
 * Content-Disposition: form-data; name="source"; filename="X.java"\r\n
 * Content-Type: application/octet-stream\r\n
 * \r\n
 * &lt;file bytes&gt;\r\n
 * --&lt;boundary&gt;--\r\n
 * </pre>
 *
 * This is <em>not</em> a complete RFC 7578 parser. We deliberately keep
 * the surface tiny -- no nested multiparts, no base64 transfer encoding,
 * no header values beyond {@code Content-Disposition}'s {@code name=} /
 * {@code filename=}. Writing this directly against the known client
 * saves pulling in Jetty / Netty / Apache Commons FileUpload (each
 * 2-5 MB of transitive deps) which would blow up the custom jlink
 * runtime image this service relies on.
 */
final class Multipart {

    private Multipart() { /* no instances */ }

    /** A single named part in the multipart body: the raw bytes of the uploaded file. */
    static final class Part {
        final String filename;
        final byte[] content;
        Part(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }
    }

    /**
     * Scan the multipart body and return the first part whose
     * {@code Content-Disposition name="..."} equals {@code fieldName}, or
     * {@code null} if none is present. Returns the raw bytes of the part
     * -- character decoding is the caller's job (we don't know whether
     * the uploaded file is UTF-8 or something else).
     */
    static Part readSingleFileField(InputStream in, String contentType, String fieldName)
            throws IOException {
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return null;
        }
        byte[] body = readAll(in);
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);

        int offset = 0;
        while (offset < body.length) {
            int partStart = indexOf(body, boundaryBytes, offset);
            if (partStart < 0) {
                break;
            }
            int afterBoundary = partStart + boundaryBytes.length;
            // Terminator "--" directly after the boundary marks end-of-stream.
            if (afterBoundary + 1 < body.length
                    && body[afterBoundary] == '-'
                    && body[afterBoundary + 1] == '-') {
                break;
            }
            int headerStart = skipCrlf(body, afterBoundary);
            int headerEnd = indexOf(body, CRLF_CRLF, headerStart);
            if (headerEnd < 0) {
                break;
            }
            String headers = new String(
                body, headerStart, headerEnd - headerStart, StandardCharsets.US_ASCII);
            int contentStart = headerEnd + CRLF_CRLF.length;
            int nextBoundary = indexOf(body, boundaryBytes, contentStart);
            if (nextBoundary < 0) {
                break;
            }
            // The part's content ends 2 bytes before the next boundary --
            // the \r\n that precedes "--<boundary>" is part of the
            // delimiter, NOT part of the payload (RFC 7578 §4.1).
            int contentEnd = Math.max(contentStart, nextBoundary - 2);

            if (matchesFieldName(headers, fieldName)) {
                byte[] content = new byte[contentEnd - contentStart];
                System.arraycopy(body, contentStart, content, 0, content.length);
                return new Part(extractFilename(headers), content);
            }

            offset = nextBoundary;
        }
        return null;
    }

    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    private static String extractBoundary(String contentType) {
        for (String token : contentType.split(";")) {
            String t = token.trim();
            if (t.toLowerCase().startsWith("boundary=")) {
                String v = t.substring("boundary=".length()).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static int skipCrlf(byte[] body, int offset) {
        if (offset + 1 < body.length && body[offset] == '\r' && body[offset + 1] == '\n') {
            return offset + 2;
        }
        if (offset < body.length && body[offset] == '\n') {
            return offset + 1;
        }
        return offset;
    }

    private static boolean matchesFieldName(String headers, String fieldName) {
        // RFC 7578 Content-Disposition is case-insensitive on header name
        // AND on the "form-data" / parameter-name tokens; parameter
        // VALUES (the actual field name string) are case-sensitive.
        String lower = headers.toLowerCase();
        if (!lower.contains("content-disposition")) {
            return false;
        }
        int idx = lower.indexOf("name=\"");
        if (idx < 0) {
            return false;
        }
        int valueStart = idx + "name=\"".length();
        int valueEnd = headers.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return false;
        }
        return headers.substring(valueStart, valueEnd).equals(fieldName);
    }

    private static String extractFilename(String headers) {
        // aiohttp emits both `filename=` (unquoted-ascii fallback) and
        // `filename*=utf-8''...` (RFC 5987). We only read the former,
        // which is good enough for the ASCII-only test filenames the
        // worker currently sends. Non-ASCII filenames would arrive as
        // percent-escaped bytes under filename*= and are a known gap --
        // not worth the extra ~30 lines until a real-world test fails.
        int idx = headers.toLowerCase().indexOf("filename=\"");
        if (idx < 0) {
            return null;
        }
        int valueStart = idx + "filename=\"".length();
        int valueEnd = headers.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        return headers.substring(valueStart, valueEnd);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) {
            return from;
        }
        outer:
        for (int i = from; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
