package dbml;

import dbml.DbmlModel.Column;
import dbml.DbmlModel.Group;
import dbml.DbmlModel.Ref;
import dbml.DbmlModel.Table;
import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;

/** ER diagram in the dbdiagram.io look: blue headers, light rows, orthogonal rounded connectors with 1 / * labels. */
final class DbmlDiagram extends JComponent {
    // dbdiagram.io palette, sampled from its screenshots
    static final Color CANVAS = new Color(0xFAFAFC);
    static final Color HEADER = new Color(0x1A6694);
    static final Color ROW = new Color(0xF6F6F4);
    static final Color ROW_HOT = new Color(0xDAEEF4);
    static final Color TEXT = new Color(0x565654);
    static final Color TYPE = new Color(0x9C9C9A);
    static final Color LINE = new Color(0x62A2CC);
    static final Color LINE_DIM = new Color(0xC3C9CE);
    static final int HEADER_H = 24, ROW_H = 22, MIN_W = 150, PAD = 10, GAP_X = 110, GAP_Y = 44, MARGIN = 48;
    static final int GROUP_PAD = 18, GROUP_TITLE = 22;

    private static final String FAMILY = UIManager.getFont("Label.font") != null ? UIManager.getFont("Label.font").getFamily() : Font.SANS_SERIF;
    private static final Font NAME = new Font(FAMILY, Font.PLAIN, 13), BOLD = NAME.deriveFont(Font.BOLD), SMALL = NAME.deriveFont(11f);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private DbmlModel model = DbmlModel.parse("");
    private Map<String, Rectangle> box = Map.of();
    // ponytail: dragged positions live in memory only (reset on reopen); persist per file if that gets annoying
    private final Map<String, Point> moved = new HashMap<>();
    private final IntConsumer navigate;
    private String error, hover, drag;
    private boolean highlightAll = true, needsFit = true;
    private double zoom = 1, tx, ty;
    private Point last, grab;

    DbmlDiagram(IntConsumer navigate) {
        this.navigate = navigate;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                last = e.getPoint();
                drag = tableAt(e.getPoint());
                if (drag != null) {
                    Point2D w = toWorld(e.getPoint());
                    Rectangle b = box.get(drag);
                    grab = new Point((int) w.getX() - b.x, (int) w.getY() - b.y);
                }
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (drag != null) {
                    Point2D w = toWorld(e.getPoint());
                    Rectangle b = box.get(drag);
                    if (b == null) return; // table vanished by a re-parse mid-drag
                    b.setLocation((int) w.getX() - grab.x, (int) w.getY() - grab.y);
                    moved.put(drag, b.getLocation());
                } else if (last != null) {
                    tx += e.getX() - last.x;
                    ty += e.getY() - last.y;
                }
                last = e.getPoint();
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) { drag = null; }

            @Override public void mouseMoved(MouseEvent e) {
                String h = tableAt(e.getPoint());
                if (!Objects.equals(h, hover)) {
                    hover = h;
                    repaint();
                }
            }

