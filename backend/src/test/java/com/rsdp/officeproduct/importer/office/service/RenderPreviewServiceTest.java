package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.office.dto.*;
import com.rsdp.officeproduct.importer.office.entity.RenderPreview;
import com.rsdp.officeproduct.importer.office.mapper.*;
import com.rsdp.officeproduct.importer.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderPreviewServiceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final OfficeService office=mock(OfficeService.class);
    private final RenderProductReferences products=mock(RenderProductReferences.class);
    private final RenderLayoutContext physicalContext=new RenderLayoutContext(mock(FloorPlanService.class));
    private final OfficeWorkspaceLockMapper projects=mock(OfficeWorkspaceLockMapper.class);
    private final RenderPreviewMapper mapper=mock(RenderPreviewMapper.class);
    private final QwenImageClient provider=mock(QwenImageClient.class);
    private final RenderPreviewFiles files=mock(RenderPreviewFiles.class);
    private final RenderProperties properties=new RenderProperties();
    private final Map<String,RenderPreview> records=new HashMap<>();
    private final RenderPreviewService service=new RenderPreviewService(office,products,physicalContext,projects,mapper,provider,files,properties,json);
    private CanvasDto canvas;
    private ArrayNode objects;

    @BeforeEach void setup() throws Exception {
        objects=(ArrayNode)json.readTree("""
            [{"id":"ROOM","kind":"zone","name":"L形办公室","reviewUnit":"mm","evidenceKind":"MANUAL",
              "polygon":[[-6000,-4000],[0,-4000],[0,-2000],[-2000,-2000],[-2000,0],[-6000,0]]},
             {"id":"F1","kind":"furniture","zoneId":"ROOM","label":"办公桌","x":-5500,"y":-3500,"w":1200,"h":600,"rotation":90,"productId":""}]
            """);
        when(products.prepare(any())).thenReturn(RenderProductReferences.Prepared.empty());
        canvas=new CanvasDto(); canvas.setRevision(3); sync();
        when(office.getCanvas("PROJECT")).thenAnswer(call->canvas);
        when(projects.lockWorkspace("PROJECT")).thenReturn(1);
        when(provider.base()).thenReturn(properties.getBaseUrl());
        when(provider.submit(anyList(),anyString())).thenReturn(json.readTree("{\"output\":{\"task_id\":\"task-1\"},\"request_id\":\"req-1\"}"));
        when(files.reference(anyString(),any())).thenReturn("/uploads/render-previews/reference.png");
        when(files.download(anyString(),anyString())).thenReturn("/uploads/render-previews/result.png");
        when(mapper.insert(any())).thenAnswer(call->{ RenderPreview r=call.getArgument(0); records.put(r.getPreviewId(),r); return 1; });
        when(mapper.byChange(eq("PROJECT"),anyString())).thenAnswer(call->records.values().stream().filter(r->r.getClientChangeId().equals(call.getArgument(1))).findFirst().orElse(null));
        when(mapper.lock(eq("PROJECT"),anyString())).thenAnswer(call->records.get(call.getArgument(1)));
    }
    private void sync() { canvas.setObjects(json.convertValue(objects,new com.fasterxml.jackson.core.type.TypeReference<List<Object>>(){})); }
    private RenderPreviewRequest request() {
        RenderPreviewRequest r=new RenderPreviewRequest(); r.setClientChangeId("CHANGE-1"); r.setZoneId("ROOM"); r.setCanvasRevision(3);
        r.setExpectedSnapshot(RenderPreviewScene.snapshot(objects,"ROOM")); r.setStyle("现代简约"); r.setView("ISOMETRIC"); r.setInstructions(""); return r;
    }

    @Test void generatesWithoutProductsAndMakesRepeatedRequestsIdempotent() {
        var first=service.create("PROJECT",request());
        var repeated=service.create("PROJECT",request());
        assertEquals(first.path("previewId"),repeated.path("previewId")); assertEquals("CONCEPT_PREVIEW",first.path("purpose").asText());
        assertFalse(first.path("stale").asBoolean()); assertFalse(first.has("snapshotJson")); assertFalse(first.has("prompt"));
        assertEquals(6,first.path("snapshot").path("zone").path("polygon").size());
        verify(provider,times(1)).submit(anyList(),anyString());
        var changed=request(); changed.setStyle("工业风"); assertThrows(BusinessException.class,()->service.create("PROJECT",changed));
    }
    @Test void sendsSelectedProductPhotosAndKeepsReferencesSeparateFromLayoutSnapshot() throws Exception {
        ((ObjectNode)objects.get(1)).put("productId","SKU-1"); sync();
        ArrayNode manifest=json.createArrayNode(); manifest.addObject().put("productId","SKU-1").put("referenceId","P1");
        when(products.prepare(any())).thenReturn(new RenderProductReferences.Prepared(List.of(new byte[]{9,8,7}),manifest,"P1 对应家具1，保留商品外观"));
        var result=service.create("PROJECT",request());
        assertTrue(result.path("productImagesUsed").asBoolean());
        assertEquals("SKU-1",result.path("productReferences").path("items").get(0).path("productId").asText());
        assertEquals(request().getExpectedSnapshot(),result.path("snapshot"));
        assertFalse(result.path("stale").asBoolean());
        verify(provider).submit(argThat((List<byte[]> refs) -> refs.size()==2 && Arrays.equals(refs.get(1),new byte[]{9,8,7})),contains("P1 对应家具1"));
        String stored=records.values().iterator().next().getSnapshotJson();
        assertTrue(stored.contains("renderProductReferences"));
        assertTrue(stored.contains("guideUrl"));
    }
    @Test void missingSelectedProductPhotoStopsBeforeBilling() {
        when(products.prepare(any())).thenThrow(new BusinessException("所选商品图片缺失"));
        assertThrows(BusinessException.class,()->service.create("PROJECT",request()));
        verifyNoInteractions(provider); verify(mapper,never()).insert(any());
    }
    @Test void rejectsOutdatedOrForgedSnapshotsAndUncalibratedGeometryBeforeCallingProvider() {
        var old=request(); old.setCanvasRevision(2); assertThrows(BusinessException.class,()->service.create("PROJECT",old));
        var forged=request(); ((ObjectNode)forged.getExpectedSnapshot().path("furniture").get(0)).put("w",800);
        assertThrows(BusinessException.class,()->service.create("PROJECT",forged));
        ((ObjectNode)objects.get(0)).put("reviewUnit","source"); sync();
        assertThrows(BusinessException.class,()->service.create("PROJECT",request())); verifyNoInteractions(provider);
    }
    @Test void oldAsyncResultsStayBoundToTheirSnapshotWhenCurrentRoomChanges() throws Exception {
        String id=service.create("PROJECT",request()).path("previewId").asText();
        ((ObjectNode)objects.get(1)).put("x",-5100); canvas.setRevision(4); sync();
        when(provider.poll(anyString(),anyString())).thenReturn(json.readTree("""
            {"output":{"task_status":"SUCCEEDED","choices":[{"message":{"content":[{"image":"https://result.oss-cn-beijing.aliyuncs.com/image.png"}]}}]}}
            """));
        var done=service.get("PROJECT",id);
        assertEquals("SUCCEEDED",done.path("status").asText()); assertTrue(done.path("stale").asBoolean());
        assertEquals(-5500,done.path("snapshot").path("furniture").get(0).path("x").asInt());
        assertEquals(-5100,objects.get(1).path("x").asInt()); verify(files).download(eq(id),anyString());
    }
    @Test void transientPollOrDownloadFailuresDoNotSubmitAnotherBilledTask() {
        String id=service.create("PROJECT",request()).path("previewId").asText();
        when(provider.poll(anyString(),anyString())).thenThrow(new BusinessException("暂时失败"));
        assertThrows(BusinessException.class,()->service.get("PROJECT",id));
        assertEquals("PENDING",records.get(id).getStatus()); verify(provider,times(1)).submit(anyList(),anyString());
    }
    @Test void providerSubmissionFailureIsPersistedAndDoesNotFallBackToDemo() {
        when(provider.submit(anyList(),anyString())).thenThrow(new BusinessException("模型权限不足"));
        var failed=service.create("PROJECT",request());
        assertEquals("FAILED",failed.path("status").asText()); assertEquals("模型权限不足",failed.path("errorMessage").asText());
        assertTrue(failed.path("imageUrl").isNull());
        service.create("PROJECT",request()); verify(provider,times(1)).submit(anyList(),anyString());
    }
    @Test void referencePreservesConcaveRoomNegativeCoordinatesAndRotationAndRejectsBadPlacement() throws Exception {
        var scene=RenderPreviewScene.snapshot(objects,"ROOM"); RenderPreviewScene.validate(scene);
        var image=ImageIO.read(new ByteArrayInputStream(RenderPreviewScene.referencePng(scene)));
        assertEquals(1536,image.getWidth()); assertEquals(1024,image.getHeight());
        // The concave upper/right cutout stays blank; the room is not replaced by its bounding box.
        assertEquals(java.awt.Color.WHITE.getRGB(),image.getRGB(1200,800));
        var footprint=RenderPreviewScene.furnitureShape(scene.path("furniture").get(0)).getBounds2D();
        assertEquals(600,footprint.getWidth(),.001); assertEquals(1200,footprint.getHeight(),.001);
        ((ObjectNode)objects.get(1)).put("x",-1000).put("y",-1000);
        assertThrows(BusinessException.class,()->RenderPreviewScene.validate(RenderPreviewScene.snapshot(objects,"ROOM")));
    }
    @Test void generationGuideIsVolumetricAndPromptRejectsPlanOverlays() throws Exception {
        var scene=RenderPreviewScene.snapshot(objects,"ROOM");
        byte[] flat=RenderPreviewScene.referencePng(scene),guide=RenderSceneGuide.png(scene,"ISOMETRIC");
        assertFalse(Arrays.equals(flat,guide),"provider must not receive the flat plan as an image to copy");
        var image=ImageIO.read(new ByteArrayInputStream(guide));assertEquals(1536,image.getWidth());assertEquals(1024,image.getHeight());
        assertFalse(Arrays.equals(guide,RenderSceneGuide.png(scene,"INTERIOR")),"view guides differ");
        String guideExample=System.getProperty("rsdp.render.guideExample");
        if(guideExample!=null) java.nio.file.Files.write(java.nio.file.Path.of(guideExample),guide);
        String prompt=RenderPreviewScene.prompt(scene,"现代简约","INTERIOR","");
        assertTrue(prompt.contains("满幅"));assertTrue(prompt.contains("严禁出现二维平面图"));assertTrue(prompt.contains("同一透视空间"));
    }
    @Test void overlappingFurnitureIsRejectedAndForeignProjectCannotReadTasks() {
        objects.add(objects.get(1).deepCopy()); ((ObjectNode)objects.get(2)).put("id","F2");
        assertThrows(BusinessException.class,()->RenderPreviewScene.validate(RenderPreviewScene.snapshot(objects,"ROOM")));
        assertThrows(BusinessException.class,()->service.get("OTHER","RPRE-UNKNOWN"));
    }
}
