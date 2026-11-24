package dbml;

import dbml.DbmlLexer.Kind;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tolerant DBML parser: keeps what the diagram needs (tables, columns, refs, groups) and skips the rest
 * (Project, Enum, indexes, sticky notes, records). Unknown constructs are ignored, never fatal.
 */
final class DbmlModel {
    record Column(String name, String type, boolean pk, String note) {}

    record Ref(String from, List<String> fromCols, String op, String to, List<String> toCols) {}

    record Group(String name, List<String> tables, Color color) {}

    static final class Table {
        final String name;
        final int offset;
        final List<Column> columns = new ArrayList<>();
        String alias, note;
        Color color;

        Table(String name, int offset) {
            this.name = name;
            this.offset = offset;
        }

        int indexOf(String column) {
            for (int k = 0; k < columns.size(); k++) if (columns.get(k).name().equals(column)) return k;
            return -1;
        }
    }

    final Map<String, Table> tables = new LinkedHashMap<>();
    final List<Ref> refs = new ArrayList<>();
    final List<Group> groups = new ArrayList<>();
    /** Non-null when the text is structurally broken (mid-edit); the caller keeps showing the previous diagram. */
    String error;

    private record Tok(Kind kind, String text, int offset, boolean nl) {
        int end() { return offset + text.length(); }
    }

    private record Endpoint(String table, List<String> cols) {}

    private static final class Settings {
        boolean pk;
        String note;
        Color color;
        final List<Object[]> refs = new ArrayList<>(); // {op, Endpoint}
    }

    private static final Set<String> OPS = Set.of(">", "<", "-", "<>");
    private static final Tok EOF = new Tok(null, "", 0, true);

    private final List<Tok> toks = new ArrayList<>();
    private final List<Ref> rawRefs = new ArrayList<>();
    private String src;
    private int i;

    static DbmlModel parse(CharSequence text) {
        DbmlModel m = new DbmlModel();
        m.tokenize(text);
        if (m.error == null) {
            m.parseTopLevel();
            m.resolve();
        }
        return m;
    }

    private void tokenize(CharSequence text) {
        src = text.toString();
        DbmlLexer lx = new DbmlLexer();
        lx.start(src);
        boolean nl = true;
        int braces = 0, brackets = 0;
        for (; lx.kind() != null; lx.advance()) {
            String t = src.substring(lx.getTokenStart(), lx.getTokenEnd());
            if (lx.kind() == Kind.WS || lx.kind() == Kind.COMMENT) {
                nl |= t.indexOf('\n') >= 0;
                continue;
            }
            toks.add(new Tok(lx.kind(), t, lx.getTokenStart(), nl));
            nl = false;
            switch (t) {
                case "{" -> braces++;
                case "}" -> braces--;
                case "[" -> brackets++;
                case "]" -> brackets--;
                default -> { }
            }
            if (braces < 0 || brackets < 0) break;
        }
        if (braces != 0 || brackets != 0) error = "Unbalanced " + (braces != 0 ? "{ }" : "[ ]");
    }

    // ---- cursor helpers ----

    private Tok cur() { return i < toks.size() ? toks.get(i) : EOF; }

    private Tok peek(int k) { return i + k < toks.size() ? toks.get(i + k) : EOF; }

    private boolean at(String s) { return cur().text.equalsIgnoreCase(s); }

    private boolean more() { return i < toks.size(); }

    private boolean isWord(Tok t) { return t.kind == Kind.IDENT || t.kind == Kind.KEYWORD || t.kind == Kind.SETTING; }

    private void skipBlock() { // cursor on '{'
        int depth = 0;
        do {
            if (at("{")) depth++;
            else if (at("}")) depth--;
            i++;
        } while (more() && depth > 0);
    }

    // ---- grammar ----

    private void parseTopLevel() {
        while (more()) {
            switch (cur().text.toLowerCase(Locale.ROOT)) {
                case "table" -> table();
                case "ref" -> ref();
                case "tablegroup" -> group();
                default -> { // Project, Enum, Note, TablePartial, Records, junk: skip, including its block
                    boolean keyword = cur().kind == Kind.KEYWORD;
                    i++;
                    if (!keyword) continue;
                    while (more() && !at("{") && !cur().nl) i++;
                    if (at("{")) skipBlock();
                }
            }
        }
    }

    private void table() {
        i++;
        int offset = cur().offset;
        String name = name();
        if (name.isEmpty()) return;
        Table t = new Table(name, offset);
        if (at("as")) {
            i++;
            t.alias = unquote(cur().text);
            i++;
        }
        if (at("[")) {
            Settings s = settings();
            t.color = s.color;
            t.note = s.note;
        }
        if (!at("{")) return;
        tables.putIfAbsent(name, t);
        i++;
        while (more() && !at("}")) {
            if (at("~")) { // ponytail: ~partial injections are skipped, TablePartial columns don't show up
                i += 2;
            } else if (at("note") && (peek(1).text.equals(":") || peek(1).text.equals("{"))) {
                i++;
                t.note = noteValue();
            } else if (peek(1).text.equals("{")) { // indexes { }, records { }, checks { }
                i++;
                skipBlock();
            } else if (isWord(cur())) {
                column(t);
            } else {
                i++;
            }
        }
        i++;
    }

