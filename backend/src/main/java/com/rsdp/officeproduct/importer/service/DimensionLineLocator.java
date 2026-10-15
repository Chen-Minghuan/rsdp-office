package com.rsdp.officeproduct.importer.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Locates long, thin, axis-aligned margin strokes. These are candidates, not dimensions until read by vision. */
public final class DimensionLineLocator {
    private DimensionLineLocator() {}
    public record Line(String id, int x1, int y1, int x2, int y2, String dataUri) {}
    private record Stroke(boolean horizontal, int cross, int from, int to) {}

    public static List<Line> locate(BufferedImage image) throws IOException {
        List<Stroke> strokes = new ArrayList<>();
        scan(image, true, strokes);
        scan(image, false, strokes);
        List<Line> result = new ArrayList<>();
        for (Stroke stroke : strokes) {
            if (result.size() == 8) break;
            int x1 = stroke.horizontal ? stroke.from : stroke.cross, y1 = stroke.horizontal ? stroke.cross : stroke.from;
            int x2 = stroke.horizontal ? stroke.to : stroke.cross, y2 = stroke.horizontal ? stroke.cross : stroke.to;
            result.add(new Line("LINE-" + (result.size() + 1), x1, y1, x2, y2, crop(image, x1, y1, x2, y2, stroke.horizontal)));
        }
        return result;
    }

    private static void scan(BufferedImage image, boolean horizontal, List<Stroke> output) {
        int alongSize = horizontal ? image.getWidth() : image.getHeight();
        int crossSize = horizontal ? image.getHeight() : image.getWidth();
        int minSpan = Math.max(60, (int) (alongSize * 0.4));
        List<Stroke> rows = new ArrayList<>();
        for (int cross = 5; cross < crossSize - 5; cross++) {
            if (cross > crossSize * 0.18 && cross < crossSize * 0.82) continue;
            int start = -1, last = -1;
            for (int along = 0; along <= alongSize + 2; along++) {
                boolean dark = along < alongSize && dark(image, horizontal ? along : cross, horizontal ? cross : along);
                if (dark) { if (start < 0) start = along; last = along; }
                if (!dark && start >= 0 && along - last > 2) {
                    if (last - start >= minSpan && start >= 4 && last < alongSize - 4) rows.add(new Stroke(horizontal, cross, start, last));
                    start = -1;
                }
            }
        }
        List<Stroke> candidates = new ArrayList<>();
        for (int i = 0; i < rows.size();) {
            int j = i + 1;
            Stroke first = rows.get(i);
            while (j < rows.size() && rows.get(j).cross <= rows.get(j - 1).cross + 1 &&
                Math.abs(rows.get(j).from - first.from) < 8 && Math.abs(rows.get(j).to - first.to) < 8) j++;
            // Exclude broad walls, page borders and filled strips.
            if (rows.get(j - 1).cross - first.cross <= Math.max(3, crossSize / 400)) {
                Stroke middle = rows.get((i + j - 1) / 2);
                if (hasEndMark(image, middle, middle.from) && hasEndMark(image, middle, middle.to) && !hasInteriorMark(image, middle)) candidates.add(middle);
            }
            i = j;
        }
        candidates.sort(Comparator.comparingInt(stroke -> Math.min(stroke.cross, crossSize - stroke.cross)));
        List<Stroke> selected = new ArrayList<>();
        for (Stroke stroke : candidates) {
            if (selected.stream().anyMatch(previous -> Math.abs(previous.cross - stroke.cross) < 8)) continue;
            selected.add(stroke);
            if (selected.size() == 4) break;
        }
        output.addAll(selected);
    }

    private static boolean hasInteriorMark(BufferedImage image, Stroke stroke) {
        // A dimension chain has multiple labeled spans; it cannot supply one whole-span reference.
        for (int along = stroke.from + 16; along < stroke.to - 16; along++) {
            if (hasEndMark(image, stroke, along)) return true;
        }
        return false;
    }
    private static boolean hasEndMark(BufferedImage image, Stroke stroke, int along) {
        int negative = 0, positive = 0;
        for (int offset : new int[]{-5, -4, -3, 3, 4, 5}) {
            for (int shift = -4; shift <= 4; shift++) {
                int x = stroke.horizontal ? along + shift : stroke.cross + offset;
                int y = stroke.horizontal ? stroke.cross + offset : along + shift;
                if (dark(image, x, y)) { if (offset < 0) negative++; else positive++; break; }
            }
        }
        return negative >= 1 && positive >= 1;
    }
    private static boolean dark(BufferedImage image, int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) return false;
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16 & 255) * 299 + (rgb >> 8 & 255) * 587 + (rgb & 255) * 114) < 195000;
    }
    private static String crop(BufferedImage image, int x1, int y1, int x2, int y2, boolean horizontal) throws IOException {
        int padding = Math.max(16, (int) (Math.min(image.getWidth(), image.getHeight()) * 0.021));
        int left = Math.max(0, x1 - padding), top = Math.max(0, y1 - padding);
        int right = Math.min(image.getWidth(), x2 + padding + 1), bottom = Math.min(image.getHeight(), y2 + padding + 1);
        BufferedImage local = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = local.createGraphics();
        try {
            g.drawImage(image, -left, -top, null);
            g.setColor(Color.RED);
            g.fillOval(x1 - left - 3, y1 - top - 3, 7, 7);
            g.fillOval(x2 - left - 3, y2 - top - 3, 7, 7);
        } finally { g.dispose(); }
        if (!horizontal) {
            BufferedImage rotated = new BufferedImage(local.getHeight(), local.getWidth(), BufferedImage.TYPE_INT_RGB);
            Graphics2D rotation = rotated.createGraphics();
            try { rotation.translate(local.getHeight(), 0); rotation.rotate(Math.PI / 2); rotation.drawImage(local, 0, 0, null); }
            finally { rotation.dispose(); }
            local = rotated;
        }
        double factor = Math.min(3, 3072.0 / local.getWidth());
        BufferedImage enlarged = new BufferedImage(Math.max(1, (int) (local.getWidth() * factor)), Math.max(1, (int) (local.getHeight() * factor)), BufferedImage.TYPE_INT_RGB);
        Graphics2D resize = enlarged.createGraphics();
        try { resize.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); resize.drawImage(local, 0, 0, enlarged.getWidth(), enlarged.getHeight(), null); }
        finally { resize.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(enlarged, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
