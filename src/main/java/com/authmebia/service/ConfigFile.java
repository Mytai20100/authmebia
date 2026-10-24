package com.authmebia.service;

import com.authmebia.AuthMeBia;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigFile {

    public static final int SAFE_PAYLOAD_BYTES = 28000;
    public static final String KIND_CHANGED = "changed";
    public static final String KIND_VALIDATION = "validation";
    public static final String KIND_DUPLICATE = "duplicate";

    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();
    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+|\"[^\"]+\"|'[^']+')\\s*:(.*)$");
    private static final Pattern LINE_NUMBER = Pattern.compile("line (\\d+)");

    private final AuthMeBia plugin;
    private final File file;
    private volatile String eol = "\n";

    public ConfigFile(AuthMeBia plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
    }

    public record Snapshot(String text, String eol, String hash) {}

    public record Validation(boolean valid, String reason, int line, String snippet) {
        public static Validation ok() {
            return new Validation(true, "", 0, "");
        }
    }

    public record Block(String key, String text, int startLine, int startOffset, int endOffset) {}

    public static final class ConfigException extends Exception {
        private final String kind;
        private final int line;
        private final String snippet;

        public ConfigException(String kind, String message) {
            this(kind, message, 0, "");
        }

        public ConfigException(String kind, String message, int line, String snippet) {
            super(message);
            this.kind = kind;
            this.line = line;
            this.snippet = snippet == null ? "" : snippet;
        }

        public String kind() {
            return kind;
        }

        public int line() {
            return line;
        }

        public String snippet() {
            return snippet;
        }
    }

    public Snapshot read() throws IOException {
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String detected = raw.contains("\r\n") ? "\r\n" : "\n";
        eol = detected;
        String normalized = normalize(raw);
        return new Snapshot(normalized, detected, hash(raw));
    }

    public String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public Validation validate(String text) {
        String normalized = normalize(text);
        String[] lines = normalized.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int j = 0;
            while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) {
                if (line.charAt(j) == '\t') {
                    return new Validation(false, "Tab indentation is not allowed", i + 1, snippet(line));
                }
                j++;
            }
        }
        Validation duplicates = checkDuplicates(lines);
        if (!duplicates.valid()) return duplicates;
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(normalized);
            if (config == null) {
                return new Validation(false, "YAML parser returned no document", 0, "");
            }
            if (config.getValues(false) == null) {
                return new Validation(false, "YAML root must be a map", 0, "");
            }
            return Validation.ok();
        } catch (Throwable t) {
            return fromThrowable(t, lines);
        }
    }

    private Validation checkDuplicates(String[] lines) {
        Deque<Frame> stack = new ArrayDeque<>();
        boolean inList = false;
        int listIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                inList = true;
                listIndent = indent;
                continue;
            }
            if (inList) {
                if (indent > listIndent) continue;
                inList = false;
                listIndent = -1;
            }
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.matches()) continue;
            String key = unquote(matcher.group(2));
            while (!stack.isEmpty() && stack.peek().indent > indent) {
                stack.pop();
            }
            if (stack.isEmpty() || stack.peek().indent < indent) {
                stack.push(new Frame(indent));
            }
            Frame frame = stack.peek();
            if (!frame.keys.add(key)) {
                return new Validation(false, "Duplicate key '" + key + "'", i + 1, snippet(line));
            }
        }
        return Validation.ok();
    }

    private Validation fromThrowable(Throwable throwable, String[] lines) {
        int line = 0;
        Throwable current = throwable;
        while (current != null && line <= 0) {
            try {
                Method method = current.getClass().getMethod("getProblemMark");
                Object mark = method.invoke(current);
                if (mark != null) {
                    Object value = mark.getClass().getMethod("getLine").invoke(mark);
                    if (value instanceof Number number) {
                        line = number.intValue() + 1;
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getCause();
        }
        String message = throwable.getMessage();
        if (line <= 0 && message != null) {
            Matcher matcher = LINE_NUMBER.matcher(message);
            if (matcher.find()) {
                try {
                    line = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    line = 0;
                }
            }
        }
        String reason = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        String snippet = "";
        if (line > 0 && line <= lines.length) {
            snippet = snippet(lines[line - 1]);
        }
        return new Validation(false, reason, line, snippet);
    }

    public List<Block> blocks(String text) {
        String normalized = normalize(text);
        String[] lines = normalized.split("\n", -1);
        List<Integer> keyLines = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = KEY_LINE.matcher(lines[i]);
            if (!matcher.matches() || !matcher.group(1).isEmpty()) continue;
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("-")) continue;
            keyLines.add(i);
            keys.add(unquote(matcher.group(2)));
        }
        if (keyLines.isEmpty()) {
            return List.of(new Block("config", normalized, 1, 0, normalized.length()));
        }
        int[] starts = new int[keyLines.size()];
        starts[0] = 0;
        for (int i = 1; i < keyLines.size(); i++) {
            int start = keyLines.get(i);
            while (start > 0 && isAttachment(lines[start - 1])) {
                start--;
            }
            if (start < starts[i - 1]) {
                start = starts[i - 1];
            }
            starts[i] = start;
        }
        int[] lineOffsets = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) {
            lineOffsets[i + 1] = lineOffsets[i] + lines[i].length() + 1;
        }
        List<Block> result = new ArrayList<>();
        for (int i = 0; i < keyLines.size(); i++) {
            int startLine = starts[i];
            int endLine = i + 1 < starts.length ? starts[i + 1] : lines.length;
            int startOffset = startLine < lines.length ? lineOffsets[startLine] : normalized.length();
            int endOffset = endLine < lines.length ? lineOffsets[endLine] : normalized.length();
            if (startOffset > normalized.length()) startOffset = normalized.length();
            if (endOffset > normalized.length()) endOffset = normalized.length();
            if (endOffset < startOffset) endOffset = startOffset;
            result.add(new Block(keys.get(i), normalized.substring(startOffset, endOffset),
                    startLine + 1, startOffset, endOffset));
        }
        StringBuilder joined = new StringBuilder(normalized.length());
        for (Block block : result) {
            joined.append(block.text());
        }
        if (!joined.toString().equals(normalized)) {
            throw new IllegalStateException("Config block split invariant failed");
        }
        return result;
    }

    public String setScalar(String text, String path, String value) throws ConfigException {
        String normalized = normalize(text);
        if (path == null || path.isBlank()) {
            throw new ConfigException(KIND_VALIDATION, "Empty config path");
        }
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new ConfigException(KIND_VALIDATION, "Invalid scalar value");
        }
        String[] lines = normalized.split("\n", -1);
        List<Integer> matches = findKeyLines(lines, path);
        if (matches.size() > 1) {
            throw new ConfigException(KIND_DUPLICATE, "Key appears more than once: " + path);
        }
        if (matches.size() == 1) {
            int index = matches.get(0);
            lines[index] = replaceValueOnLine(lines[index], value);
            return String.join("\n", lines);
        }
        return insertScalar(lines, path, value);
    }

    public void writeAtomic(String text, String expectedHash) throws IOException, ConfigException {
        Validation validation = validate(text);
        if (!validation.valid()) {
            throw new ConfigException(KIND_VALIDATION, validation.reason(),
                    validation.line(), validation.snippet());
        }
        WRITE_LOCK.lock();
        try {
            String disk = Files.exists(file.toPath())
                    ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
            if (!hash(disk).equals(expectedHash)) {
                throw new ConfigException(KIND_CHANGED, "config.yml changed on disk");
            }
            Path target = file.toPath();
            Path parent = target.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Config directory is unavailable");
            }
            Path backup = target.resolveSibling(file.getName() + ".bak");
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            String out = eol.equals("\r\n") ? text.replace("\n", "\r\n") : text;
            Path temp = target.resolveSibling(file.getName() + ".tmp");
            Files.write(temp, out.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String after = Files.readString(target, StandardCharsets.UTF_8);
            Validation check = validate(after);
            if (!check.valid()) {
                if (Files.exists(backup)) {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                }
                throw new ConfigException(KIND_VALIDATION, check.reason(),
                        check.line(), check.snippet());
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    public String reassemble(String fullText, Block block, String editedBlock) {
        String normalized = normalize(fullText);
        boolean trailingNewline = normalized.endsWith("\n");
        int start = Math.max(0, Math.min(block.startOffset(), normalized.length()));
        int end = Math.max(start, Math.min(block.endOffset(), normalized.length()));
        String updated = normalized.substring(0, start) + normalize(editedBlock) + normalized.substring(end);
        if (trailingNewline) {
            if (!updated.endsWith("\n")) updated = updated + "\n";
        } else {
            while (updated.endsWith("\n")) {
                updated = updated.substring(0, updated.length() - 1);
            }
        }
        return updated;
    }

    public static int estimatePayload(String text) {
        if (text == null) return 0;
        return text.getBytes(StandardCharsets.UTF_8).length + 512;
    }

    private String insertScalar(String[] lines, String path, String value) throws ConfigException {
        String[] parts = path.split("\\.");
        int existing = 0;
        for (int i = parts.length - 1; i >= 1; i--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, i));
            List<Integer> found = findKeyLines(lines, prefix);
            if (found.size() > 1) {
                throw new ConfigException(KIND_DUPLICATE, "Key appears more than once: " + prefix);
            }
            if (found.size() == 1) {
                existing = i;
                break;
            }
        }
        List<String> output = new ArrayList<>(List.of(lines));
        if (existing > 0) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, existing));
            int parentIndex = findKeyLines(lines, prefix).get(0);
            int indent = leadingSpaces(lines[parentIndex]) + 2;
            List<String> inserted = new ArrayList<>();
            for (int i = existing; i < parts.length; i++) {
                String key = parts[i];
                String line = " ".repeat(indent + (i - existing) * 2) + key + ":";
                if (i == parts.length - 1) {
                    line = line + " " + value;
                }
                inserted.add(line);
            }
            output.addAll(parentIndex + 1, inserted);
            return String.join("\n", output);
        }
        int insertAt = output.size();
        for (int i = output.size() - 1; i >= 0; i--) {
            Matcher matcher = KEY_LINE.matcher(output.get(i));
            if (matcher.matches() && matcher.group(1).isEmpty()
                    && "config_version".equals(unquote(matcher.group(2)))) {
                insertAt = i;
                break;
            }
        }
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String line = " ".repeat(i * 2) + parts[i] + ":";
            if (i == parts.length - 1) {
                line = line + " " + value;
            }
            inserted.add(line);
        }
        output.addAll(insertAt, inserted);
        return String.join("\n", output);
    }

    private List<Integer> findKeyLines(String[] lines, String path) {
        List<Integer> matches = new ArrayList<>();
        Deque<PathFrame> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                while (!stack.isEmpty() && stack.peek().indent >= indent) {
                    stack.pop();
                }
                continue;
            }
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.matches()) continue;
            String key = unquote(matcher.group(2));
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            String full = stack.isEmpty() ? key : stack.peek().path + "." + key;
            if (full.equals(path)) {
                matches.add(i);
            }
            stack.push(new PathFrame(indent, full));
        }
        return matches;
    }

    private String replaceValueOnLine(String line, String value) {
        Matcher matcher = KEY_LINE.matcher(line);
        if (!matcher.matches()) return line;
        int colon = line.indexOf(':', matcher.end(2));
        if (colon < 0) return line;
        int valueStart = colon + 1;
        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
            valueStart++;
        }
        String rest = line.substring(valueStart);
        if (rest.startsWith("#")) {
            return line.substring(0, valueStart) + value + " " + rest;
        }
        int comment = -1;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quote != 0) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) {
                comment = i;
                break;
            }
        }
        if (comment >= 0) {
            return line.substring(0, valueStart) + value + " " + rest.substring(comment);
        }
        return line.substring(0, valueStart) + value;
    }

    private boolean isAttachment(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private String unquote(String key) {
        if (key == null || key.length() < 2) return key == null ? "" : key;
        char first = key.charAt(0);
        char last = key.charAt(key.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private String snippet(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() > 80) return trimmed.substring(0, 80);
        return trimmed;
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static final class Frame {
        private final int indent;
        private final Set<String> keys = new HashSet<>();

        private Frame(int indent) {
            this.indent = indent;
        }
    }

    private static final class PathFrame {
        private final int indent;
        private final String path;

        private PathFrame(int indent, String path) {
            this.indent = indent;
            this.path = path;
        }
    }
}
