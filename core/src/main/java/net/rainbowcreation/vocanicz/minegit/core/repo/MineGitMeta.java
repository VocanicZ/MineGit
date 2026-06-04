package net.rainbowcreation.vocanicz.minegit.core.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Model of the repository metadata file {@code minegit.json}:
 *
 * <pre>
 * { "formatVersion": &lt;int&gt;, "mcVersionsSeen": [&lt;string&gt;...], "dimensions": [&lt;string&gt;...] }
 * </pre>
 *
 * <p>Serialization is deterministic: the string arrays are de-duplicated and sorted, and fields are
 * emitted in a fixed order with two-space indentation. This guarantees byte-stable output so the
 * file diffs cleanly in version control. No Minecraft dependencies.
 */
public final class MineGitMeta {

    private final int formatVersion;
    private final List<String> mcVersionsSeen;
    private final List<String> dimensions;

    public MineGitMeta(int formatVersion, List<String> mcVersionsSeen, List<String> dimensions) {
        this.formatVersion = formatVersion;
        this.mcVersionsSeen = sortedUnique(mcVersionsSeen);
        this.dimensions = sortedUnique(dimensions);
    }

    private static List<String> sortedUnique(List<String> in) {
        Objects.requireNonNull(in);
        TreeSet<String> set = new TreeSet<>();
        for (String s : in) {
            set.add(Objects.requireNonNull(s, "element"));
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    /** De-duplicated, lexicographically sorted, immutable. */
    public List<String> getMcVersionsSeen() {
        return mcVersionsSeen;
    }

    /** De-duplicated, lexicographically sorted, immutable. */
    public List<String> getDimensions() {
        return dimensions;
    }

    /** Serializes to deterministic JSON text (trailing newline included). */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"formatVersion\": ").append(formatVersion).append(",\n");
        appendStringArray(sb, "mcVersionsSeen", mcVersionsSeen, true);
        appendStringArray(sb, "dimensions", dimensions, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendStringArray(
        StringBuilder sb, String key, List<String> values, boolean trailingComma) {
        sb.append("  \"").append(key).append("\": ");
        if (values.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[\n");
            for (int i = 0; i < values.size(); i++) {
                sb.append("    \"").append(escape(values.get(i))).append('"');
                sb.append(i < values.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  ]");
        }
        sb.append(trailingComma ? ",\n" : "\n");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Writes {@link #toJson()} to {@code path} as UTF-8. */
    public void writeTo(Path path) {
        try {
            Files.write(path, toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + path, e);
        }
    }

    /** Reads and parses a {@code minegit.json} from {@code path} (UTF-8). */
    public static MineGitMeta readFrom(Path path) {
        try {
            return fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + path, e);
        }
    }

    /** Parses {@code minegit.json} text. */
    public static MineGitMeta fromJson(String json) {
        JsonParser p = new JsonParser(json);
        return p.parseMeta();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MineGitMeta)) {
            return false;
        }
        MineGitMeta that = (MineGitMeta) o;
        return formatVersion == that.formatVersion
            && mcVersionsSeen.equals(that.mcVersionsSeen)
            && dimensions.equals(that.dimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, mcVersionsSeen, dimensions);
    }

    @Override
    public String toString() {
        return "MineGitMeta(formatVersion="
            + formatVersion
            + ", mcVersionsSeen="
            + mcVersionsSeen
            + ", dimensions="
            + dimensions
            + ")";
    }

    /**
     * Minimal recursive-descent JSON parser covering the subset MineGit emits: an object whose
     * values are integers or arrays of strings. Tolerant of any field ordering and whitespace.
     */
    private static final class JsonParser {

        private final String s;
        private int i;

        JsonParser(String s) {
            this.s = s;
        }

        MineGitMeta parseMeta() {
            int formatVersion = 0;
            List<String> versions = new ArrayList<>();
            List<String> dims = new ArrayList<>();
            ws();
            expect('{');
            ws();
            if (peek() != '}') {
                do {
                    ws();
                    String key = parseString();
                    ws();
                    expect(':');
                    ws();
                    if ("formatVersion".equals(key)) {
                        formatVersion = parseInt();
                    } else if ("mcVersionsSeen".equals(key)) {
                        versions = parseStringArray();
                    } else if ("dimensions".equals(key)) {
                        dims = parseStringArray();
                    } else {
                        skipValue();
                    }
                    ws();
                } while (consumeIf(','));
            }
            ws();
            expect('}');
            return new MineGitMeta(formatVersion, versions, dims);
        }

        private List<String> parseStringArray() {
            List<String> out = new ArrayList<>();
            expect('[');
            ws();
            if (peek() != ']') {
                do {
                    ws();
                    out.add(parseString());
                    ws();
                } while (consumeIf(','));
            }
            ws();
            expect(']');
            return out;
        }

        private void skipValue() {
            char c = peek();
            if (c == '"') {
                parseString();
            } else if (c == '[') {
                expect('[');
                ws();
                if (peek() != ']') {
                    do {
                        ws();
                        skipValue();
                        ws();
                    } while (consumeIf(','));
                }
                ws();
                expect(']');
            } else if (c == '{') {
                expect('{');
                ws();
                if (peek() != '}') {
                    do {
                        ws();
                        parseString();
                        ws();
                        expect(':');
                        ws();
                        skipValue();
                        ws();
                    } while (consumeIf(','));
                }
                ws();
                expect('}');
            } else {
                // number, true, false, null
                while (i < s.length() && "{}[],: \t\r\n".indexOf(s.charAt(i)) < 0) {
                    i++;
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw err("bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw err("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private int parseInt() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (start == i) {
                throw err("expected integer");
            }
            return Integer.parseInt(s.substring(start, i));
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            return s.charAt(i);
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw err("expected '" + c + "'");
            }
            i++;
        }

        private boolean consumeIf(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("minegit.json parse error at " + i + ": " + msg);
        }
    }
}
