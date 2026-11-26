package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.rsdp.officeproduct.importer.office.product.SupplierWorkbookMapping.Field.*;

class LocalProductWorkbookTest {
    @Test
    void derivesMaterialsFromExplicitAndMergedDescriptionCells() throws Exception {
        byte[] file;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("月影胡桃");
            row(sheet, 0, "PICTURE", "ITEM NO.", "ITEM NAME", "COLOR", "SPEC", "REMARK", "MATERIAL");
            row(sheet, 1, "", "TEA001", "茶台", "月影胡桃", "2000x800x750", "台面为碳晶板，标配乌金石茶盘。");
            row(sheet, 2, "", "TEA002", "配套台", "月影胡桃", "1200/1400x800x750", "隐藏的不锈钢旧值");
            row(sheet, 3, "", "TEA003", "木桌", "月影胡桃", "1800x800x750", "表面为岩板", "橡木");
            row(sheet, 4, "", "TEA004", "配套柜", "大理石纹+月影胡桃", "800x400x750", "仿大理石纹，不采用实木");
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 5, 5));
            workbook.write(output);
            file = output.toByteArray();
        }
        var goods = workbookService().preview(new MockMultipartFile("file", "goods.xlsx", null, file)).supplier().products();
        assertEquals("碳晶板", goods.get(0).product().variants().getFirst().material());
        assertEquals(List.of("碳晶板", "碳晶板"), goods.get(1).product().variants().stream().map(LocalProductModels.Variant::material).toList());
        var evidence = goods.get(1).variantEvidence().getFirst();
        assertEquals("F", evidence.descriptionCell().column());
        assertEquals(2, evidence.descriptionCell().row());
        assertEquals(LocalProductWorkbook.MaterialSource.DESCRIPTION, evidence.materialSource());
        assertEquals("橡木", goods.get(2).product().variants().getFirst().material());
        assertEquals(LocalProductWorkbook.MaterialSource.COLUMN, goods.get(2).variantEvidence().getFirst().materialSource());
        assertEquals("", goods.get(3).product().variants().getFirst().material());
    }

    @Test
    void factoryTemplateOmitsSupplierAndOldTemplateCannotOverrideBinding() throws Exception {
        var settings = new RsdpMainProperties();
        settings.setCatalogWorkspaceId("WS-TEST");
        settings.setCatalogFactoryCode("B001");
        var factoryWorkbook = new LocalProductWorkbook(productService(settings), new WorkbookPreviewCache());
        byte[] file;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(factoryWorkbook.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("厂内型号", sheet.getRow(0).getCell(2).getStringCellValue());
            row(sheet, 1, "会议桌", "OF", "DESK", "默认", "1200", "600");
            workbook.write(output);
            file = output.toByteArray();
        }
        var bound = factoryWorkbook.preview(new MockMultipartFile("file", "factory.xlsx", null, file));
        assertEquals("B001", bound.products().getFirst().factoryCode());
        assertTrue(bound.errors().isEmpty());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService().template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            row(sheet, 0, "商品名称", "分类编码", "供应厂编码", "厂内型号", "规格名称", "宽(mm)", "深(mm)");
            row(sheet, 1, "会议桌", "OF", "OTHER", "DESK", "默认", "1200", "600");
            workbook.write(output);
            file = output.toByteArray();
        }
        var legacy = new MockMultipartFile("file", "legacy.xlsx", null, file);
        assertEquals("B001", factoryWorkbook.preview(legacy).products().getFirst().factoryCode());
        assertEquals("", workbookService().preview(legacy).products().getFirst().factoryCode());
        assertNull(productService(settings).boundFactory("WS-OTHER-FACILITY"));
    }

    @Test
    void previewsUnfamiliarColumnsAndReadsTheConfirmedSheetMapping() throws Exception {
        byte[] file;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            row(workbook.createSheet("封面"), 0, "年度目录");
            var sheet = workbook.createSheet("供应商目录");
            row(sheet, 0, "供应商抬头");
            row(sheet, 1, "品名说明", "报价", "库存货号", "长向毫米", "短向毫米", "商品组");
            row(sheet, 2, "会议桌", "599", "MT-11", "1600", "800", "会议桌");
            row(sheet, 3, "品名说明", "报价", "库存货号", "长向毫米", "短向毫米", "商品组");
            row(sheet, 4, "配套商品", "199", "CH-04", "550", "", "椅类");
            workbook.write(output);
            file = output.toByteArray();
        }
        var workbookService = workbookService();
        var upload = new MockMultipartFile("file", "supplier.xlsx", null, file);
        var preview = workbookService.preview(upload);
        assertEquals(1, preview.mappingSheets().getFirst().index());
        assertEquals(2, preview.mappingSheets().getFirst().headers().getFirst().row());
        assertNull(preview.supplier());

        var mapping = new SupplierWorkbookMapping.Selection(1, 2,
            Map.of(MODEL, 2, NAME, 0, WIDTH_MM, 3, DEPTH_MM, 4, CATEGORY, 5));
        var products = workbookService.preview(upload, mapping).supplier().products();
        assertEquals(2, products.size());
        var table = products.getFirst();
        assertEquals("MT-11", table.product().factoryModel());
        assertEquals("会议桌", table.sourceCategory());
        assertEquals(1600, table.product().variants().getFirst().widthMm().intValueExact());
        assertEquals(800, table.product().variants().getFirst().depthMm().intValueExact());
        assertNull(products.getLast().product().variants().getFirst().depthMm());
        assertThrows(BusinessException.class, () -> workbookService.preview(upload,
            new SupplierWorkbookMapping.Selection(1, 2, Map.of(MODEL, 2, NAME, 2))));
    }

    @Test
    void previewsParallelSupplierSectionsWithoutTreatingPricesAsDimensions() throws Exception {
        byte[] photo = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=");
        byte[] file;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("供应商表");
            row(sheet, 0, "配套商品");
            row(sheet, 1, "产品图形", "产品编号", "产品名称", "产品颜色", "价格", "产品图形", "产品编号", "产品名称", "产品颜色", "价格");
            row(sheet, 2, "PICTURE", "ITEM NO.", "ITEM NAME", "COLOR", "PRICE", "PICTURE", "ITEM NO.", "ITEM NAME", "COLOR", "PRICE");
            row(sheet, 3, "", "DESK01", "总裁桌", "胡桃色", "3000", "", "CHAIR-1", "职员椅", "深棕", "800");
            row(sheet, 4, "桌类商品");
            row(sheet, 5, "PICTURE", "ITEM NO.", "ITEM NAME", "COLOR", "SPEC");
            row(sheet, 6, "", "DESK01", "总裁桌", "胡桃色", "2400/2200x\n1800x750");
            var drawing = sheet.createDrawingPatriarch();
            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setRow1(3);
            anchor.setRow2(4);
            anchor.setCol1(5);
            anchor.setCol2(6);
            drawing.createPicture(anchor, workbook.addPicture(photo, Workbook.PICTURE_TYPE_PNG));
            workbook.write(output);
            file = output.toByteArray();
        }

        var preview = workbookService().preview(new MockMultipartFile("file", "supplier.xlsx", null, file));
        assertTrue(preview.errors().isEmpty());
        assertEquals(2, preview.supplier().products().size());
        var table = preview.supplier().products().getFirst();
        assertEquals(List.of("B", "B"), table.sources().stream().map(LocalProductWorkbook.Source::column).toList());
        assertEquals(List.of(2400, 2200), table.product().variants().stream().map(v -> v.widthMm().intValueExact()).toList());
        assertTrue(table.images().isEmpty());
        var chair = preview.supplier().products().getLast();
        assertEquals("CHAIR-1", chair.product().factoryModel());
        assertEquals("G", chair.sources().getFirst().column());
        assertNull(chair.product().variants().getFirst().widthMm());
        assertTrue(chair.images().getFirst().selected());
        assertEquals(1, preview.supplier().images().size());
    }

    private void row(XSSFSheet sheet, int rowIndex, String... values) {
        var row = sheet.createRow(rowIndex);
        for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(values[column]);
    }

    @Test
    void associatesDescribedPictureWithModelsInItsRangeInTheSameSection() throws Exception {
        byte[] photo = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=");
        byte[] file;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("供应商表");
            row(sheet, 0, "PICTURE", "ITEM NO.", "ITEM NAME", "COLOR", "SPEC");
            row(sheet, 1, "", "12DESK001", "桌子一", "胡桃色", "1000x600x750");
            row(sheet, 2, "", "12DESK002", "桌子二", "胡桃色", "1200x600x750");
            row(sheet, 3, "", "12DESK003", "桌子三", "胡桃色", "1400x600x750");
            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setRow1(1);
            anchor.setRow2(2);
            anchor.setCol2(1);
            var picture = sheet.createDrawingPatriarch().createPicture(anchor, workbook.addPicture(photo, Workbook.PICTURE_TYPE_PNG));
            picture.getCTPicture().getNvPicPr().getCNvPr().setDescr("12DESK001-002前左侧视图");
            workbook.write(output);
            file = output.toByteArray();
        }
        var products = workbookService().preview(new MockMultipartFile("file", "supplier.xlsx", null, file)).supplier().products();
        assertTrue(products.get(0).images().getFirst().selected());
        assertEquals(products.get(0).images(), products.get(1).images());
        assertTrue(products.get(2).images().isEmpty());
    }

    @Test
    void groupsVariantsAndReportsInvalidRowsWithoutLosingValidProducts() throws Exception {
        var workbookService = workbookService();

        byte[] file;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            String[][] rows = {
                {"会议桌", "OF", "DESK", "小型", "1200", "600"},
                {"会议桌", "OF", "DESK", "大型", "1600", "750"},
                {"无效", "OF", "BAD", "小型", "-1", "600"},
                {"职员桌", "OF", "STAFF", "默认", "1000", "500"}
            };
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int col = 0; col < rows[rowIndex].length; col++) row.createCell(col).setCellValue(rows[rowIndex][col]);
            }
            workbook.write(output);
            file = output.toByteArray();
        }

        var preview = workbookService.preview(new MockMultipartFile("file", "products.xlsx", null, file));
        assertEquals(2, preview.products().size());
        assertEquals(2, preview.products().getFirst().variants().size());
        assertEquals(1, preview.errors().size());
        assertEquals(4, preview.errors().getFirst().row());
    }

    @Test
    void previewsImagesAcrossMultipleSaveBatchesAndScopesReferencesToUploader() throws Exception {
        var cache = new WorkbookPreviewCache();
        var workbookService = workbookService(cache);
        byte[] file;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var drawing = sheet.createDrawingPatriarch();
            var random = new Random(83);
            int side = 768;
            int[] pixels = new int[side * side];
            var image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
            for (int index = 1; index <= 8; index++) {
                var row = sheet.createRow(index);
                String[] values = {"会议桌 " + index, "OF", "DESK-" + index, "默认", "1200", "600"};
                for (int col = 0; col < values.length; col++) row.createCell(col).setCellValue(values[col]);
                for (int pixel = 0; pixel < pixels.length; pixel++) pixels[pixel] = random.nextInt();
                image.setRGB(0, 0, side, side, pixels, 0, side);
                var png = new ByteArrayOutputStream();
                assertTrue(ImageIO.write(image, "png", png));
                int picture = workbook.addPicture(png.toByteArray(), Workbook.PICTURE_TYPE_PNG);
                var anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setRow1(index);
                anchor.setRow2(index + 1);
                anchor.setCol1(11);
                anchor.setCol2(12);
                drawing.createPicture(anchor, picture);
            }
            workbook.write(output);
            file = output.toByteArray();
        }
        assertTrue(file.length > 12 * 1024 * 1024);

        var preview = workbookService.preview(new MockMultipartFile("file", "large.xlsx", null, file));
        assertEquals(7, preview.products().size());
        assertEquals(1, preview.errors().size());
        assertTrue(preview.products().stream().allMatch(product -> product.images().size() == 1));

        try {
            var referenced = workbookService.preview(new MockMultipartFile("file", "large.xlsx", null, file), null, true);
            assertEquals(8, referenced.products().size());
            assertTrue(referenced.errors().isEmpty());
            assertTrue(referenced.imageSizes().values().stream().mapToInt(Integer::intValue).sum() > 12 * 1024 * 1024);
            var image = referenced.products().getFirst().images().getFirst();
            assertNull(image.content());
            var sameWorkspace = new OfficeUser();
            sameWorkspace.setWorkspaceId("WS-TEST");
            sameWorkspace.setUserId("USR-TEST");
            assertEquals(referenced.imageSizes().get(image.id()).intValue(),
                cache.read(referenced.previewId(), image.id(), sameWorkspace).content().length);
            sameWorkspace.setWorkspaceId("WS-ANOTHER");
            assertThrows(BusinessException.class, () -> cache.read(referenced.previewId(), image.id(), sameWorkspace));
            sameWorkspace.setWorkspaceId("WS-TEST");
            sameWorkspace.setUserId("USR-OTHER");
            assertThrows(BusinessException.class, () -> cache.read(referenced.previewId(), image.id(), sameWorkspace));
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void retainsFileMetadataForAnImageFreeWorkbook() throws Exception {
        var cache = new WorkbookPreviewCache();
        try {
            var workbookService = new LocalProductWorkbook(productService(new RsdpMainProperties()), cache);
            byte[] bytes;
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService.template()));
                 var output = new ByteArrayOutputStream()) {
                row(workbook.getSheetAt(0), 1, "会议桌", "OF", "DESK-NO-IMAGE", "默认", "1200", "600");
                workbook.write(output);
                bytes = output.toByteArray();
            }
            var preview = workbookService.preview(new MockMultipartFile("file", "C:\\fakepath\\产品表.xlsx", null, bytes), null, true);
            assertNotNull(preview.previewId());
            assertTrue(preview.imageSizes().isEmpty());
            var actor = new OfficeUser();
            actor.setWorkspaceId("WS-TEST");
            actor.setUserId("USR-TEST");
            var request = new LocalProductModels.SaveRequest(UUID.randomUUID(), preview.products(), preview.previewId());
            var materialized = cache.materialize(request, actor);
            assertEquals(new WorkbookPreviewCache.WorkbookSource("产品表.xlsx", bytes.length), materialized.source());
            assertEquals(1, materialized.products().size());
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void rejectsWorkbookOverLimitBeforeOpeningIt() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(150L * 1024 * 1024 + 1);
        var error = assertThrows(BusinessException.class, () -> workbookService().preview(file));
        assertEquals("Excel 文件不能超过 150 MB", error.getMessage());
        verify(file, never()).transferTo(any(Path.class));
    }

    private LocalProductWorkbook workbookService() {
        return workbookService(new WorkbookPreviewCache());
    }

    private LocalProductWorkbook workbookService(WorkbookPreviewCache cache) {
        return new LocalProductWorkbook(productService(new RsdpMainProperties()), cache);
    }

    private LocalProductService productService(RsdpMainProperties settings) {
        CurrentUserService users = mock(CurrentUserService.class);
        OfficeUser member = new OfficeUser();
        member.setWorkspaceId("WS-TEST");
        member.setUserId("USR-TEST");
        member.setProductEntryEnabled(true);
        when(users.currentUser()).thenReturn(member);
        return new LocalProductService(mock(JdbcTemplate.class), new ObjectMapper(), users,
            new OfficeCatalogAccess(users, settings), new WorkbookPreviewCache(), mock(LocalCatalogReader.class));
    }
}