    private void column(Table t) {
        String name = unquote(cur().text);
        i++;
        int typeStart = cur().offset, typeEnd = typeStart;
        while (more() && !cur().nl && !at("}") && !(at("[") && !peek(1).text.equals("]"))) {
            typeEnd = cur().end();
            i++;
        }
        String type = typeEnd > typeStart ? unquote(src.substring(typeStart, typeEnd).trim()) : "";
        Settings s = at("[") && !cur().nl ? settings() : new Settings();
        t.columns.add(new Column(name, type, s.pk, s.note));
        for (Object[] r : s.refs) {
            Endpoint e = (Endpoint) r[1];
            rawRefs.add(new Ref(t.name, List.of(name), (String) r[0], e.table, e.cols));
        }
    }

    private Settings settings() { // cursor on '['
        Settings s = new Settings();
        i++;
        while (more() && !at("]")) {
            String k = cur().text.toLowerCase(Locale.ROOT);
            boolean colon = peek(1).text.equals(":");
            if (k.equals("pk")) {
                s.pk = true;
            } else if (k.equals("primary") && peek(1).text.equalsIgnoreCase("key")) {
                s.pk = true;
            } else if (k.equals("note") && colon) {
                i += 2;
                s.note = unquote(cur().text);
            } else if ((k.equals("headercolor") || k.equals("color")) && colon) {
                i += 2;
                s.color = color(cur().text);
            } else if (k.equals("ref") && colon) {
                i += 2;
                String op = cur().text;
                i++;
                Endpoint e = endpoint();
                if (e != null && OPS.contains(op)) s.refs.add(new Object[] {op, e});
            }
            while (more() && !at(",") && !at("]")) i++; // rest of this setting
            if (at(",")) i++;
        }
        i++;
        return s;
    }

    private void ref() {
        i++;
        if (!at(":") && !at("{")) i++; // optional ref name
        if (at(":")) {
            i++;
            refLine();
        } else if (at("{")) {
            i++;
            while (more() && !at("}")) {
                int before = i;
                refLine();
                if (i == before) i++;
            }
            i++;
        }
    }

    private void refLine() {
        Endpoint a = endpoint();
        String op = cur().text;
        i++;
        Endpoint b = endpoint();
        if (at("[")) settings(); // [delete: cascade] etc.
        if (a != null && b != null && OPS.contains(op)) rawRefs.add(new Ref(a.table, a.cols, op, b.table, b.cols));
    }

    /** {@code table.col}, {@code schema.table.col} or composite {@code table.(a, b)}; null if malformed. */
    private Endpoint endpoint() {
        List<String> parts = new ArrayList<>();
        List<String> cols = null;
        while (more() && (isWord(cur()) || at("("))) {
            if (at("(")) {
                i++;
                cols = new ArrayList<>();
                while (more() && !at(")")) {
                    if (!at(",")) cols.add(unquote(cur().text));
                    i++;
                }
                i++;
                break;
            }
            parts.add(unquote(cur().text));
            i++;
            if (!at(".")) break;
            i++;
        }
        if (cols == null) {
            if (parts.size() < 2) return null;
            cols = List.of(parts.removeLast());
        }
        return parts.isEmpty() ? null : new Endpoint(String.join(".", parts), cols);
    }

    private void group() {
        i++;
        String name = name();
        Color color = at("[") ? settings().color : null;
        if (!at("{")) return;
        i++;
        List<String> members = new ArrayList<>();
        while (more() && !at("}")) {
            if (at("note") && (peek(1).text.equals(":") || peek(1).text.equals("{"))) {
                i++;
                noteValue();
            } else if (isWord(cur())) {
                members.add(name());
            } else {
                i++;
            }
        }
        i++;
        groups.add(new Group(name, members, color));
    }

    /** After {@code Note}: either {@code : 'text'} or {@code { 'text' }}. */
    private String noteValue() {
        if (at(":")) {
            i++;
            String note = unquote(cur().text);
            i++;
            return note;
        }
        String note = null;
        if (at("{")) {
            int start = i;
            skipBlock();
            for (int k = start; k < i; k++) if (toks.get(k).kind == Kind.STRING && note == null) note = unquote(toks.get(k).text);
        }
        return note;
    }

    /** Dotted, possibly quoted name; the default {@code public.} schema is dropped. */
    private String name() {
        if (!isWord(cur())) return "";
        StringBuilder sb = new StringBuilder(unquote(cur().text));
        i++;
        while (at(".") && isWord(peek(1))) {
            sb.append('.').append(unquote(peek(1).text));
            i += 2;
        }
        return stripPublic(sb.toString());
    }

    private void resolve() {
        Map<String, String> alias = new HashMap<>();
        for (Table t : tables.values()) if (t.alias != null) alias.put(t.alias, t.name);
        for (Ref r : rawRefs) {
            String a = tableName(r.from, alias), b = tableName(r.to, alias);
            if (a != null && b != null) refs.add(new Ref(a, r.fromCols, r.op, b, r.toCols));
        }
        groups.replaceAll(g -> new Group(g.name, g.tables.stream().map(n -> tableName(n, alias))
                .filter(n -> n != null).toList(), g.color));
    }

    private String tableName(String n, Map<String, String> alias) {
        n = stripPublic(n);
        return tables.containsKey(n) ? n : alias.get(n);
    }

    private static String stripPublic(String n) { return n.startsWith("public.") ? n.substring(7) : n; }

    static String unquote(String s) {
        if (s.length() >= 6 && s.startsWith("'''") && s.endsWith("'''")) return s.substring(3, s.length() - 3).stripIndent().strip();
        if (s.length() >= 2 && "'\"`".indexOf(s.charAt(0)) >= 0 && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1).replace("\\" + s.charAt(0), String.valueOf(s.charAt(0)));
        }
        return s;
    }

    static Color color(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        try {
            return h.length() == 6 ? new Color(Integer.parseInt(h, 16)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