            @Override public void mouseClicked(MouseEvent e) {
                String t = e.getClickCount() == 2 ? tableAt(e.getPoint()) : null;
                if (t != null) navigate.accept(model.tables.get(t).offset);
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(Math.pow(1.1, -e.getPreciseWheelRotation()), e.getPoint());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // ---- view API ----

    void setModel(DbmlModel m) {
        error = m.error;
        if (error == null) {
            model = m;
            relayout();
        }
        repaint();
    }

    void resetLayout() {
        moved.clear();
        relayout();
        fit();
    }

    void fit() {
        needsFit = true;
        repaint();
    }

    void zoomBy(double f) { zoomAt(f, new Point(getWidth() / 2, getHeight() / 2)); }

    boolean isHighlightAll() { return highlightAll; }

    void setHighlightAll(boolean on) {
        highlightAll = on;
        repaint();
    }

    BufferedImage toImage(double scale) { return toImage(model, box, highlightAll, scale); }

    String toSvg() { return toSvg(model, box, highlightAll); }

    private void relayout() {
        box = layout(model);
        moved.forEach((t, p) -> { if (box.containsKey(t)) box.get(t).setLocation(p); });
    }

    private void zoomAt(double f, Point p) {
        double z = Math.max(0.1, Math.min(4, zoom * f));
        tx = p.x - (p.x - tx) * z / zoom;
        ty = p.y - (p.y - ty) * z / zoom;
        zoom = z;
        repaint();
    }

    private Point2D toWorld(Point p) { return new Point2D.Double((p.x - tx) / zoom, (p.y - ty) / zoom); }

    private String tableAt(Point p) {
        Point2D w = toWorld(p);
        String hit = null;
        for (Map.Entry<String, Rectangle> e : box.entrySet()) if (e.getValue().contains(w)) hit = e.getKey(); // topmost = last painted
        return hit;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        String t = tableAt(e.getPoint());
        if (t == null) return null;
        Table table = model.tables.get(t);
        int row = (int) Math.floor((toWorld(e.getPoint()).getY() - box.get(t).y - HEADER_H) / ROW_H);
        String note = row >= 0 && row < table.columns.size() ? table.columns.get(row).note() : table.note;
        return note == null ? null : "<html>" + note.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>";
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(CANVAS);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (needsFit && getWidth() > 0 && !box.isEmpty()) {
            Rectangle b = bounds(model, box);
            zoom = Math.min(1, Math.min((double) getWidth() / b.width, (double) getHeight() / b.height));
            tx = (getWidth() - b.width * zoom) / 2 - b.x * zoom;
            ty = (getHeight() - b.height * zoom) / 2 - b.y * zoom;
            needsFit = false;
        }
        Graphics2D w = (Graphics2D) g.create();
        w.translate(tx, ty);
        w.scale(zoom, zoom);
        paint(w, model, box, hover, highlightAll);
        w.dispose();
        hints(g);
        g.setFont(SMALL);
        if (error != null) {
            g.setColor(new Color(0xC0392B));
            g.drawString("Syntax error: " + error + " (showing last valid diagram)", 10, 18);
        } else if (box.isEmpty()) {
            g.setColor(TYPE);
            g.drawString("No tables yet", 10, 18);
        }
        g.dispose();
    }

    // ---- layout ----

    /** Tables and TableGroup blocks laid out by {@link #arrange}; each group is arranged on its own first. */
    static Map<String, Rectangle> layout(DbmlModel m) {
        Map<String, Rectangle> box = new LinkedHashMap<>();
        for (Table t : m.tables.values()) box.put(t.name, new Rectangle(0, 0, width(t), HEADER_H + t.columns.size() * ROW_H));
        List<String[]> edges = new ArrayList<>(); // {fk table, pk table}
        for (Ref r : m.refs) edges.add(r.op().equals("<") ? new String[] {r.to(), r.from()} : new String[] {r.from(), r.to()});

        Map<String, String> owner = new HashMap<>(); // table -> its group node ("\0" + group name)
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (Group g : m.groups) {
            for (String t : g.tables()) {
                if (owner.putIfAbsent(t, "\0" + g.name()) == null) members.computeIfAbsent("\0" + g.name(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<String, Rectangle> nodes = new LinkedHashMap<>();
        for (String t : box.keySet()) {
            String id = owner.getOrDefault(t, t);
            if (nodes.containsKey(id)) continue;
            if (id.equals(t)) {
                nodes.put(t, box.get(t));
                continue;
            }
            Map<String, Rectangle> inner = new LinkedHashMap<>();
            for (String x : members.get(id)) inner.put(x, box.get(x));
            arrange(inner, edges);
            Rectangle u = bounds(inner.values());
            for (Rectangle b : inner.values()) b.translate(GROUP_PAD - u.x, GROUP_PAD + GROUP_TITLE - u.y);
            nodes.put(id, new Rectangle(0, 0, u.width + 2 * GROUP_PAD, u.height + 2 * GROUP_PAD + GROUP_TITLE));
        }
        List<String[]> outer = new ArrayList<>();
        for (String[] e : edges) outer.add(new String[] {owner.getOrDefault(e[0], e[0]), owner.getOrDefault(e[1], e[1])});
        arrange(nodes, outer);
        for (Map.Entry<String, String> e : owner.entrySet()) box.get(e.getKey()).translate(nodes.get(e.getValue()).x, nodes.get(e.getValue()).y);
        return box;
    }

    private static Rectangle bounds(Iterable<Rectangle> rects) {
        Rectangle u = null;
        for (Rectangle b : rects) u = u == null ? new Rectangle(b) : u.union(b);
        return u;
    }

    /**
     * Layered left-to-right placement of {@code box} (sizes in, locations out): nodes holding foreign keys sit left of
     * the nodes they reference. Edges with an end outside {@code box} are ignored.
     */
    private static void arrange(Map<String, Rectangle> box, List<String[]> edges) {
        Map<String, Set<String>> out = new HashMap<>(), adj = new HashMap<>();
        for (String[] e : edges) {
            if (e[0].equals(e[1]) || !box.containsKey(e[0]) || !box.containsKey(e[1])) continue;
            out.computeIfAbsent(e[0], k -> new LinkedHashSet<>()).add(e[1]);
            adj.computeIfAbsent(e[0], k -> new LinkedHashSet<>()).add(e[1]);
            adj.computeIfAbsent(e[1], k -> new LinkedHashSet<>()).add(e[0]);
        }
        Map<String, Integer> rank = new HashMap<>();
        int maxRank = 0;
        for (String t : box.keySet()) maxRank = Math.max(maxRank, rank(t, out, rank, new HashSet<>()));

        // columns: 0..maxRank for connected nodes, one extra column for isolated ones
        List<List<String>> cols = new ArrayList<>();
        for (int c = 0; c <= maxRank + 1; c++) cols.add(new ArrayList<>());
        for (String t : box.keySet()) cols.get(adj.containsKey(t) ? maxRank - rank.get(t) : maxRank + 1).add(t);

        // barycenter sweeps to reduce crossings
        for (int pass = 0; pass < 4; pass++) {
            boolean fwd = pass % 2 == 0;
            for (int c = fwd ? 1 : cols.size() - 2; fwd ? c < cols.size() : c >= 0; c += fwd ? 1 : -1) {
                List<String> ref = cols.get(fwd ? c - 1 : c + 1), col = cols.get(c);
                Map<String, Double> key = new HashMap<>();
                for (int k = 0; k < col.size(); k++) {
                    double sum = 0;
                    int n = 0;
                    for (String nb : adj.getOrDefault(col.get(k), Set.of())) {
                        int idx = ref.indexOf(nb);
                        if (idx >= 0) {
                            sum += idx;
                            n++;
                        }
                    }
                    key.put(col.get(k), n > 0 ? sum / n : k);
                }
                col.sort((a, b) -> Double.compare(key.get(a), key.get(b)));
            }
        }

        // wrap tall columns, then place them left to right, vertically centered
        long area = 0;
        for (Rectangle b : box.values()) area += (long) (b.width + GAP_X) * (b.height + GAP_Y);
        int limit = Math.max(450, (int) (Math.sqrt(area) * 0.8));
        List<List<String>> subs = new ArrayList<>();
        for (List<String> col : cols) {
            List<String> sub = new ArrayList<>();
            int h = 0;
            for (String t : col) {
                if (!sub.isEmpty() && h + box.get(t).height > limit) {
                    subs.add(sub);
                    sub = new ArrayList<>();
                    h = 0;
                }
                sub.add(t);
                h += box.get(t).height + GAP_Y;
            }
            if (!sub.isEmpty()) subs.add(sub);
        }
        int tallest = 0;
        for (List<String> sub : subs) tallest = Math.max(tallest, colHeight(sub, box));
        int x = 0;
        for (List<String> sub : subs) {
            int y = (tallest - colHeight(sub, box)) / 2, w = 0;
            for (String t : sub) {
                Rectangle b = box.get(t);
                b.setLocation(x, y);
                y += b.height + GAP_Y;
                w = Math.max(w, b.width);
            }
            x += w + GAP_X;
        }
    }

    private static int colHeight(List<String> sub, Map<String, Rectangle> box) {
        int h = -GAP_Y;
        for (String t : sub) h += box.get(t).height + GAP_Y;
        return h;
    }

    /** Longest chain of foreign-key hops starting at {@code t}; cycles are cut. */
    private static int rank(String t, Map<String, Set<String>> out, Map<String, Integer> memo, Set<String> stack) {
        Integer v = memo.get(t);
        if (v != null) return v;
        if (!stack.add(t)) return 0;
        int r = 0;
        for (String pk : out.getOrDefault(t, Set.of())) r = Math.max(r, 1 + rank(pk, out, memo, stack));
        stack.remove(t);
        memo.put(t, r);
        return r;
    }

    private static int width(Table t) {
        double w = textWidth(BOLD, t.name);
        for (Column c : t.columns) w = Math.max(w, textWidth(c.pk() ? BOLD : NAME, c.name()) + 24 + textWidth(SMALL, c.type()));
        return Math.max(MIN_W, (int) Math.ceil(w) + 2 * PAD);
    }

    private static double textWidth(Font f, String s) { return f.getStringBounds(s, FRC).getWidth(); }

    // ---- painting (shared by screen, PNG and SVG) ----

    private record Route(Path2D path, double x1, double y1, int dir1, double x2, double y2, int dir2) {}

    static void paint(Graphics2D g, DbmlModel m, Map<String, Rectangle> box, String focus, boolean all) {
        hints(g);
        for (Group gr : m.groups) {
            Rectangle u = groupBounds(gr, box);
            if (u == null) continue;
            Color c = gr.color() != null ? gr.color() : HEADER;
            RoundRectangle2D shape = new RoundRectangle2D.Double(u.x, u.y, u.width, u.height, 14, 14);
            g.setColor(alpha(c, 22));
            g.fill(shape);
            g.setColor(alpha(c, 110));
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
            g.setColor(c);
            g.setFont(BOLD);
            text(g, gr.name(), u.x + 12, u.y, 28);
        }

        Set<String> hot = new HashSet<>();
        List<Ref> lit = new ArrayList<>();
        List<Route> litRoutes = new ArrayList<>();
        g.setColor(LINE_DIM);
        g.setStroke(new BasicStroke(1.3f));
        for (Ref r : m.refs) {
            Route rt = route(m, box, r);
            if (all || r.from().equals(focus) || r.to().equals(focus)) {
                lit.add(r);
                litRoutes.add(rt);
                r.fromCols().forEach(c -> hot.add(r.from() + '\0' + c));
                r.toCols().forEach(c -> hot.add(r.to() + '\0' + c));
            } else {
                g.draw(rt.path);
            }
        }
        g.setColor(LINE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Route rt : litRoutes) g.draw(rt.path);

        for (Table t : m.tables.values()) {
            Rectangle b = box.get(t.name);
            Color hc = t.color != null ? t.color : HEADER;
            g.setColor(hc);
            g.fillRect(b.x, b.y, b.width, HEADER_H);
            g.setColor(hc.darker());
            g.fillRect(b.x, b.y + HEADER_H - 1, b.width, 1);
            g.setColor(luminance(hc) > 0.6 ? TEXT : Color.WHITE);
            g.setFont(BOLD);
            text(g, t.name, b.x + PAD, b.y, HEADER_H);
            for (int k = 0; k < t.columns.size(); k++) {
                Column c = t.columns.get(k);
                int y = b.y + HEADER_H + k * ROW_H;
                g.setColor(hot.contains(t.name + '\0' + c.name()) ? ROW_HOT : ROW);
                g.fillRect(b.x, y, b.width, ROW_H);
                g.setColor(TEXT);
                g.setFont(c.pk() ? BOLD : NAME);
                text(g, c.name(), b.x + PAD, y, ROW_H);
                g.setColor(TYPE);
                g.setFont(SMALL);
                text(g, c.type(), (float) (b.x + b.width - PAD - textWidth(SMALL, c.type())), y, ROW_H);
            }
        }

        // cardinality labels on top of everything
        g.setColor(TEXT);
        g.setFont(SMALL);
        for (int k = 0; k < lit.size(); k++) {
            Ref r = lit.get(k);
            Route rt = litRoutes.get(k);
            boolean manyFrom = r.op().equals(">") || r.op().equals("<>"), manyTo = r.op().equals("<") || r.op().equals("<>");
            label(g, manyFrom ? "*" : "1", rt.x1, rt.y1, rt.dir1);
            label(g, manyTo ? "*" : "1", rt.x2, rt.y2, rt.dir2);
        }
    }

    private static void label(Graphics2D g, String s, double x, double y, int dir) {
        double w = textWidth(SMALL, s);
        g.drawString(s, (float) (dir > 0 ? x + 5 : x - 5 - w), (float) (y - 4));
    }

    /** Orthogonal connector from the source row's side to the target row's side, with rounded bends. */
    private static Route route(DbmlModel m, Map<String, Rectangle> box, Ref r) {
        Rectangle a = box.get(r.from()), b = box.get(r.to());
        double y1 = rowY(m.tables.get(r.from()), a, r.fromCols()), y2 = rowY(m.tables.get(r.to()), b, r.toCols());
        double x1, x2, mx;
        int d1, d2;
        // the vertical run sits in the gap just before the target, never inside a column the line skips over
        if (b.x >= a.getMaxX() + 30) { // target to the right
            x1 = a.getMaxX(); d1 = 1; x2 = b.x; d2 = -1; mx = Math.max((x1 + x2) / 2, x2 - GAP_X / 2.0);
        } else if (a.x >= b.getMaxX() + 30) { // target to the left
            x1 = a.x; d1 = -1; x2 = b.getMaxX(); d2 = 1; mx = Math.min((x1 + x2) / 2, x2 + GAP_X / 2.0);
        } else { // same column (or self reference): loop out on the right side
            x1 = a.getMaxX(); d1 = 1; x2 = b.getMaxX(); d2 = 1; mx = Math.max(x1, x2) + 28;
        }
        Path2D p = new Path2D.Double();
        p.moveTo(x1, y1);
        double dy = y2 - y1, r0 = Math.min(8, Math.min(Math.abs(dy) / 2, Math.min(Math.abs(mx - x1), Math.abs(x2 - mx))));
        double sy = Math.signum(dy), s1 = Math.signum(mx - x1), s2 = Math.signum(x2 - mx);
        p.lineTo(mx - s1 * r0, y1);
        p.quadTo(mx, y1, mx, y1 + sy * r0);
        p.lineTo(mx, y2 - sy * r0);
        p.quadTo(mx, y2, mx + s2 * r0, y2);
        p.lineTo(x2, y2);
        return new Route(p, x1, y1, d1, x2, y2, d2);
    }

    private static double rowY(Table t, Rectangle b, List<String> cols) {
        int idx = cols.isEmpty() ? -1 : t.indexOf(cols.getFirst());
        return idx < 0 ? b.y + HEADER_H / 2.0 : b.y + HEADER_H + idx * ROW_H + ROW_H / 2.0;
    }

    private static Rectangle groupBounds(Group gr, Map<String, Rectangle> box) {
        Rectangle u = bounds(gr.tables().stream().map(box::get).filter(Objects::nonNull).toList());
        if (u != null) {
            u.grow(GROUP_PAD, GROUP_PAD);
            u.y -= GROUP_TITLE;
            u.height += GROUP_TITLE;
        }
        return u;
    }

    static Rectangle bounds(DbmlModel m, Map<String, Rectangle> box) {
        Rectangle u = bounds(box.values());
        for (Group gr : m.groups) {
            Rectangle g = groupBounds(gr, box);
            if (g != null) u = u.union(g);
        }
        if (u == null) return new Rectangle(0, 0, 1, 1);
        u.grow(MARGIN, MARGIN); // room for self-reference loops and labels
        return u;
    }

    private static void text(Graphics2D g, String s, float x, float y, float h) {
        if (s.isEmpty()) return;
        LineMetrics lm = g.getFont().getLineMetrics(s, FRC);
        g.drawString(s, x, y + (h + lm.getAscent() - lm.getDescent()) / 2);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    private static double luminance(Color c) { return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255; }

    // ---- export ----

    static BufferedImage toImage(DbmlModel m, Map<String, Rectangle> box, boolean all, double scale) {
        Rectangle b = bounds(m, box);
        BufferedImage img = new BufferedImage((int) Math.ceil(b.width * scale), (int) Math.ceil(b.height * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.translate(-b.x, -b.y);
        g.setColor(CANVAS);
        g.fill(b);
        paint(g, m, box, null, all);
        g.dispose();
        return img;
    }

    static String toSvg(DbmlModel m, Map<String, Rectangle> box, boolean all) {
        Rectangle b = bounds(m, box);
        SVGGraphics2D g = new SVGGraphics2D(GenericDOMImplementation.getDOMImplementation()
                .createDocument("http://www.w3.org/2000/svg", "svg", null));
        g.setSVGCanvasSize(b.getSize());
        g.translate(-b.x, -b.y);
        g.setColor(CANVAS);
        g.fill(b);
        paint(g, m, box, null, all);
        StringWriter out = new StringWriter();
        try {
            g.stream(out, true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // the IDE font (e.g. Inter) may be missing where the SVG is viewed
        return out.toString().replace("font-family:'" + FAMILY + "'", "font-family:'" + FAMILY + "',sans-serif");
    }
}
