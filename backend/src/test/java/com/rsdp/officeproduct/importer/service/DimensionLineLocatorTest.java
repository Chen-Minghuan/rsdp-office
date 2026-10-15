package com.rsdp.officeproduct.importer.service;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class DimensionLineLocatorTest {
    @Test
    void groundsIndependentHorizontalAndVerticalThinTickedLinesAndIgnoresWallsAndInterior() throws Exception {
        BufferedImage image = drawing();
        var lines = DimensionLineLocator.locate(image);
        assertEquals(2, lines.size());
        assertTrue(lines.stream().anyMatch(line -> Math.abs(line.x1() - 80) <= 3 && line.y1() == 25 && Math.abs(line.x2() - 920) <= 3));
        assertTrue(lines.stream().anyMatch(line -> line.x1() == 20 && Math.abs(line.y1() - 80) <= 3 && Math.abs(line.y2() - 520) <= 3));
        assertTrue(lines.stream().allMatch(line -> line.dataUri().startsWith("data:image/png;base64,")));
    }

    @Test
    void segmentedDimensionChainIsNotOfferedAsOneTotalSpan() throws Exception {
        BufferedImage image = drawing();
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.drawLine(400, 20, 400, 30);
        graphics.dispose();
        var lines = DimensionLineLocator.locate(image);
        assertEquals(1, lines.size());
        assertEquals(20, lines.get(0).x1());
        assertEquals(20, lines.get(0).x2());
    }

    static BufferedImage drawing() {
        BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 600); g.setColor(Color.BLACK);
        g.drawLine(80, 25, 920, 25); g.drawLine(80, 20, 80, 30); g.drawLine(920, 20, 920, 30);
        g.drawLine(20, 80, 20, 520); g.drawLine(15, 80, 25, 80); g.drawLine(15, 520, 25, 520);
        g.fillRect(80, 85, 840, 10); // thick exterior wall
        g.drawLine(100, 300, 900, 300); // interior line
        g.dispose();
        return image;
    }

    @Test
    void noDimensionIsInventedFromBlankImageOrUntickedBorder() throws Exception {
        BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 600);
        g.setColor(Color.BLACK); g.drawLine(50, 30, 950, 30); g.drawRect(0, 0, 999, 599); g.dispose();
        assertTrue(DimensionLineLocator.locate(image).isEmpty());
    }
}
