/*
 * Generates the Bandwidth Console app icon at every size the installers need.
 *
 *   java packaging/resources/GenerateIcon.java packaging/resources
 *
 * Java2D rather than an SVG toolchain: the JDK is already a hard build
 * dependency, so the icons stay reproducible on any machine that can build the
 * console — no ImageMagick or librsvg to install.
 *
 * Outputs into <outdir>:
 *   bwconsole.iconset/   feed to `iconutil -c icns` for macOS
 *   bwconsole.ico        multi-resolution, PNG-compressed (Vista+)
 *   bwconsole.png        512x512 for Linux .desktop
 *
 * The mark is the architecture: a sending node and a receiving node joined by three
 * parallel streams — the fan-out the tool exists to measure.
 */

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;

public class GenerateIcon {

    // Palette lifted from console/src/main/resources/com/bwtest/console/app.css
    private static final Color BG_TOP    = new Color(0x1a2436);
    private static final Color BG_BOTTOM = new Color(0x0b0f17);
    private static final Color RIM       = new Color(0x2b3a52);
    private static final Color TEAL      = new Color(0x5eead4);
    private static final Color CYAN      = new Color(0x22d3ee);
    private static final Color SKY       = new Color(0x7dd3fc);

    /** Everything is authored in this logical space, then scaled to the target. */
    private static final double REF = 1024.0;

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "packaging/resources");
        Files.createDirectories(out);

        // macOS .iconset — iconutil requires exactly these names.
        Path iconset = out.resolve("bwconsole.iconset");
        Files.createDirectories(iconset);
        int[][] icns = {{16,1},{16,2},{32,1},{32,2},{128,1},{128,2},{256,1},{256,2},{512,1},{512,2}};
        for (int[] s : icns) {
            int pt = s[0], scale = s[1];
            String name = "icon_" + pt + "x" + pt + (scale == 2 ? "@2x" : "") + ".png";
            ImageIO.write(render(pt * scale), "png", iconset.resolve(name).toFile());
        }

        // Windows .ico
        int[] icoSizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : icoSizes) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(render(s), "png", b);
            pngs.add(b.toByteArray());
        }
        writeIco(out.resolve("bwconsole.ico"), icoSizes, pngs);

        // Linux
        ImageIO.write(render(512), "png", out.resolve("bwconsole.png").toFile());

        System.out.println("wrote icons to " + out.toAbsolutePath());
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / REF, size / REF);

        // Detail is dropped as the target shrinks: three streams a few pixels
        // apart average into a single smear well before 32px, so below that we
        // spend the pixels on one unambiguous stream instead. Both tiers keep
        // the same silhouette — node, flow, node.
        boolean simplify = size <= 32;

        // Rounded tile. The inset keeps the mark off the edge the way platform
        // icon grids expect; the radius is macOS-squircle-ish without being
        // exact. Tiny targets claw back some of that inset — at 16px the
        // standard margin is over a pixel a side, which the mark needs more.
        double m = simplify ? 40 : 72, w = REF - 2 * m, r = simplify ? 200 : 224;
        RoundRectangle2D tile = new RoundRectangle2D.Double(m, m, w, w, r, r);
        g.setPaint(new GradientPaint(0, (float) m, BG_TOP, 0, (float) (REF - m), BG_BOTTOM));
        g.fill(tile);
        g.setColor(RIM);
        g.setStroke(new BasicStroke(6f));
        g.draw(tile);

        // Two nodes joined by parallel streams. The mark fills most of the tile
        // on purpose — a timid mark is unreadable in a 16px tray slot.
        double cy = REF / 2;
        double xa = simplify ? 250 : 268, xb = REF - xa, nodeR = simplify ? 108 : 82;

        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(xa, cy), new Point2D.Double(xb, cy),
                new float[]{0f, 0.5f, 1f}, new Color[]{TEAL, CYAN, SKY}));

        // Outer streams bow away from centre; the middle one runs straight.
        // The simplified stream stays narrower than the nodes so they still
        // read as endpoints rather than fusing into one dumbbell.
        double[] bow = simplify ? new double[]{0} : new double[]{-232, 0, 232};
        float[] width = simplify ? new float[]{76} : new float[]{62, 78, 62};
        for (int i = 0; i < bow.length; i++) {
            CubicCurve2D c = new CubicCurve2D.Double(
                    xa, cy,
                    xa + 156, cy + bow[i],
                    xb - 156, cy + bow[i],
                    xb, cy);
            g.setStroke(new BasicStroke(width[i], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(c);
        }

        // Nodes drawn last so the streams terminate cleanly underneath them.
        // The separating ring is background-coloured rather than a stroke, and
        // is dropped when simplifying — at 16px it costs a whole pixel of node.
        if (!simplify) {
            double halo = 16;
            g.setColor(BG_BOTTOM);
            g.fill(new Ellipse2D.Double(xa - nodeR - halo, cy - nodeR - halo, (nodeR + halo) * 2, (nodeR + halo) * 2));
            g.fill(new Ellipse2D.Double(xb - nodeR - halo, cy - nodeR - halo, (nodeR + halo) * 2, (nodeR + halo) * 2));
        }
        g.setColor(TEAL);
        g.fill(new Ellipse2D.Double(xa - nodeR, cy - nodeR, nodeR * 2, nodeR * 2));
        g.setColor(SKY);
        g.fill(new Ellipse2D.Double(xb - nodeR, cy - nodeR, nodeR * 2, nodeR * 2));

        g.dispose();
        return img;
    }

    /**
     * ICO container holding PNG-compressed frames. A 256px entry records its
     * width/height as 0 — the format's one-byte fields cannot express 256.
     */
    private static void writeIco(Path path, int[] sizes, List<byte[]> pngs) throws IOException {
        int n = sizes.length;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            le16(o, 0); le16(o, 1); le16(o, n);          // ICONDIR
            int offset = 6 + 16 * n;
            for (int i = 0; i < n; i++) {                // ICONDIRENTRY
                o.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                o.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                o.writeByte(0);                          // palette entries
                o.writeByte(0);                          // reserved
                le16(o, 1);                              // colour planes
                le16(o, 32);                             // bits per pixel
                le32(o, pngs.get(i).length);
                le32(o, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] p : pngs) o.write(p);
        }
    }

    private static void le16(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xff); o.writeByte((v >>> 8) & 0xff);
    }

    private static void le32(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xff); o.writeByte((v >>> 8) & 0xff);
        o.writeByte((v >>> 16) & 0xff); o.writeByte((v >>> 24) & 0xff);
    }
}
