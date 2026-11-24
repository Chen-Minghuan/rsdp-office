package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RenderProductReferencesTest {
    private final ObjectMapper json=new ObjectMapper();
    private final OfficeService office=mock(OfficeService.class);
    private final RsdpCatalogGateway gateway=mock(RsdpCatalogGateway.class);
    private final RestTemplate restTemplate=mock(RestTemplate.class);
    private final RsdpMainProperties mainProperties=mock(RsdpMainProperties.class);
    private final ProductVisualUnderstandingService understanding=mock(ProductVisualUnderstandingService.class);
    private final RenderProductReferences references=spy(new RenderProductReferences(office,gateway,restTemplate,mainProperties,understanding));
    @BeforeEach void staticBaseline() {
        when(understanding.understandAll(anyList())).thenAnswer(call->ProductVisualUnderstandingService.Batch.disabled(call.getArgument(0)));
        when(understanding.maxNewProductsPerRender()).thenReturn(4);
    }
    private ObjectNode scene(String... ids) {
        ObjectNode scene=json.createObjectNode(); ArrayNode items=scene.putArray("furniture");
        for(int i=0;i<ids.length;i++) items.addObject().put("id","F"+(i+1)).put("productId",ids[i]).put("label","测试家具").put("x",i*1000).put("y",0).put("w",600).put("h",600).put("rotation",0);
        return scene;
    }
    private OfficeProductDto product(String id) {
        OfficeProductDto p=new OfficeProductDto(); p.setId(id); p.setName("所选商品"+id); p.setVariant("1400×700"); p.setImageUrl("http://localhost/uploads/images/"+id+".png"); return p;
    }
    private ProductDetailDto.Image image(String url, String variantId) {
        ProductDetailDto.Image image=new ProductDetailDto.Image();
        image.setStorageUrl(url); image.setVariantId(variantId);
        return image;
    }
    private ProductDetailDto detailWithImages(List<ProductDetailDto.Image> images) {
        ProductDetailDto detail=new ProductDetailDto();
        detail.setImages(images);
        return detail;
    }
    private byte[] photo() throws Exception { return photo(Color.RED.getRGB()); }
    private byte[] photo(int rgb) throws Exception {
        var image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics(); g.setColor(new Color(rgb)); g.fillRect(0,0,400,300); g.dispose();
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    private String contentId(byte[] bytes) { return "IMG-"+com.rsdp.officeproduct.importer.service.VisionImagePreparer.sha256(bytes).substring(0,16); }
    @Test void deduplicatesPhotosAndMapsThemToEveryFurnitureNumber() throws Exception {
        var p=product("SKU1"); when(office.listOfficeProducts()).thenReturn(List.of(p));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1","SKU1",""));
        assertEquals(1,prepared.images().size()); assertEquals(1,prepared.manifest().size());
        assertTrue(prepared.prompt().contains("该商品组 →")); assertTrue(prepared.prompt().contains("测试家具")); assertTrue(prepared.prompt().contains("图3第1个商品组")); assertFalse(prepared.prompt().contains(p.getName())); assertFalse(prepared.prompt().contains(p.getVariant())); assertFalse(prepared.prompt().contains("实际宽深高")); assertTrue(prepared.prompt().contains("P1")); assertTrue(prepared.prompt().contains("F1")); assertTrue(prepared.prompt().contains("使用数量为2件"));
        assertEquals(2,prepared.manifest().get(0).path("quantity").asInt());
        assertEquals("F1",prepared.manifest().get(0).path("furnitureReferenceIds").get(0).asText()); assertEquals(p.getName(),prepared.manifest().get(0).path("name").asText()); assertEquals(p.getVariant(),prepared.manifest().get(0).path("variant").asText());
        var board=ImageIO.read(new ByteArrayInputStream(prepared.images().get(0)));
        assertEquals(Color.RED.getRGB(),board.getRGB(board.getWidth()/2,board.getHeight()/2));
        assertEquals(64,prepared.manifest().get(0).path("imageSha256").asText().length());
        verify(references,times(1)).readImage(p.getImageUrl(),p.getName());
    }
    @Test void usesMainAndAuxiliaryViewsButNotOtherVariantsOrDuplicatePhotos() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");p.setVariantId("V1");
        when(office.listOfficeProducts()).thenReturn(List.of(p));
        var extra=image("/uploads/images/side.png","V1");
        var duplicate=image("/uploads/images/duplicate.png",null);
        var foreign=image("/uploads/images/other-color.png","V2");
        when(gateway.getProductDetail("SPU1")).thenReturn(detailWithImages(List.of(extra,duplicate,foreign)));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var side=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);var g=side.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,400,300);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(side,"png",bytes);
        doReturn(bytes.toByteArray()).when(references).readImage(extra.getStorageUrl(),p.getName());
        doReturn(photo()).when(references).readImage(duplicate.getStorageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1","SKU1"));
        assertEquals(2,prepared.manifest().get(0).path("usedImageCount").asInt());
        String productExample=System.getProperty("rsdp.render.productExample");
        if(productExample!=null) java.nio.file.Files.write(java.nio.file.Path.of(productExample),prepared.images().get(0));
        assertEquals(2,prepared.manifest().get(0).path("images").size());
        verify(references,never()).readImage(foreign.getStorageUrl(),p.getName());
        var board=ImageIO.read(new ByteArrayInputStream(prepared.images().get(0)));boolean red=false,blue=false;
        for(int x=0;x<board.getWidth();x+=8)for(int y=0;y<board.getHeight();y+=8){red|=board.getRGB(x,y)==Color.RED.getRGB();blue|=board.getRGB(x,y)==Color.BLUE.getRGB();}
        assertTrue(red&&blue,"main and auxiliary image pixels must reach the supplied reference board");
        assertTrue(prepared.prompt().contains("同一个商品"));
        assertTrue(prepared.prompt().contains("左右位置、大小和排列顺序不表示家具的正面、背面或侧面"));
        assertTrue(prepared.prompt().contains("只自然呈现当前可见的一侧"));
        assertTrue(prepared.prompt().contains("不得把多张参考照片拼贴或融合成一件扭曲家具"));
        assertTrue(prepared.prompt().contains("优先生成结构完整、稳定、可正常使用的家具"));
        assertTrue(prepared.prompt().contains("不得出现部件重复、交叉、悬空、断裂、翻面或正反结构叠加"));
    }
    @Test void agentSelectedPrimaryLeadsReferenceBoardWithoutChangingBindings() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");p.setVariantId("V1");when(office.listOfficeProducts()).thenReturn(List.of(p));
        var extra=image("/uploads/images/side.png","V1");when(gateway.getProductDetail("SPU1")).thenReturn(detailWithImages(List.of(extra)));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var side=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);var g=side.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,400,300);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(side,"png",bytes);doReturn(bytes.toByteArray()).when(references).readImage(extra.getStorageUrl(),p.getName());
        ObjectNode profile=(ObjectNode)json.readTree("""
            {"cacheKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","views":[
              {"imageId":"P1-V1","view":"FRONT_3Q","confidence":0.7,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]},
              {"imageId":"P1-V2","view":"BACK_3Q","confidence":0.95,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]}],
             "overallShape":{"evidenceImageIds":["P1-V2"]},"components":[{"evidenceImageIds":["P1-V1"]}],"assemblyConstraints":["柜体只能位于一侧"],"mustPreserve":[],"appearancePrompt":"保持单侧柜体"}
            """);
        for(int i=0;i<7;i++) profile.withArray("assemblyConstraints").add("附加约束"+i);
        String mainId=contentId(photo()),sideId=contentId(bytes.toByteArray());
        ((ObjectNode)profile.withArray("views").get(0)).put("imageId",mainId);
        ((ObjectNode)profile.withArray("views").get(1)).put("imageId",sideId);
        profile.withObject("/overallShape").putArray("evidenceImageIds").add(sideId);
        ((ObjectNode)profile.withArray("components").get(0)).putArray("evidenceImageIds").add(mainId);
        var result=new ProductVisualUnderstandingService.ItemResult("GENERATED",profile,List.of());
        when(understanding.understandAll(anyList())).thenReturn(new ProductVisualUnderstandingService.Batch("SUCCEEDED","qwen3-vl-max",0,1,Map.of("SKU1",result)));
        var prepared=references.prepare(scene("SKU1","SKU1"));
        ObjectNode item=(ObjectNode)prepared.manifest().get(0);
        assertEquals(2,item.path("candidateImageCount").asInt());assertEquals(2,item.path("usedImageCount").asInt());
        assertEquals(sideId,item.path("images").get(0).path("viewId").asText());assertEquals(mainId,item.path("images").get(1).path("viewId").asText());
        assertEquals("F1",item.path("furnitureReferenceIds").get(0).asText());
        assertTrue(prepared.prompt().contains("柜体只能位于一侧"));assertTrue(prepared.prompt().contains("附加约束4"));assertFalse(prepared.prompt().contains("附加约束5"));assertFalse(prepared.prompt().contains("附加约束6"));assertEquals("SUCCEEDED",prepared.understanding().path("status").asText());
        var board=ImageIO.read(new ByteArrayInputStream(prepared.images().get(0)));assertEquals(Color.BLUE.getRGB(),board.getRGB(board.getWidth()/2,board.getHeight()/2));
    }
    @Test void isolatesReliableTargetAndRecordsOriginalAndGenerationHashes() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");p.setVariantId("V1");when(office.listOfficeProducts()).thenReturn(List.of(p));
        when(gateway.getProductDetail("SPU1")).thenReturn(detailWithImages(List.of(image("/uploads/images/side.png","V1"))));
        doReturn(photo(),photo(Color.BLUE.getRGB())).when(references).readImage(anyString(),eq(p.getName()));
        ObjectNode profile=(ObjectNode)json.readTree("""
            {"views":[
              {"imageId":"P1-V1","view":"FRONT_3Q","confidence":0.7,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]},
              {"imageId":"P1-V2","view":"FRONT_3Q","imageComposition":"PRODUCT_SET","target":{"status":"IDENTIFIED","category":"茶桌","description":"中央茶桌","bbox":[100,100,800,900],"confidence":0.95},"containsNonTargetProducts":true,"requiresSubjectIsolation":true,"originalImageSuitableForDirectGeneration":false,"confidence":0.95,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":["MULTIPLE_PRODUCTS"]}],
             "overallShape":{"evidenceImageIds":["P1-V2"]},"components":[],"assemblyConstraints":[],"appearancePrompt":"只描述中央茶桌"}
            """);
        String mainId=contentId(photo()),sideId=contentId(photo(Color.BLUE.getRGB()));
        ((ObjectNode)profile.withArray("views").get(0)).put("imageId",mainId);
        ((ObjectNode)profile.withArray("views").get(1)).put("imageId",sideId);
        profile.withObject("/overallShape").putArray("evidenceImageIds").add(sideId);
        var result=new ProductVisualUnderstandingService.ItemResult("GENERATED",profile,List.of());
        when(understanding.understandAll(anyList())).thenReturn(new ProductVisualUnderstandingService.Batch("SUCCEEDED","qwen3-vl-plus",0,1,Map.of("SKU1",result)));
        ObjectNode item=(ObjectNode)references.prepare(scene("SKU1")).manifest().get(0);
        ObjectNode used=(ObjectNode)item.path("images").get(0);
        assertEquals(sideId,used.path("viewId").asText());assertEquals("TARGET_BBOX_CROP",used.path("isolationMethod").asText());
        assertEquals(4,used.path("targetBbox").size());assertEquals(4,used.path("appliedCropPixels").size());
        assertNotEquals(used.path("sourceImageSha256").asText(),used.path("generationImageSha256").asText());
        assertTrue(used.path("requiresManualReview").asBoolean());assertFalse(item.path("warnings").isEmpty());
    }

    @Test void fallsBackToAvailableAuxiliaryViewIfMainImageIsMissing() throws Exception {
        var p=product("SKU1");p.setRspuId("SPU1");when(office.listOfficeProducts()).thenReturn(List.of(p));
        var extra=image("/uploads/images/side.png",null);
        when(gateway.getProductDetail("SPU1")).thenReturn(detailWithImages(List.of(extra)));
        doThrow(new BusinessException("缺失")).when(references).readImage(p.getImageUrl(),p.getName());
        doReturn(photo()).when(references).readImage(extra.getStorageUrl(),p.getName());
        var prepared=references.prepare(scene("SKU1"));
        assertEquals(1,prepared.manifest().get(0).path("usedImageCount").asInt());
        assertFalse(prepared.manifest().get(0).path("warnings").isEmpty());
    }
    @Test void invalidAgentAuditCannotSilentlyFallBackToOriginalSetImage() throws Exception {
        var p=product("SKU1");when(office.listOfficeProducts()).thenReturn(List.of(p));
        doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName());
        var invalid=new ProductVisualUnderstandingService.ItemResult("INVALID_RESPONSE",null,List.of("目标校验失败"));
        when(understanding.understandAll(anyList())).thenReturn(new ProductVisualUnderstandingService.Batch("UNAVAILABLE","qwen3-vl-max",0,1,Map.of("SKU1",invalid)));
        var error=assertThrows(BusinessException.class,()->references.prepare(scene("SKU1")));
        assertTrue(error.getMessage().contains("未通过校验"));
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
    @Test void readsCatalogLocalImageWithoutFetchingItsPublicHostname() throws Exception {
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"catalog-images");
        byte[] bytes=photo(); java.nio.file.Files.write(directory.resolve("selected.png"),bytes);
        doReturn(directory).when(references).imageDirectory();
        assertArrayEquals(bytes,references.readImage("http://old-server:8082/uploads/images/selected.png","商品"));
        assertArrayEquals(bytes,references.readImage("/uploads/images/selected.png","商品"));
        assertThrows(BusinessException.class,()->references.readImage("/uploads/images/missing.png","商品"));
    }
    @Test void rejectsMoreThanTwelveProductTypesBeforeBilling() throws Exception {
        var catalog=new java.util.ArrayList<OfficeProductDto>(); var ids=new String[13];
        for(int i=0;i<13;i++) { ids[i]="SKU"+i;var p=product(ids[i]);catalog.add(p);doReturn(photo()).when(references).readImage(p.getImageUrl(),p.getName()); }
        when(office.listOfficeProducts()).thenReturn(catalog);
        var error=assertThrows(BusinessException.class,()->references.prepare(scene(ids)));
        assertTrue(error.getMessage().contains("12 种商品"));
        verify(references,never()).readImage(anyString(),anyString());
    }
}
