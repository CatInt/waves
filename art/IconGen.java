// Generates every Waves app icon asset from one geometry definition.
//
//   java art/IconGen.java                     (run from the repo root; needs only a JDK)
//   java art/IconGen.java --preview out.png   (renders a 512 px, a 128 px and a 32 px preview only)
//
// Outputs:
//   art/waves-icon.svg                                   master artwork (1024 macOS tile)
//   composeApp/src/androidMain/res/drawable/ic_launcher_{background,foreground,monochrome}.xml, ic_stat_waves.xml
//   composeApp/src/desktopMain/resources/waves-icon.png  window/dock icon (512 px)
//   composeApp/src/desktopMain/resources/menu-bar-waves.txt  the lines as path data, drawn by the macOS menu bar pill
//   composeApp/icons/waves.icns                          via iconutil (macOS only)
//
// The artwork is "Waves": the broadcast symbol turned into Hokusai's Great Wave. Three arcs rise around an unseen
// source on the sea, and each arc breaks at its end into a curling crest, nested like the swells of a wave, with a
// small snow-capped mountain far off beneath them. White lines of one width on a soft blue tile.
// Everything is drawn in a 1024 canvas once and scaled for each output; lines may run past the tile and are clipped.
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public class IconGen {
    static final Color BG = color("bg", 0x76A2E1);
    static final Color LINE = color("line", 0xFFFFFF);

    static Color color(String name, int rgb) {
        String v = System.getProperty(name);
        return new Color(v == null ? rgb : Integer.parseInt(v, 16));
    }

    // ---- a tiny path DSL, so one geometry feeds SVG, Android vectors and Java2D ----------------------------------

    static final class P {
        final List<double[]> cmds = new ArrayList<>(); // [type, coords...]; type 0=M 1=L 2=C 3=Z

        P m(double x, double y) { cmds.add(new double[]{0, x, y}); return this; }
        P l(double x, double y) { cmds.add(new double[]{1, x, y}); return this; }
        P c(double x1, double y1, double x2, double y2, double x, double y) { cmds.add(new double[]{2, x1, y1, x2, y2, x, y}); return this; }
        P z() { cmds.add(new double[]{3}); return this; }

        String data(double scale, double dx, double dy) {
            var sb = new StringBuilder();
            for (double[] c : cmds) {
                if (sb.length() > 0) sb.append(' ');
                sb.append("MLCZ".charAt((int) c[0]));
                for (int i = 1; i < c.length; i++) sb.append(i == 1 ? "" : (i % 2 == 1 ? " " : ",")).append(f(c[i] * scale + (i % 2 == 1 ? dx : dy)));
            }
            return sb.toString();
        }

        Path2D.Double java2d() {
            var p = new Path2D.Double();
            for (double[] c : cmds) {
                switch ((int) c[0]) {
                    case 0 -> p.moveTo(c[1], c[2]);
                    case 1 -> p.lineTo(c[1], c[2]);
                    case 2 -> p.curveTo(c[1], c[2], c[3], c[4], c[5], c[6]);
                    default -> p.closePath();
                }
            }
            return p;
        }
    }

    /** A smooth curve through [pts] (Catmull-Rom, written as cubics). */
    static P smooth(List<double[]> pts) {
        P p = new P().m(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 0; i < pts.size() - 1; i++) {
            double[] a = pts.get(Math.max(0, i - 1)), b = pts.get(i), c = pts.get(i + 1), d = pts.get(Math.min(pts.size() - 1, i + 2));
            p.c(b[0] + (c[0] - a[0]) / 6, b[1] + (c[1] - a[1]) / 6, c[0] - (d[0] - b[0]) / 6, c[1] - (d[1] - b[1]) / 6, c[0], c[1]);
        }
        return p;
    }

    // ---- artwork (1024 canvas) ----------------------------------------------------------------------------------

    static double prop(String k, double d) { return Double.parseDouble(System.getProperty(k, String.valueOf(d))); }

    static final double TILE = 928, TILE_OFF = 48, TILE_R = 196;
    static final double LINE_W = prop("lineW", 15);

    /** The broadcast source. Angles below are degrees around it with y pointing down: 180 is left, 270 is up. */
    static final double SX = prop("sx", 235), SY = prop("sy", 835);

    /**
     * The three signal arcs: (radius, angle where the arc breaks, curl size as a share of the radius).
     * Each one rises from off the left edge, arches over the source and breaks a little further round than
     * the one inside it, so the crests sit in a staggered row like the swells of one wave.
     */
    static final double[][] CRESTS = {{250, 272, 0.28}, {455, 302, 0.22}, {660, 330, 0.19}};

    /** How far the crest curls round after it breaks, and how much it tightens on the way. */
    static final double CURL_TURN = prop("curlTurn", 300), CURL_TIGHTEN = prop("curlTighten", 0.62);

    /** Forward lean: higher points shift right by this share of their height above the source, like a wave about to break. */
    static final double LEAN = prop("lean", 0.32);

    static double[] around(double cx, double cy, double r, double deg) {
        double a = Math.toRadians(deg);
        return new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a)};
    }

    static double[] lean(double[] p) { return new double[]{p[0] + LEAN * (SY - p[1]), p[1]}; }

    /**
     * One signal arc around the source, clockwise from the left, that breaks into a curl at [breakAt]. The curl
     * starts on a circle touching the arc there, so the two share a tangent, then winds tighter.
     */
    static P crest(double r, double breakAt, double curl) {
        var pts = new ArrayList<double[]>();
        for (double a = 172; a < breakAt; a += 6) pts.add(around(SX, SY, r, a));
        double[] e = around(SX, SY, r, breakAt);
        double rc = curl * r;
        double cx = e[0] + (SX - e[0]) / r * rc, cy = e[1] + (SY - e[1]) / r * rc;
        for (double s = 0; s <= 1.0001; s += 1.0 / 40) {
            double rr = rc * (1 - CURL_TIGHTEN * Math.pow(s, 1.5));
            pts.add(around(cx, cy, rr, breakAt + CURL_TURN * s));
        }
        pts.replaceAll(IconGen::lean);
        return smooth(pts);
    }

    static List<P> crests() {
        var out = new ArrayList<P>();
        for (double[] c : CRESTS) out.add(crest(c[0], c[1], c[2]));
        return out;
    }

    /** A second, shorter arc just inside each crest, stopping before the curl, like the grain of the water. */
    static final double INNER_GAP = prop("innerGap", 56), INNER_STOP = prop("innerStop", 22);

    static List<P> innerLines() {
        var out = new ArrayList<P>();
        for (double[] c : CRESTS) {
            var pts = new ArrayList<double[]>();
            for (double a = 172; a <= c[1] - INNER_STOP; a += 6) pts.add(lean(around(SX, SY, c[0] - INNER_GAP, a)));
            out.add(smooth(pts));
        }
        return out;
    }

    /** Where the mountain's summit stands, and how much taller than its base shape it is drawn. */
    static final double MX = prop("mountainX", 620), MH = prop("mountainH", 1.4);

    /** Height above the sea line, scaled by {@link #MH}. */
    static double up(double h) { return SY - h * MH; }

    /** A small mountain far off under the crests, with a jagged snow line below its summit. */
    static P mountain() {
        return new P().m(MX - 170, SY)
            .c(MX - 90, up(15), MX - 40, up(75), MX - 15, up(115))
            .c(MX - 7, up(127), MX + 7, up(127), MX + 15, up(115))
            .c(MX + 40, up(75), MX + 90, up(15), MX + 180, SY);
    }

    static P snow() {
        return new P().m(MX - 33, up(88)).l(MX - 17, up(74)).l(MX - 4, up(86)).l(MX + 10, up(72)).l(MX + 30, up(88));
    }

    /** The sea surface from the source to the foot of the mountain, and on from its other foot. */
    static List<P> sea() {
        double from = SX, to = MX - 200;
        return List.of(
            new P().m(from, SY).c(from + (to - from) * 0.35, SY - 10, from + (to - from) * 0.65, SY + 8, to, SY),
            new P().m(MX + 210, SY).c(MX + 260, SY - 8, MX + 310, SY + 6, MX + 360, SY));
    }

    // ---- output -----------------------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".");
        if (args.length > 1 && args[0].equals("--preview")) {
            ImageIO.write(render(512), "png", new File(args[1]));
            ImageIO.write(render(128), "png", new File(args[1].replace(".png", "-128.png")));
            ImageIO.write(render(32), "png", new File(args[1].replace(".png", "-32.png")));
            return;
        }
        svg(root.resolve("art/waves-icon.svg"));
        android(root.resolve("composeApp/src/androidMain/res/drawable"));
        Path png = root.resolve("composeApp/src/desktopMain/resources/waves-icon.png");
        Files.createDirectories(png.getParent());
        ImageIO.write(render(512), "png", png.toFile());
        lines(root.resolve("composeApp/src/desktopMain/resources/menu-bar-waves.txt"));
        icns(root.resolve("composeApp/icons/waves.icns"));
    }

    static String f(double v) { return String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", ""); }

    static String hex(Color c) { return String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    /** Every output draws the same strokes; there are no fills. */
    interface Sink {
        void stroke(P p, double width, Color color);
    }

    /** [small] thickens the lines and drops the fine ones so the crests still read at 16-32 px. */
    static void draw(Sink s, boolean small) {
        double w = LINE_W * (small ? 2.2 : 1);
        if (!small) {
            for (P p : innerLines()) s.stroke(p, w, LINE);
            s.stroke(snow(), w, LINE);
            for (P p : sea()) s.stroke(p, w, LINE);
        }
        s.stroke(mountain(), w, LINE);
        for (P p : crests()) s.stroke(p, w, LINE);
    }

    /** What [draw] paints inside the tile, for fitting the notification icon. */
    static Rectangle2D symbolBounds() {
        Rectangle2D[] b = {null};
        draw((p, w, c) -> {
            var r = p.java2d().getBounds2D();
            r.setRect(r.getX() - w / 2, r.getY() - w / 2, r.getWidth() + w, r.getHeight() + w);
            if (b[0] == null) b[0] = r; else b[0].add(r);
        }, false);
        return b[0].createIntersection(new Rectangle2D.Double(TILE_OFF, TILE_OFF, TILE, TILE));
    }

    static void svg(Path out) throws IOException {
        var sb = new StringBuilder();
        String tile = "x=\"" + f(TILE_OFF) + "\" y=\"" + f(TILE_OFF) + "\" width=\"" + f(TILE) + "\" height=\"" + f(TILE)
            + "\" rx=\"" + f(TILE_R) + "\"";
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1024\" height=\"1024\" viewBox=\"0 0 1024 1024\">\n");
        sb.append("  <!-- Generated by art/IconGen.java; edit the generator, not this file. -->\n");
        sb.append("  <defs><clipPath id=\"tile\"><rect " + tile + "/></clipPath></defs>\n");
        sb.append("  <rect " + tile + " fill=\"#" + hex(BG) + "\"/>\n");
        sb.append("  <g clip-path=\"url(#tile)\">\n");
        draw((p, w, c) -> sb.append("    <path fill=\"none\" stroke=\"#" + hex(c) + "\" stroke-width=\"" + f(w)
            + "\" stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"" + p.data(1, 0, 0) + "\"/>\n"), false);
        sb.append("  </g>\n</svg>\n");
        Files.writeString(out, sb.toString());
    }

    /** One path per line in the 1024 canvas (M/L/C commands), all stroked in white at {@link #LINE_W}. */
    static void lines(Path out) throws IOException {
        var sb = new StringBuilder();
        draw((p, w, c) -> sb.append(p.data(1, 0, 0)).append('\n'), false);
        Files.writeString(out, sb.toString());
    }

    /**
     * Adaptive icon layers on the 108dp canvas. Launchers show roughly the middle 72dp, so the tile's
     * [ANDROID_FROM, ANDROID_TO] square is mapped onto that; the swells run on past it to the layer's edges.
     */
    static final double ANDROID_FROM = 80, ANDROID_TO = 940;

    static void android(Path dir) throws IOException {
        Files.createDirectories(dir);
        String header = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by art/IconGen.java -->\n";
        String open = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:width=\"%sdp\" android:height=\"%sdp\"\n"
            + "    android:viewportWidth=\"%s\" android:viewportHeight=\"%s\">\n";
        Files.writeString(dir.resolve("ic_launcher_background.xml"), header + open.formatted("108", "108", "108", "108")
            + "    <path android:fillColor=\"#FF" + hex(BG) + "\" android:pathData=\"M0,0h108v108h-108z\" />\n</vector>\n");
        double scale = 72 / (ANDROID_TO - ANDROID_FROM), d = 54 - (ANDROID_FROM + ANDROID_TO) / 2 * scale;
        Files.writeString(dir.resolve("ic_launcher_foreground.xml"), header + open.formatted("108", "108", "108", "108")
            + vector(scale, d, d, false, false) + "</vector>\n");
        Files.writeString(dir.resolve("ic_launcher_monochrome.xml"), header + open.formatted("108", "108", "108", "108")
            + vector(scale, d, d, true, false) + "</vector>\n");
        double[] st = fit(symbolBounds(), 24, 22);
        Files.writeString(dir.resolve("ic_stat_waves.xml"), header
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:width=\"24dp\" android:height=\"24dp\"\n"
            + "    android:viewportWidth=\"24\" android:viewportHeight=\"24\"\n"
            + "    android:tint=\"#FFFFFFFF\">\n"
            + vector(st[0], st[1], st[2], true, true) + "</vector>\n");
    }

    /** Scale [bounds] so its longest side is [target] and it sits in the middle of a [view] square. */
    static double[] fit(Rectangle2D b, double view, double target) {
        double scale = target / Math.max(b.getWidth(), b.getHeight());
        return new double[]{scale, view / 2 - b.getCenterX() * scale, view / 2 - b.getCenterY() * scale};
    }

    /** The artwork as vector paths; [mono] draws every line in white. */
    static String vector(double scale, double dx, double dy, boolean mono, boolean small) {
        var sb = new StringBuilder();
        draw((p, w, c) -> sb.append("    <path android:strokeColor=\"#FF" + hex(mono ? Color.WHITE : c) + "\" android:strokeWidth=\""
            + f(w * scale) + "\"\n"
            + "        android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\"\n"
            + "        android:pathData=\"" + p.data(scale, dx, dy) + "\" />\n"), small);
        return sb.toString();
    }

    static BufferedImage render(int size) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size / 1024.0;
        g.scale(s, s);
        var tile = new RoundRectangle2D.Double(TILE_OFF, TILE_OFF, TILE, TILE, TILE_R * 2, TILE_R * 2);
        for (int i = 14; i >= 1; i--) {
            double grow = i * 2.2;
            g.setColor(new Color(0, 0, 0, 6));
            g.fill(new RoundRectangle2D.Double(TILE_OFF - grow, TILE_OFF - grow + 14, TILE + 2 * grow, TILE + 2 * grow,
                (TILE_R + grow) * 2, (TILE_R + grow) * 2));
        }
        g.setColor(BG);
        g.fill(tile);
        g.setClip(tile);
        draw((p, w, c) -> {
            g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(c);
            g.draw(p.java2d());
        }, size <= 64);
        g.dispose();
        return img;
    }

    static void icns(Path out) throws Exception {
        Path set = Files.createTempDirectory("waves").resolve("waves.iconset");
        Files.createDirectories(set);
        for (int px : new int[]{16, 32, 128, 256, 512}) {
            ImageIO.write(render(px), "png", set.resolve("icon_" + px + "x" + px + ".png").toFile());
            ImageIO.write(render(px * 2), "png", set.resolve("icon_" + px + "x" + px + "@2x.png").toFile());
        }
        Files.createDirectories(out.getParent());
        Process p = new ProcessBuilder("iconutil", "-c", "icns", set.toString(), "-o", out.toString()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("iconutil failed");
        System.out.println("Wrote " + out);
    }
}
