package com.rsdp.officeproduct.importer.util;

import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class WorkbookImportSourceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl7JsgAAAAASUVORK5CYII=");

    @Test
    void picturesWithStaleDescriptionsStayVisibleButRequireExplicitSelection() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("供应商");
            sheet.createRow(2).createCell(1).setCellValue("ITEM NO.");
            sheet.getRow(2).createCell(2).setCellValue("ITEM NAME");
            sheet.createRow(3).createCell(1).setCellValue("OTHER001");
            sheet.getRow(3).createCell(2).setCellValue("旧款桌");
            sheet.createRow(4).createCell(1).setCellValue("TARGET001");
            sheet.getRow(4).createCell(2).setCellValue("新款桌");
            picture(workbook, sheet, coloredPng(Color.RED), 3, 0)
                .getCTPicture().getNvPicPr().getCNvPr().setDescr("OTHER001 主图");
            picture(workbook, sheet, coloredPng(Color.BLUE), 4, 0)
                .getCTPicture().getNvPicPr().getCNvPr().setDescr("OTHER001 左图");
            picture(workbook, sheet, coloredPng(Color.GREEN), 4, 100000)
                .getCTPicture().getNvPicPr().getCNvPr().setDescr("OTHER001 右图");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "supplier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            var target = source.rows().get(1);
            assertEquals(2, target.getEmbeddedImageCount());
            assertEquals(java.util.List.of(), target.getSelectedEmbeddedImageIndexes());
            assertTrue(target.getImageWarnings().stream().anyMatch(w -> w.contains("请逐张勾选")));
            assertEquals(2, source.images().get(target.getRowIndex()).size());
        }
    }

    @Test
    void oneSupplierLineWithTwoWidthsBecomesTwoVariantsSharingConfirmedPricesAndPackaging() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("供应商");
            sheet.createRow(2).createCell(1).setCellValue("ITEM NO.");
            sheet.getRow(2).createCell(2).setCellValue("ITEM NAME");
            var line = sheet.createRow(3);
            line.createCell(1).setCellValue("26NNK005");
            line.createCell(2).setCellValue("2.4/2.2米高管台");
            line.createCell(4).setCellValue("2400/2200x1800\nx750");
            line.createCell(6).setCellValue(6);
            line.createCell(7).setCellValue(0.474);
            line.createCell(8).setCellValue(139);
            line.createCell(9).setCellValue(4567);
            line.createCell(11).setCellValue(1507);
            picture(workbook, sheet, PNG, 3, 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "supplier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            assertEquals(2, source.rows().size());
            assertEquals("2400x1800x750", source.rows().get(0).getFields().get("sizeText"));
            assertEquals("2200x1800x750", source.rows().get(1).getFields().get("sizeText"));
            assertEquals("2400", source.rows().get(0).getFields().get("widthMm"));
            assertEquals("2200", source.rows().get(1).getFields().get("widthMm"));
            assertEquals("1800", source.rows().get(1).getFields().get("depthMm"));
            assertEquals("750", source.rows().get(1).getFields().get("heightMm"));
            assertEquals("4567", source.rows().get(0).getFields().get("retailPrice"));
            assertEquals("4567", source.rows().get(1).getFields().get("retailPrice"));
            assertEquals("1507", source.rows().get(0).getFields().get("factoryPrice"));
            assertEquals("1507", source.rows().get(1).getFields().get("factoryPrice"));
            assertEquals("6", source.rows().get(0).getFields().get("packageCount"));
            assertEquals("6", source.rows().get(1).getFields().get("packageCount"));
            assertEquals("0.474", source.rows().get(1).getFields().get("packageVolumeCbm"));
            assertEquals("139", source.rows().get(1).getFields().get("grossWeight"));
            assertEquals(4, source.rows().get(0).getSourceRowIndex());
            assertEquals(4, source.rows().get(1).getSourceRowIndex());
            assertEquals(2, source.rows().get(0).getSourceVariantCount());
            assertEquals("2400/2200x1800\nx750", source.rows().get(0).getSourceSizeText());
            assertEquals(1, source.images().get(2).size());
            assertEquals(1, source.images().get(3).size());
        }
    }

    @Test
    void mergedPictureCellsShareEveryImageWithEachCoveredProductAndKeepVisualOrder() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("供应商");
            sheet.createRow(2).createCell(1).setCellValue("ITEM NO.");
            sheet.getRow(2).createCell(2).setCellValue("ITEM NAME");
            for (int r = 3; r <= 8; r++) {
                sheet.createRow(r).createCell(1).setCellValue("CODE00" + (r + 1));
                sheet.getRow(r).createCell(2).setCellValue("办公桌");
            }
            sheet.createRow(9).createCell(1).setCellValue("CODE010");
            sheet.getRow(9).createCell(2).setCellValue("办公桌");
            sheet.addMergedRegion(new CellRangeAddress(3, 5, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(6, 7, 0, 0));
            sheet.getRow(3).createCell(5).setCellValue("三款共用说明\n第二段");
            sheet.getRow(4).createCell(5).setCellValue("合并区中不可见的旧值");
            sheet.addMergedRegion(new CellRangeAddress(3, 5, 5, 5));
            sheet.getRow(6).createCell(5).setCellValue("两款共用说明");
            sheet.addMergedRegion(new CellRangeAddress(6, 7, 5, 5));
            sheet.getRow(8).createCell(5).setCellValue("单款说明");
            byte[] top = coloredPng(Color.RED);
            byte[] bottom = coloredPng(Color.BLUE);
            // Drawing XML deliberately puts the lower picture first.
            picture(workbook, sheet, bottom, 4, 0);
            picture(workbook, sheet, top, 3, 0);
            picture(workbook, sheet, bottom, 7, 0);
            picture(workbook, sheet, top, 6, 0);
            picture(workbook, sheet, bottom, 8, 100000);
            picture(workbook, sheet, top, 8, 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "supplier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            for (int row = 0; row < 6; row++) {
                var images = source.images().get(source.rows().get(row).getRowIndex());
                assertNotNull(images);
                assertEquals(2, images.size());
                assertArrayEquals(top, images.get(0).data());
                assertArrayEquals(bottom, images.get(1).data());
            }
            for (int row = 0; row < 3; row++)
                assertEquals("三款共用说明\n第二段", source.rows().get(row).getFields().get("description"));
            for (int row = 3; row < 5; row++)
                assertEquals("两款共用说明", source.rows().get(row).getFields().get("description"));
            assertEquals("单款说明", source.rows().get(5).getFields().get("description"));
            assertEquals("", source.rows().get(6).getFields().get("description"));
        }
    }

    private static byte[] coloredPng(Color color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static XSSFPicture picture(XSSFWorkbook workbook, XSSFSheet sheet, byte[] data, int row, int dy) {
        int id = workbook.addPicture(data, Workbook.PICTURE_TYPE_PNG);
        ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setCol1(0);
        anchor.setRow1(row);
        anchor.setDy1(dy);
        return sheet.createDrawingPatriarch().createPicture(anchor, id);
    }

    @Test
    void standardTemplateKeepsImageOnItsProductRow() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("商品");
            sheet.createRow(0).createCell(0).setCellValue("外部编码");
            sheet.getRow(0).createCell(1).setCellValue("商品名称");
            sheet.createRow(1).createCell(0).setCellValue("TEST001");
            sheet.getRow(1).createCell(1).setCellValue("测试桌");
            int pictureId = workbook.addPicture(PNG, Workbook.PICTURE_TYPE_PNG);
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setRow1(1);
            sheet.createDrawingPatriarch().createPicture(anchor, pictureId);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "standard.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            assertEquals(1, source.rows().size());
            assertEquals("TEST001", source.rows().get(0).getFields().get("externalCode"));
            assertEquals(1, source.rows().get(0).getEmbeddedImageCount());
            assertEquals(1, source.images().get(2).size());
        }
    }

    @Test
    void supplierNumericFloatingPointNoiseIsRoundedToStoragePrecision() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("供应商");
            sheet.createRow(2).createCell(1).setCellValue("ITEM NO.");
            sheet.getRow(2).createCell(2).setCellValue("ITEM NAME");
            sheet.createRow(3).createCell(1).setCellValue("NOISE001");
            sheet.getRow(3).createCell(2).setCellValue("办公桌");
            sheet.getRow(3).createCell(4).setCellValue("1600x800x750");
            sheet.getRow(3).createCell(8).setCellValue(86.49999999999999d);
            sheet.getRow(3).createCell(7).setCellValue(0.40534966499999996d);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "supplier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            var fields = source.rows().getFirst().getFields();
            assertEquals("86.5", fields.get("grossWeight"));
            assertEquals("0.405349665", fields.get("packageVolumeCbm"));
        }
    }

    @Test
    void supplierRowsKeepPhysicalSheetAndImageLocation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet first = workbook.createSheet("系列一");
            first.createRow(2).createCell(1).setCellValue("ITEM NO.");
            first.getRow(2).createCell(2).setCellValue("ITEM NAME");
            first.createRow(3).createCell(1).setCellValue("ABC001");
            first.getRow(3).createCell(2).setCellValue("办公桌");
            first.getRow(3).createCell(4).setCellValue("1600x800x750");
            first.getRow(3).createCell(6).setCellValue(3);
            first.getRow(3).createCell(7).setCellValue(0.742000000000004);
            first.getRow(3).createCell(8).setCellValue(59);
            XSSFSheet second = workbook.createSheet("系列二");
            second.createRow(2).createCell(1).setCellValue("ITEM NO.");
            second.getRow(2).createCell(2).setCellValue("ITEM NAME");
            second.createRow(3).createCell(1).setCellValue("ABC002");
            second.getRow(3).createCell(2).setCellValue("办公椅");
            int pictureId = workbook.addPicture(PNG, Workbook.PICTURE_TYPE_PNG);
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setRow1(3);
            second.createDrawingPatriarch().createPicture(anchor, pictureId);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            var source = WorkbookImportSource.read(new MockMultipartFile("file", "supplier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));
            assertEquals(2, source.rows().size());
            assertEquals("办公桌", source.rows().get(0).getFields().get("productName"));
            assertEquals("1600", source.rows().get(0).getFields().get("widthMm"));
            assertEquals("800", source.rows().get(0).getFields().get("depthMm"));
            assertEquals("750", source.rows().get(0).getFields().get("heightMm"));
            assertEquals("3", source.rows().get(0).getFields().get("packageCount"));
            assertEquals("0.742", source.rows().get(0).getFields().get("packageVolumeCbm"));
            assertEquals("59", source.rows().get(0).getFields().get("grossWeight"));
            assertEquals("系列二", source.rows().get(1).getSourceSheetName());
            assertEquals(4, source.rows().get(1).getSourceRowIndex());
            assertEquals(1, source.rows().get(1).getEmbeddedImageCount());
            assertEquals(1, source.images().get(3).size());
        }
    }

    @Test
    void realSupplierWorkbookWhenProvided() throws Exception {
        String sample = System.getProperty("rsdp.sampleWorkbook");
        if (sample == null) return;
        Path path = Path.of(sample);
        var source = WorkbookImportSource.read(new MockMultipartFile("file", path.getFileName().toString(),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.readAllBytes(path)));
        assertEquals(220, source.rows().size());
        assertEquals(215, source.rows().stream().filter(r -> !r.getFields().getOrDefault("widthMm", "").isBlank()
            && !r.getFields().getOrDefault("depthMm", "").isBlank()).count());
        assertEquals("8997", source.rows().get(0).getFields().get("retailPrice"));
        assertEquals("2969", source.rows().get(0).getFields().get("factoryPrice"));
        assertEquals("9", source.rows().get(0).getFields().get("packageCount"));
        assertEquals("0.742", source.rows().get(0).getFields().get("packageVolumeCbm"));
        assertEquals("209", source.rows().get(0).getFields().get("grossWeight"));
        assertEquals(198, source.rows().stream().filter(r -> !r.getFields().getOrDefault("packageCount", "").isBlank()).count());
        assertEquals(187, source.rows().stream().filter(r -> !r.getFields().getOrDefault("packageVolumeCbm", "").isBlank()).count());
        assertEquals(187, source.rows().stream().filter(r -> !r.getFields().getOrDefault("grossWeight", "").isBlank()).count());
        assertEquals(135, source.rows().stream().filter(r -> !r.getFields().getOrDefault("description", "").isBlank()).count());
        for (int index = 1; index < 3; index++)
            assertEquals(source.rows().get(0).getFields().get("description"),
                source.rows().get(index).getFields().get("description"));
        assertEquals(source.rows().get(3).getFields().get("description"),
            source.rows().get(4).getFields().get("description"));
        assertEquals(220, source.images().size());
        long previewable = source.images().values().stream().flatMap(java.util.List::stream)
            .filter(image -> EmbeddedImagePreview.create(image) != null).count();
        assertTrue(previewable > 205);
        for (String code : java.util.List.of("26MNK001C", "26MNK005C", "26MNK002C",
            "26MNK003", "26MNK004", "26NNK005")) {
            assertEquals(2, source.rows().stream().filter(r -> r.getFields().get("externalCode").equals(code))
                .findFirst().orElseThrow().getEmbeddedImageCount(), code);
        }
        assertEquals(2, source.rows().stream().filter(r -> r.getFields().get("externalCode").equals("16BMT008"))
            .findFirst().orElseThrow().getEmbeddedImageCount());
        var corrected = source.rows().stream().filter(r -> r.getFields().get("externalCode").equals("16FMT001"))
            .findFirst().orElseThrow();
        assertEquals(2, corrected.getEmbeddedImageCount());
        assertEquals(java.util.List.of(), corrected.getSelectedEmbeddedImageIndexes());
    }
}
