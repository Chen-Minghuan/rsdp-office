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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderPreviewServiceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final OfficeService office=mock(OfficeService.class);
    private final RenderProductReferences products=mock(RenderProductReferences.class);
    private final RenderProductAuditService productAudit=mock(RenderProductAuditService.class);
    private final RenderGeometryAuditService geometryAudit=mock(RenderGeometryAuditService.class);
    private final RenderLayoutContext physicalContext=new RenderLayoutContext(mock(FloorPlanService.class));
    private final OfficeWorkspaceLockMapper projects=mock(OfficeWorkspaceLockMapper.class);
    private final RenderPreviewMapper mapper=mock(RenderPreviewMapper.class);
    private final QwenImageClient provider=mock(QwenImageClient.class);
    private final RenderPreviewFiles files=mock(RenderPreviewFiles.class);
    private final RenderProperties properties=new RenderProperties();
    private final Map<String,RenderPreview> records=new HashMap<>();
    private final OfficeProposalConfirmationService confirmations=mock(OfficeProposalConfirmationService.class);
    private final TransactionOperations transactions=mock(TransactionOperations.class);
    private final RenderPreviewService service=new RenderPreviewService(office,products,productAudit,geometryAudit,physicalContext,projects,mapper,provider,files,properties,json,
        confirmations,mock(OfficeProjectAccessService.class),transactions);
    private CanvasDto canvas;
    private ArrayNode objects;

    @BeforeEach void setup() throws Exception {
        when(transactions.execute(any())).thenAnswer(call->((TransactionCallback<Object>)call.getArgument(0)).doInTransaction(null));
        objects=(ArrayNode)json.readTree("""
            [{"id":"ROOM","kind":"zone","name":"L形办公室","reviewUnit":"mm","evidenceKind":"MANUAL",
              "polygon":[[-6000,-4000],[0,-4000],[0,-2000],[-2000,-2000],[-2000,0],[-6000,0]]},
             {"id":"F1","kind":"furniture","zoneId":"ROOM","label":"办公桌","x":-5500,"y":-3500,"w":1200,"h":600,"rotation":90,"productId":""}]
            """);
        when(products.prepare(any(),any(RenderFurnitureBindings.Prepared.class))).thenReturn(RenderProductReferences.Prepared.empty());
        canvas=new CanvasDto(); canvas.setRevision(3); sync();
        when(office.getCanvas("PROJECT")).thenAnswer(call->canvas);
        when(projects.lockWorkspace("PROJECT")).thenReturn(1);
        when(provider.base()).thenReturn(properties.getBaseUrl());
        when(provider.submit(anyList(),anyString(),any())).thenReturn(json.readTree("{\"output\":{\"task_id\":\"task-1\"},\"request_id\":\"req-1\"}"));
        when(files.reference(anyString(),any())).thenReturn("/uploads/render-previews/reference.png");
        when(files.download(anyString(),anyString())).thenReturn("/uploads/render-previews/result.png");
        when(files.read(anyString())).thenReturn(new byte[]{1,2,3});
        when(productAudit.audit(any(),any(),anyList(),any(),any(),any())).thenReturn(json.createObjectNode().put("status","PASS"));
        when(productAudit.unavailable()).thenReturn(json.createObjectNode().put("status","UNAVAILABLE"));
        when(geometryAudit.audit(any(),any(),any(),any())).thenReturn(json.createObjectNode().put("status","PASS"));
        when(geometryAudit.unavailable()).thenReturn(json.createObjectNode().put("status","UNAVAILABLE"));
        when(mapper.insert(any())).thenAnswer(call->{ RenderPreview r=call.getArgument(0); records.put(r.getPreviewId(),r); return 1; });
        when(mapper.byChange(eq("PROJECT"),anyString())).thenAnswer(call->records.values().stream().filter(r->r.getClientChangeId().equals(call.getArgument(1))).findFirst().orElse(null));
        when(mapper.lock(eq("PROJECT"),anyString())).thenAnswer(call->records.get(call.getArgument(1)));
        when(mapper.updateSnapshot(any())).thenReturn(1);
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
        verify(provider,times(1)).submit(anyList(),argThat(prompt->!prompt.contains("图3为带P编号")),any());
        var changed=request(); changed.setStyle("工业风"); assertThrows(BusinessException.class,()->service.create("PROJECT",changed));
    }

    @Test void legacyHashRetryRecoversOriginalTaskWithoutSubmittingAgain() throws Exception {
        var first=service.create("PROJECT",request());
        var record=records.get(first.path("previewId").asText());
        record.setContentHash(legacyHash(request(),null,true));
        clearInvocations(provider);

        var recovered=service.create("PROJECT",request());
        assertEquals(first.path("previewId"),recovered.path("previewId"));
        verify(provider,never()).submit(anyList(),anyString(),any());

        var changed=request();changed.setStyle("工业风");
        assertThrows(BusinessException.class,()->service.create("PROJECT",changed));
        verify(provider,never()).submit(anyList(),anyString(),any());
    }

    @Test void legacyWhiteModelHashRetryRecoversWithoutReopeningScreenshotGeneration() throws Exception {
        byte[] screenshot=viewPng(800,600);
        var guided=guidedRequest(screenshot);
        var first=service.create("PROJECT",request());
        records.get(first.path("previewId").asText()).setContentHash(legacyHash(guided,screenshot,true));
        clearInvocations(provider);

        var recovered=service.create("PROJECT",guided);
        assertEquals(first.path("previewId"),recovered.path("previewId"));
        verify(provider,never()).submit(anyList(),anyString(),any());
        var fresh=guidedRequest(viewPng(800,600));fresh.setClientChangeId("CHANGE-NEW");
        assertThrows(BusinessException.class,()->service.create("PROJECT",fresh));
    }

    private String legacyHash(RenderPreviewRequest request,byte[] viewImage,boolean includeViewImage) throws Exception {
        Map<String,Object> content=new LinkedHashMap<>();content.put("snapshot",RenderPreviewScene.canonical(request.getExpectedSnapshot()));
        content.put("style",request.getStyle());content.put("view",request.getView());content.put("instructions",request.getInstructions());content.put("model",properties.getModel());
        if(includeViewImage)content.put("viewImage",viewImage==null?"":VisionImagePreparer.sha256(viewImage));
        if(request.getConfirmationId()!=null)content.put("confirmationId",request.getConfirmationId());
        String canonical=json.writeValueAsString(RenderPreviewScene.canonical(json.valueToTree(content)));
        return VisionImagePreparer.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void formalRenderRequiresCurrentConfirmationAndKeepsExpiredResultsHistorical() {
        var request = request();
        request.setConfirmationId("PROP-A");
        when(confirmations.requireRenderScene("PROJECT", "ROOM", "PROP-A")).thenThrow(new BusinessException("方案已过期"));
        assertThrows(BusinessException.class, () -> service.create("PROJECT", request));
        verifyNoInteractions(provider);

        doReturn(request.getExpectedSnapshot()).when(confirmations).requireRenderScene("PROJECT", "ROOM", "PROP-A");
        var now = LocalDateTime.now();
        when(confirmations.latest("PROJECT")).thenReturn(new OfficeProposalConfirmationService.Status("PROP-A", 3, now, true));
        var job = service.create("PROJECT", request);
        assertEquals("CONFIRMED_SCHEME", job.path("purpose").asText());
        assertEquals("PROP-A", job.path("confirmationId").asText());
        assertFalse(job.path("stale").asBoolean());

        when(confirmations.latest("PROJECT")).thenReturn(new OfficeProposalConfirmationService.Status("PROP-A", 3, now, false));
        assertTrue(service.create("PROJECT", request).path("stale").asBoolean());
        verify(provider, times(1)).submit(anyList(), anyString(), any());
        request.setConfirmationId("PROP-B");
        assertThrows(BusinessException.class, () -> service.create("PROJECT", request));
        verify(provider, times(1)).submit(anyList(), anyString(), any());
    }
    @Test void sendsSelectedProductPhotosAndKeepsReferencesSeparateFromLayoutSnapshot() throws Exception {
        ((ObjectNode)objects.get(1)).put("productId","SKU-1"); sync();
        ArrayNode manifest=json.createArrayNode(); manifest.addObject().put("productId","SKU-1").put("referenceId","P1");
        when(products.prepare(any(),any(RenderFurnitureBindings.Prepared.class))).thenReturn(new RenderProductReferences.Prepared(List.of(new byte[]{9,8,7}),manifest,"P1 对应家具1，保留商品外观"));
        var result=service.create("PROJECT",request());
        assertTrue(result.path("productImagesUsed").asBoolean());
        assertEquals("SKU-1",result.path("productReferences").path("items").get(0).path("productId").asText());
        assertEquals(request().getExpectedSnapshot(),result.path("snapshot"));
        assertFalse(result.path("stale").asBoolean());
        verify(provider).submit(argThat((List<byte[]> refs) -> refs.size()==3 && Arrays.equals(refs.get(2),new byte[]{9,8,7})),contains("P1 对应家具1"),any());
        String stored=records.values().iterator().next().getSnapshotJson();
        assertTrue(stored.contains("renderProductReferences"));
        assertTrue(stored.contains("guideUrl"));
    }
    @Test void missingSelectedProductPhotoStopsBeforeBilling() {
        when(products.prepare(any(),any(RenderFurnitureBindings.Prepared.class))).thenThrow(new BusinessException("所选商品图片缺失"));
        assertThrows(BusinessException.class,()->service.create("PROJECT",request()));
        verify(provider).requireConfigured(); verify(provider,never()).submit(anyList(),anyString(),any()); verify(mapper,never()).insert(any());
    }
    @Test void rejectsOutdatedOrForgedSnapshotsAndUncalibratedGeometryBeforeCallingProvider() {
        var old=request(); old.setCanvasRevision(2); assertThrows(BusinessException.class,()->service.create("PROJECT",old));
        var forged=request(); ((ObjectNode)forged.getExpectedSnapshot().path("furniture").get(0)).put("w",800);
        assertThrows(BusinessException.class,()->service.create("PROJECT",forged));
        ((ObjectNode)objects.get(0)).put("reviewUnit","source"); sync();
        assertThrows(BusinessException.class,()->service.create("PROJECT",request())); verifyNoInteractions(provider);
    }
    @Test void historicalPendingAndRunningSnapshotsWithoutProductReferencesContinuePolling() throws Exception {
        when(provider.poll(anyString(),anyString())).thenReturn(json.readTree("""
            {"output":{"task_status":"SUCCEEDED","choices":[{"message":{"content":[{"image":"https://result.oss-cn-beijing.aliyuncs.com/image.png"}]}}]}}
            """));
        for(String historicalStatus:List.of("PENDING","RUNNING")) {
            var historicalRequest=request();historicalRequest.setClientChangeId("CHANGE-"+historicalStatus);
            String id=service.create("PROJECT",historicalRequest).path("previewId").asText();
            var record=records.get(id);record.setStatus(historicalStatus);
            ObjectNode snapshot=(ObjectNode)json.readTree(record.getSnapshotJson());snapshot.remove("renderProductReferences");
            record.setSnapshotJson(json.writeValueAsString(snapshot));

            var done=service.get("PROJECT",id);
            assertEquals("SUCCEEDED",done.path("status").asText());
            assertEquals("/uploads/render-previews/result.png",done.path("imageUrl").asText());
            assertFalse(done.has("qualityAudit"));assertFalse(done.has("geometryAudit"));assertFalse(done.has("generationTiming"));
        }
        verify(files,times(2)).download(anyString(),anyString());
        verify(productAudit,never()).audit(any(),any(),anyList(),any(),any(),any());
        verify(geometryAudit,never()).audit(any(),any(),any(),any());
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
        assertEquals("PASS",done.path("qualityAudit").path("status").asText());
        assertEquals("PASS",done.path("geometryAudit").path("status").asText());
        assertTrue(done.path("generationTiming").path("requestStartedAt").asLong()>0);assertTrue(done.path("generationTiming").path("submittedAt").asLong()>0);
        assertTrue(done.path("generationTiming").path("providerCompletedAt").asLong()>0);assertTrue(done.path("generationTiming").path("readyAt").asLong()>=done.path("generationTiming").path("providerCompletedAt").asLong());
        assertTrue(done.path("generationTiming").path("totalMs").asLong()>=0);assertTrue(done.path("generationTiming").path("providerMs").asLong()>=0);
        assertTrue(records.get(id).getSnapshotJson().contains("qualityAudit")); verify(mapper).updateSnapshot(records.get(id));
        service.get("PROJECT",id); verify(productAudit,times(1)).audit(any(),any(),anyList(),any(),any(),any());
        verify(geometryAudit,times(1)).audit(any(),any(),any(),any());
    }
    @Test void transientPollOrDownloadFailuresDoNotSubmitAnotherBilledTask() {
        String id=service.create("PROJECT",request()).path("previewId").asText();
        when(provider.poll(anyString(),anyString())).thenThrow(new BusinessException("暂时失败"));
        assertThrows(BusinessException.class,()->service.get("PROJECT",id));
        assertEquals("PENDING",records.get(id).getStatus()); verify(provider,times(1)).submit(anyList(),anyString(),any());
    }
    @Test void providerSubmissionFailureIsPersistedAndDoesNotFallBackToDemo() {
        when(provider.submit(anyList(),anyString(),any())).thenThrow(new BusinessException("模型权限不足"));
        var failed=service.create("PROJECT",request());
        assertEquals("FAILED",failed.path("status").asText()); assertEquals("模型权限不足",failed.path("errorMessage").asText());
        assertTrue(failed.path("imageUrl").isNull());
        service.create("PROJECT",request()); verify(provider,times(1)).submit(anyList(),anyString(),any());
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
        assertArrayEquals(guide,RenderSceneGuide.png(scene,"INTERIOR"),"v9 keeps one fixed isometric control camera");
        String guideExample=System.getProperty("rsdp.render.guideExample");
        if(guideExample!=null) java.nio.file.Files.write(java.nio.file.Path.of(guideExample),guide);
        String prompt=RenderPreviewScene.prompt(scene,"现代简约","INTERIOR","");
        assertTrue(prompt.contains("照片级写实的3D室内效果图"));assertTrue(prompt.contains("材质真实细腻"));assertTrue(prompt.contains("不输出文字、标注、图表或拼贴"));assertTrue(prompt.contains("只输出一个完整空间画面"));assertFalse(prompt.contains("商品清单"));
    }
    @Test void overlappingFurnitureIsRejectedAndForeignProjectCannotReadTasks() {
        objects.add(objects.get(1).deepCopy()); ((ObjectNode)objects.get(2)).put("id","F2");
        assertThrows(BusinessException.class,()->RenderPreviewScene.validate(RenderPreviewScene.snapshot(objects,"ROOM")));
        assertThrows(BusinessException.class,()->service.get("OTHER","RPRE-UNKNOWN"));
    }

    private byte[] viewPng(int w, int h) throws Exception {
        var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray();
    }
    private RenderPreviewRequest guidedRequest(byte[] png) {
        var r=request(); r.setViewImage("data:image/png;base64,"+Base64.getEncoder().encodeToString(png)); return r;
    }

    @Test void storesProductUnderstandingAndCompilesAgentTextBehindDeterministicRules() {
        ObjectNode understanding=json.createObjectNode().put("status","PARTIAL").put("agentModel","qwen3-vl-max").put("cacheHits",1).put("generatedCount",0);
        ArrayNode manifest=JsonNodeFactory.instance.arrayNode();manifest.addObject().put("agentStatus","CACHE_HIT").putObject("visualProfile").put("appearancePrompt","商品视觉描述");
        when(products.prepare(any(),any(RenderFurnitureBindings.Prepared.class))).thenReturn(new RenderProductReferences.Prepared(List.of(),manifest,"商品视觉描述",understanding));
        var result=service.create("PROJECT",request());
        assertEquals("PARTIAL",result.path("agentStatus").asText());
        assertEquals("qwen3-vl-max",result.path("productUnderstanding").path("agentModel").asText());
        assertEquals("PARTIAL",result.path("productReferences").path("productUnderstanding").path("status").asText());
        verify(provider).submit(anyList(),argThat(prompt->prompt.contains("商品视觉描述") && prompt.contains("始终遵守程序给定的空间轮廓")),any());
    }
    @Test void semanticGuideAndPromptShareFurnitureBindings() {
        var result=service.create("PROJECT",request());
        assertEquals("spaceGuide",result.path("sourceType").asText());
        assertEquals("ISOMETRIC",result.path("view").asText());
        assertEquals("furniture-product-v1",result.path("productReferences").path("bindingVersion").asText());
        assertEquals("F1",result.path("productReferences").path("furnitureBindings").get(0).path("referenceId").asText());
        assertEquals("F1",result.path("productReferences").path("guideLabels").get(0).path("referenceId").asText());
        assertFalse(result.path("productReferences").path("bindingGuideUrl").asText().isBlank());
        var scene=request().getExpectedSnapshot();var bindings=RenderFurnitureBindings.from(scene);var spatial=RenderSpatialControl.prepare(scene,bindings);
        var clean=RenderSceneGuide.render(scene,"ISOMETRIC",bindings,false,spatial);var labelled=RenderSceneGuide.render(scene,"ISOMETRIC",bindings,true,spatial);var plan=RenderPlanGuide.render(spatial,false);
        var references=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).submit(references.capture(),contains("位于空间左侧后侧的办公桌"),any());
        List<byte[]> actual=references.getValue();assertEquals(2,actual.size());assertArrayEquals(clean.image(),actual.get(0));assertArrayEquals(plan.image(),actual.get(1));
        assertFalse(Arrays.equals(clean.image(),labelled.image()));assertEquals(1,labelled.labels().size());assertTrue(clean.labels().isEmpty());
    }
    @Test void conciseNoPlanExperimentIsPersistedWithoutLosingAuditPlanOrIdempotencyIsolation() {
        var experiment=request();experiment.setPromptMode("CONCISE_V11");experiment.setReferenceMode("GUIDE_PRODUCT");experiment.setSeed(1101L);experiment.setSampleIndex(1);
        var result=service.create("PROJECT",experiment);assertEquals("C",result.path("generationExperiment").path("sampleGroup").asText());
        assertEquals("selected-products-multiview-v11-prompt-ab",result.path("productReferences").path("version").asText());assertFalse(result.path("planGuideUrl").asText().isBlank());
        assertEquals(1,result.path("productReferences").path("generationImages").size());assertEquals("CLEAN_GUIDE",result.path("productReferences").path("generationImages").get(0).path("role").asText());
        var images=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).submit(images.capture(),argThat(prompt->!prompt.contains("SPATIAL_CONTROL_V1")&&prompt.contains("场景严格保持1件家具")),any());
        assertEquals(1,images.getValue().size());
        var collision=request();collision.setPromptMode("CONCISE_V11");assertThrows(BusinessException.class,()->service.create("PROJECT",collision));
    }    @Test void legacyScreenshotsAndInteriorRequestsAreRejectedBeforeBilling() throws Exception {
        assertThrows(BusinessException.class,()->service.create("PROJECT",guidedRequest(viewPng(800,600))));
        var interior=request();interior.setView("INTERIOR");
        assertThrows(BusinessException.class,()->service.create("PROJECT",interior));
        verifyNoInteractions(provider);verify(mapper,never()).insert(any());
    }
    @Test void invalidViewImageIsRejectedBeforeBilling() {
        var malformed=request();malformed.setViewImage("data:image/png;base64,%%%not-base64%%%");
        assertThrows(BusinessException.class,()->service.create("PROJECT",malformed));
        verifyNoInteractions(provider);verify(mapper,never()).insert(any());
    }
    @Test void invalidGenerationConfigurationStopsBeforeProductUnderstanding() {
        doThrow(new BusinessException("生图配置无效")).when(provider).requireConfigured();
        assertThrows(BusinessException.class,()->service.create("PROJECT",request()));
        verifyNoInteractions(products);verify(provider,never()).submit(anyList(),anyString(),any());verify(mapper,never()).insert(any());
    }

    @Test void productUnderstandingRechecksRevisionBeforeReservationAndBilling() {
        when(products.prepare(any(),any(RenderFurnitureBindings.Prepared.class))).thenAnswer(call->{
            canvas.setRevision(4);
            return RenderProductReferences.Prepared.empty();
        });
        assertThrows(BusinessException.class,()->service.create("PROJECT",request()));
        verify(provider).requireConfigured();verify(provider,never()).submit(anyList(),anyString(),any());verify(mapper,never()).insert(any());
    }

    @Test void reservedSubmissionWithoutProviderTaskIsNotFailedOrSubmittedAgain() {
        String id=service.create("PROJECT",request()).path("previewId").asText();
        RenderPreview reserved=records.get(id);reserved.setStatus("SUBMITTING");reserved.setProviderTaskId(null);
        var recovered=service.get("PROJECT",id);
        assertEquals("SUBMITTING",recovered.path("status").asText());
        verify(provider,never()).poll(anyString(),anyString());verify(provider,times(1)).submit(anyList(),anyString(),any());
    }

    @Test void remoteGenerationPollingDownloadAndAuditsRunOutsideTransactions() throws Exception {
        boolean[] inTransaction={false};
        doAnswer(call->{
            inTransaction[0]=true;
            try { return ((TransactionCallback<Object>)call.getArgument(0)).doInTransaction(null); }
            finally { inTransaction[0]=false; }
        }).when(transactions).execute(any());
        doAnswer(call->{assertFalse(inTransaction[0],"商品理解不能持有工作区事务锁");return RenderProductReferences.Prepared.empty();})
            .when(products).prepare(any(),any(RenderFurnitureBindings.Prepared.class));
        doAnswer(call->{assertFalse(inTransaction[0],"生图提交不能持有数据库事务锁");return json.readTree("{\"output\":{\"task_id\":\"task-1\"},\"request_id\":\"req-1\"}");})
            .when(provider).submit(anyList(),anyString(),any());
        String id=service.create("PROJECT",request()).path("previewId").asText();
        doAnswer(call->{assertFalse(inTransaction[0],"轮询不能持有预览行锁");return json.readTree("{\"output\":{\"task_status\":\"SUCCEEDED\",\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://example.com/result.png\"}]}}]}}");})
            .when(provider).poll(anyString(),anyString());
        doAnswer(call->{assertFalse(inTransaction[0],"图片下载不能持有预览行锁");return "/uploads/render-previews/result.png";})
            .when(files).download(anyString(),anyString());
        doAnswer(call->{
            assertFalse(inTransaction[0],"商品审核不能持有预览行锁");
            assertEquals("SUCCEEDED",records.get(id).getStatus(),"成功图片必须先保存再审核");
            assertNotNull(records.get(id).getImageUrl());return json.createObjectNode().put("status","PASS");
        }).when(productAudit).audit(any(),any(),anyList(),any(),any(),any());
        doAnswer(call->{assertFalse(inTransaction[0],"几何审核不能持有预览行锁");return json.createObjectNode().put("status","PASS");})
            .when(geometryAudit).audit(any(),any(),any(),any());
        assertEquals("SUCCEEDED",service.get("PROJECT",id).path("status").asText());
        verify(productAudit).audit(any(),any(),anyList(),any(),any(),any());verify(geometryAudit).audit(any(),any(),any(),any());
    }
}
