package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalProductWorkbookTest {
    @Test
    void groupsVariantsAndReportsInvalidRowsWithoutLosingValidProducts() throws Exception {
        var workbookService = workbookService();

        byte[] file;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            String[][] rows = {
                {"会议桌", "OF", "TEST", "DESK", "小型", "1200", "600"},
                {"会议桌", "OF", "TEST", "DESK", "大型", "1600", "750"},
                {"无效", "OF", "TEST", "BAD", "小型", "-1", "600"},
                {"职员桌", "OF", "TEST", "STAFF", "默认", "1000", "500"}
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
    void previewsWorkbookLargerThanTenMegabytesWithImages() throws Exception {
        var workbookService = workbookService();
        byte[] file;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookService.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var drawing = sheet.createDrawingPatriarch();
            var random = new Random(83);
            int side = 768;
            int[] pixels = new int[side * side];
            var image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
            for (int index = 1; index <= 6; index++) {
                var row = sheet.createRow(index);
                String[] values = {"会议桌 " + index, "OF", "TEST", "DESK-" + index, "默认", "1200", "600"};
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
        assertTrue(file.length > 10 * 1024 * 1024);

        var preview = workbookService.preview(new MockMultipartFile("file", "large.xlsx", null, file));
        assertEquals(6, preview.products().size());
        assertTrue(preview.errors().isEmpty());
        assertTrue(preview.products().stream().allMatch(product -> product.images().size() == 1));
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
        CurrentUserService users = mock(CurrentUserService.class);
        OfficeUser member = new OfficeUser();
        member.setWorkspaceId("WS-TEST");
        member.setProductEntryEnabled(true);
        when(users.currentUser()).thenReturn(member);
        return new LocalProductWorkbook(new LocalProductService(mock(JdbcTemplate.class), new ObjectMapper(), users));
    }
}
