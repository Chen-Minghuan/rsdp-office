package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RenderProductReferencesTest {
    private final ObjectMapper json=new ObjectMapper();
    private final OfficeService office=mock(OfficeService.class);
    private final com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper imageAssets=mock(com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper.class);
    private final RenderProductReferences references=spy(new RenderProductReferences(office,imageAssets));
    private ObjectNode scene(String... ids) {
        ObjectNode scene=json.createObjectNode(); ArrayNode items=scene.putArray("furniture");
        for(String id:ids) items.addObject().put("productId",id);
        return scene;
    }
    private OfficeProductDto product(String id) {
        OfficeProductDto p=new OfficeProductDto(); p.setId(id); p.setName("所选商品"+id); p.setVariant("1400×700"); p.setImageUrl("http://localhost/uploads/images/"+id+".png"); return p;
    }
    private byte[] photo() throws Exception {
        var image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics(); g.setColor(Color.RED); g.fillRect(0,0,400,300); g.dispose();
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    @Test void deduplicatesPhotosAndMapsThemToEveryFurnitureNumber() throws Exception {
        var p=product("SKU1"); when(office.listOfficeProducts()).thenReturn(List.of(p));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1","SKU1",""));
        assertEquals(1,prepared.images().size()); assertEquals(1,prepared.manifest().size());
        assertTrue(prepared.prompt().contains("1至2")); assertTrue(prepared.prompt().contains("P1"));
        var board=ImageIO.read(new ByteArrayInputStream(prepared.images().get(0)));
        assertEquals(Color.RED.getRGB(),board.getRGB(board.getWidth()/2,board.getHeight()/2));
        assertEquals(64,prepared.manifest().get(0).path("imageSha256").asText().length());
        verify(references,times(1)).readImage(p.getImageUrl(),p.getName());
    }
    @Test void usesMainAndAuxiliaryViewsButNotOtherVariantsOrDuplicatePhotos() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");p.setVariantId("V1");
        when(office.listOfficeProducts()).thenReturn(List.of(p));
        var extra=new com.rsdp.officeproduct.importer.entity.ImageAssets();extra.setStorageUrl("/uploads/images/side.png");extra.setVariantId("V1");
        var duplicate=new com.rsdp.officeproduct.importer.entity.ImageAssets();duplicate.setStorageUrl("/uploads/images/duplicate.png");
        var foreign=new com.rsdp.officeproduct.importer.entity.ImageAssets();foreign.setStorageUrl("/uploads/images/other-color.png");foreign.setVariantId("V2");
        when(imageAssets.selectList(any())).thenReturn(List.of(extra,duplicate,foreign));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var side=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);var g=side.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,400,300);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(side,"png",bytes);
        doReturn(bytes.toByteArray()).when(references).readImage(extra.getStorageUrl(),p.getName());
        doReturn(photo()).when(references).readImage(duplicate.getStorageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1","SKU1"));
        assertEquals(2,prepared.manifest().get(0).path("usedImageCount").asInt());
        assertEquals(2,prepared.manifest().get(0).path("images").size());
        verify(references,never()).readImage(foreign.getStorageUrl(),p.getName());
        var board=ImageIO.read(new ByteArrayInputStream(prepared.images().get(0)));boolean red=false,blue=false;
        for(int x=0;x<board.getWidth();x+=8)for(int y=0;y<board.getHeight();y+=8){red|=board.getRGB(x,y)==Color.RED.getRGB();blue|=board.getRGB(x,y)==Color.BLUE.getRGB();}
        assertTrue(red&&blue,"main and auxiliary image pixels must reach the supplied reference board");
        assertTrue(prepared.prompt().contains("同一个商品"));
    }
    @Test void fallsBackToAvailableAuxiliaryViewIfMainImageIsMissing() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");when(office.listOfficeProducts()).thenReturn(List.of(p));
        var extra=new com.rsdp.officeproduct.importer.entity.ImageAssets();extra.setStorageUrl("/uploads/images/side.png");
        when(imageAssets.selectList(any())).thenReturn(List.of(extra));
        doThrow(new BusinessException("缺失")).when(references).readImage(p.getImageUrl(),p.getName());
        doReturn(photo()).when(references).readImage(extra.getStorageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1"));
        assertEquals(1,prepared.manifest().get(0).path("usedImageCount").asInt());
        assertFalse(prepared.manifest().get(0).path("warnings").isEmpty());
    }
    @Test void noSelectionKeepsLayoutOnlyAndUnavailableOrForgedSelectionFails() {
        assertTrue(references.prepare(scene("")).images().isEmpty()); verifyNoInteractions(office);
        when(office.listOfficeProducts()).thenReturn(List.of());
        assertThrows(BusinessException.class,()->references.prepare(scene("GONE")));
        var forged=scene("A"); ((ObjectNode)forged.path("furniture").get(0)).putObject("productSelection").putObject("product").put("id","B");
        assertThrows(BusinessException.class,()->references.prepare(forged));
    }
    @Test void refusesForeignUrlsTraversalMissingFilesAndInvalidImages() throws Exception {
        assertThrows(BusinessException.class,()->references.readImage("file:///etc/passwd","A"));
        assertThrows(BusinessException.class,()->references.readImage("https://example.test/private.png","A"));
        assertThrows(BusinessException.class,()->references.readImage("/uploads/images/../../private.png","A"));
        var p=product("SKU1"); when(office.listOfficeProducts()).thenReturn(List.of(p));
        doReturn(new byte[]{1,2}).when(references).readImage(p.getImageUrl(),p.getName());
        assertThrows(BusinessException.class,()->references.prepare(scene("SKU1")));
    }
    @Test void readsCatalogLocalImageWithoutFetchingItsPublicHostname(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        byte[] bytes=photo(); java.nio.file.Files.write(directory.resolve("selected.png"),bytes);
        doReturn(directory).when(references).imageDirectory();
        assertArrayEquals(bytes,references.readImage("http://old-server:8082/uploads/images/selected.png","商品"));
        assertArrayEquals(bytes,references.readImage("/uploads/images/selected.png","商品"));
        assertThrows(BusinessException.class,()->references.readImage("/uploads/images/missing.png","商品"));
    }
    @Test void splitsManyProductsIntoAtMostTwoBoardsWithoutDroppingAny() throws Exception {
        var catalog=new java.util.ArrayList<OfficeProductDto>(); var ids=new String[13];
        for(int i=0;i<13;i++) { ids[i]="SKU"+i;var p=product(ids[i]);catalog.add(p);doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName()); }
        when(office.listOfficeProducts()).thenReturn(catalog);
        var prepared=references.prepare(scene(ids)); assertEquals(2,prepared.images().size()); assertEquals(13,prepared.manifest().size());
        assertTrue(prepared.prompt().contains("P13"));
    }
}
