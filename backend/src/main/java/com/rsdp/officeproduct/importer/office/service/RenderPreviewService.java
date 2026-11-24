package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.office.dto.RenderPreviewRequest;
import com.rsdp.officeproduct.importer.office.entity.RenderPreview;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import com.rsdp.officeproduct.importer.office.mapper.RenderPreviewMapper;
import com.rsdp.officeproduct.importer.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RenderPreviewService {
    private final OfficeService office;
    private final RenderProductReferences productReferences;
    private final RenderProductAuditService productAudit;
    private final RenderGeometryAuditService geometryAudit;
    private final RenderLayoutContext layoutContext;
    private final OfficeWorkspaceLockMapper workspaceLocks;
    private final RenderPreviewMapper previews;
    private final QwenImageClient provider;
    private final RenderPreviewFiles files;
    private final RenderProperties properties;
    private final ObjectMapper json;
    private final OfficeProposalConfirmationService confirmations;
    private final OfficeProjectAccessService access;

    @Transactional
    public ObjectNode create(String projectId, RenderPreviewRequest request) {
        project(projectId);
        access.requireFloorAccess(projectId);
        validateRequest(request);
        var options=RenderGenerationOptions.from(request.getPromptMode(),request.getReferenceMode(),request.getPromptExtend(),request.getSeed(),request.getSampleIndex());
        long requestStartedAt=System.currentTimeMillis();
        workspaceLocks.lockWorkspace(projectId);
        JsonNode expected=RenderPreviewScene.canonical(request.getExpectedSnapshot());
        Map<String,Object> content=new LinkedHashMap<>();content.put("snapshot",expected);content.put("style",request.getStyle());content.put("view",request.getView());content.put("instructions",request.getInstructions());content.put("model",properties.getModel());
        if (request.getConfirmationId() != null) content.put("confirmationId", request.getConfirmationId());
        if(options.explicit())content.put("generation",options.manifest());
        String hash=VisionImagePreparer.sha256(encode(RenderPreviewScene.canonical(json.valueToTree(content))).getBytes(StandardCharsets.UTF_8));
        RenderPreview previous=previews.byChange(projectId,request.getClientChangeId());
        if (previous!=null) {
            if (!hash.equals(previous.getContentHash())) throw new BusinessException("同一生成请求对应不同内容，请重新发起");
            return response(previous);
        }
        var canvas=office.getCanvas(projectId);
        if (canvas==null || !Objects.equals(canvas.getRevision(),request.getCanvasRevision())) throw new BusinessException("排版版本已变化，请保存当前布局后重新生成");
        JsonNode scene=RenderPreviewScene.snapshot(json.valueToTree(canvas.getObjects()),request.getZoneId());
        if (!scene.equals(expected)) throw new BusinessException("当前保存的空间或家具已变化，请重新读取后生成");
        if (request.getConfirmationId() != null) {
            scene = confirmations.requireRenderScene(projectId, request.getZoneId(), request.getConfirmationId());
            if (!scene.equals(expected)) throw new BusinessException("当前房间与已确认方案不一致，请重新确认");
        }
        RenderPreviewScene.validate(scene);
        // Concept preview is deliberately allowed before product selection. It cannot confirm a final scheme.
        var physicalContext=layoutContext.prepare(projectId,scene);
        var bindings=RenderFurnitureBindings.from(scene);
        var spatial=RenderSpatialControl.prepare(scene,bindings);
        var products=productReferences.prepare(scene,bindings);
        provider.requireConfigured();
        RenderPreview entry=new RenderPreview();
        entry.setPreviewId("RPRE-"+UUID.randomUUID()); entry.setProjectId(projectId); entry.setZoneId(request.getZoneId());
        if (request.getConfirmationId() != null) {
            entry.setPurpose("CONFIRMED_SCHEME");
            entry.setConfirmationId(request.getConfirmationId());
        }
        entry.setZoneName(scene.path("zone").path("name").asText("未命名空间")); entry.setCanvasRevision(canvas.getRevision());
        entry.setClientChangeId(request.getClientChangeId()); entry.setContentHash(hash); entry.setSnapshotJson(encode(scene));
        entry.setStyle(request.getStyle()); entry.setView("ISOMETRIC"); entry.setInstructions(request.getInstructions());
        entry.setModel(properties.getModel()); entry.setProviderBaseUrl(provider.base()); entry.setStatus("SUBMITTING");
        entry.setCreatedAt(LocalDateTime.now()); entry.setUpdatedAt(entry.getCreatedAt());
        entry.setPrompt(RenderPromptCompiler.compile(scene,entry.getStyle(),entry.getInstructions(),bindings,physicalContext,products,spatial,options));
        var planGuide=RenderPlanGuide.render(spatial,false);
        entry.setReferenceUrl(files.reference(entry.getPreviewId()+"-plan",planGuide.image()));
        var referenceImages=new ArrayList<byte[]>();
        ObjectNode storedScene=scene.deepCopy();
        ObjectNode references=storedScene.putObject("renderProductReferences");
        references.put("sourceType","spaceGuide");
        references.put("bindingVersion","furniture-product-v1");
        references.set("furnitureBindings",bindings.manifest());
        var generationGuide=RenderSceneGuide.render(scene,"ISOMETRIC",bindings,false,spatial);
        var bindingGuide=RenderSceneGuide.render(scene,"ISOMETRIC",bindings,true,spatial);
        String guideUrl=files.reference(entry.getPreviewId()+"-space-guide",generationGuide.image());
        String bindingGuideUrl=files.reference(entry.getPreviewId()+"-space-binding-guide",bindingGuide.image());
        referenceImages.add(generationGuide.image()); references.put("guideUrl",guideUrl);
        references.put("bindingGuideUrl",bindingGuideUrl);
        if(options.includePlan())referenceImages.add(planGuide.image());references.put("planGuideUrl",entry.getReferenceUrl());
        references.set("spatialControl",spatial.manifest());
        var guideLabels=references.putArray("guideLabels");
        for(var label:bindingGuide.labels()) guideLabels.addObject().put("referenceId",label.referenceId()).put("furnitureId",label.furnitureId()).put("x",label.x()).put("y",label.y());
        referenceImages.addAll(products.images());
        references.set("layoutContext",physicalContext.facts());
        references.put("version",options.concise()||options.promptExtend()?RenderGenerationOptions.EXPERIMENT_VERSION:RenderProductReferences.VERSION); references.set("generationExperiment",options.manifest()); references.putObject("generationTiming").put("requestStartedAt",requestStartedAt); references.set("items",products.manifest());
        references.set("productUnderstanding",products.understanding());
        var urls=references.putArray("sheetUrls");
        for (int i=0;i<products.images().size();i++) urls.add(files.reference(entry.getPreviewId()+"-products-"+(i+1),products.images().get(i)));
        var generationImages=references.putArray("generationImages");generationImages.addObject().put("role","CLEAN_GUIDE").put("url",guideUrl).put("sha256",VisionImagePreparer.sha256(generationGuide.image()));
        if(options.includePlan())generationImages.addObject().put("role","CLEAN_PLAN").put("url",entry.getReferenceUrl()).put("sha256",VisionImagePreparer.sha256(planGuide.image()));
        for(int i=0;i<products.images().size();i++)generationImages.addObject().put("role","PRODUCT_BOARD").put("url",urls.get(i).asText()).put("sha256",VisionImagePreparer.sha256(products.images().get(i)));
        entry.setSnapshotJson(encode(storedScene));
        previews.insert(entry);
        long submitStartedAt=System.currentTimeMillis();references.withObject("/generationTiming").put("submitStartedAt",submitStartedAt);
        try {
            JsonNode result=provider.submit(referenceImages,entry.getPrompt(),options);
            String taskId=result.path("output").path("task_id").asText();
            if (!taskId.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException("生图服务没有返回有效任务，请核对百炼请求记录后重试");
            entry.setProviderTaskId(taskId); entry.setProviderRequestId(result.path("request_id").asText());
            entry.setStatus("PENDING");references.withObject("/generationTiming").put("submittedAt",System.currentTimeMillis());
        } catch (BusinessException e) {
            entry.setStatus("FAILED"); entry.setErrorMessage(e.getMessage());references.withObject("/generationTiming").put("failedAt",System.currentTimeMillis());
        }
        entry.setSnapshotJson(encode(storedScene));
        entry.setUpdatedAt(LocalDateTime.now()); previews.update(entry);
        return response(entry);
    }

    public List<ObjectNode> list(String projectId,String zoneId) {
        project(projectId); id(zoneId,"空间 ID");
        access.requireFloorAccess(projectId);
        var entries = previews.list(projectId,zoneId);
        String confirmed = entries.stream().anyMatch(entry -> entry.getConfirmationId() != null) ? currentConfirmation(projectId) : null;
        return entries.stream().map(entry -> response(entry, confirmed)).toList();
    }

    @Transactional
    public ObjectNode get(String projectId,String previewId) {
        project(projectId); id(previewId,"预览 ID");
        access.requireFloorAccess(projectId);
        RenderPreview entry=previews.lock(projectId,previewId);
        if (entry==null) throw new BusinessException("效果预览不存在或不属于当前项目");
        if (List.of("SUBMITTING","PENDING","RUNNING").contains(entry.getStatus())) {
            if (entry.getCreatedAt().isBefore(LocalDateTime.now().minusHours(23))) {
                entry.setStatus("FAILED"); entry.setErrorMessage("任务已超过可查询时限，请重新生成");updateTiming(entry,timing->timing.put("failedAt",System.currentTimeMillis()));
            } else if (entry.getProviderTaskId()==null) {
                entry.setStatus("FAILED"); entry.setErrorMessage("提交结果未知，请核对百炼请求记录后重试");updateTiming(entry,timing->timing.put("failedAt",System.currentTimeMillis()));
            } else {
                // A network/download error leaves the task pollable; never bill a replacement automatically.
                JsonNode result=provider.poll(entry.getProviderTaskId(),entry.getProviderBaseUrl());
                JsonNode output=result.path("output"); String status=output.path("task_status").asText();
                switch (status) {
                    case "PENDING", "RUNNING" -> { entry.setStatus(status);updateTiming(entry,timing->{timing.put("lastPolledAt",System.currentTimeMillis());if("RUNNING".equals(status)&&!timing.has("firstRunningAt"))timing.put("firstRunningAt",System.currentTimeMillis());}); }
                    case "SUCCEEDED" -> { updateTiming(entry,timing->timing.put("providerCompletedAt",System.currentTimeMillis()));
                        String image=null;
                        for (JsonNode choice : output.path("choices")) for (JsonNode part : choice.path("message").path("content"))
                            if (part.hasNonNull("image") && image==null) image=part.path("image").asText();
                        if (image==null || image.isBlank()) { entry.setStatus("FAILED"); entry.setErrorMessage("生图任务已结束，但没有返回图片，请核对百炼请求记录");updateTiming(entry,timing->timing.put("failedAt",System.currentTimeMillis())); }
                        else { entry.setImageUrl(files.download(entry.getPreviewId(),image)); entry.setStatus("SUCCEEDED"); entry.setErrorMessage(null);updateTiming(entry,timing->timing.put("readyAt",System.currentTimeMillis()));auditResult(entry); }
                    }
                    case "FAILED", "CANCELED", "UNKNOWN" -> { entry.setStatus("FAILED"); entry.setErrorMessage("UNKNOWN".equals(status) ? "百炼任务不存在或已过期，请核对任务记录后重新生成" : "生图任务失败或被取消，请检查百炼任务详情、内容要求及额度后重试");updateTiming(entry,timing->timing.put("failedAt",System.currentTimeMillis())); }
                    default -> throw new BusinessException("暂时无法读取生图任务状态，请稍后查询");
                }
                if (result.hasNonNull("request_id")) entry.setProviderRequestId(result.path("request_id").asText());
            }
            entry.setUpdatedAt(LocalDateTime.now()); previews.update(entry);
        }
        return response(entry);
    }

    private void updateTiming(RenderPreview entry,java.util.function.Consumer<ObjectNode> update) {
        ObjectNode stored=(ObjectNode)decode(entry.getSnapshotJson());ObjectNode references=(ObjectNode)stored.path("renderProductReferences");if(!references.isObject())return;
        ObjectNode timing=references.withObject("/generationTiming");if(!timing.has("requestStartedAt"))timing.put("requestStartedAt",entry.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        update.accept(timing);entry.setSnapshotJson(encode(stored));
    }
    private ObjectNode presentTiming(JsonNode source) {
        ObjectNode timing=(ObjectNode)source.deepCopy();long now=System.currentTimeMillis(),start=timing.path("requestStartedAt").asLong(now);
        long ready=timing.path("readyAt").asLong(0),failed=timing.path("failedAt").asLong(0),end=ready>0?ready:failed>0?failed:now;
        timing.put("totalMs",Math.max(0,end-start));long submitStart=timing.path("submitStartedAt").asLong(0),submitted=timing.path("submittedAt").asLong(0),providerComplete=timing.path("providerCompletedAt").asLong(0);
        if(submitStart>0)timing.put("preparationMs",Math.max(0,submitStart-start));if(submitted>0&&submitStart>0)timing.put("submitMs",Math.max(0,submitted-submitStart));
        if(submitted>0)timing.put("providerMs",Math.max(0,(providerComplete>0?providerComplete:end)-submitted));if(ready>0&&providerComplete>0)timing.put("downloadMs",Math.max(0,ready-providerComplete));return timing;
    }
    private ObjectNode response(RenderPreview entry) {
        return response(entry, entry.getConfirmationId() == null ? null : currentConfirmation(entry.getProjectId()));
    }

    private String currentConfirmation(String floorId) {
        var confirmation = confirmations.latest(floorId);
        return confirmation != null && confirmation.current() ? confirmation.confirmationId() : null;
    }

    private ObjectNode response(RenderPreview entry, String currentConfirmationId) {
        ObjectNode result=json.valueToTree(entry);
        ObjectNode snapshot=(ObjectNode)decode(entry.getSnapshotJson());
        JsonNode references=snapshot.remove("renderProductReferences");
        result.set("snapshot",snapshot);
        if (references!=null) { result.set("productReferences",references); if(references.has("layoutContext")) result.set("layoutContext",references.get("layoutContext")); if(references.hasNonNull("guideUrl")) result.put("layoutGuideUrl",references.path("guideUrl").asText());
            result.put("sourceType",references.path("sourceType").asText("spaceGuide")); if(references.hasNonNull("viewUrl")) result.put("viewImageUrl",references.path("viewUrl").asText());
            if(references.hasNonNull("planGuideUrl")) result.put("planGuideUrl",references.path("planGuideUrl").asText());
            if(references.has("qualityAudit")) result.set("qualityAudit",references.get("qualityAudit"));
            if(references.has("geometryAudit")) result.set("geometryAudit",references.get("geometryAudit")); if(references.has("generationTiming")) result.set("generationTiming",presentTiming(references.get("generationTiming"))); if(references.has("generationExperiment")&&(references.path("generationExperiment").path("promptMode").asText().equals("CONCISE_V11")||references.path("generationExperiment").path("promptExtend").asBoolean()||!references.path("generationExperiment").path("seed").isNull()||!references.path("generationExperiment").path("sampleIndex").isNull())) result.set("generationExperiment",references.get("generationExperiment"));
            if(references.has("productUnderstanding")) { result.set("productUnderstanding",references.get("productUnderstanding")); result.put("agentStatus",references.path("productUnderstanding").path("status").asText("UNAVAILABLE")); } }
        result.put("productImagesUsed", references!=null && !references.path("items").isEmpty());
        boolean stale=true;
        try {
            var canvas=office.getCanvas(entry.getProjectId());
            stale=canvas==null || !snapshot.equals(RenderPreviewScene.snapshot(json.valueToTree(canvas.getObjects()),entry.getZoneId()));
        } catch (BusinessException ignored) { /* A deleted/unavailable room makes this a historical result. */ }
        result.put("stale",stale || entry.getConfirmationId() != null && !entry.getConfirmationId().equals(currentConfirmationId));
        return result;
    }

    private void auditResult(RenderPreview entry) {
        ObjectNode stored=(ObjectNode)decode(entry.getSnapshotJson());
        ObjectNode references=(ObjectNode)stored.path("renderProductReferences");
        if(!references.isObject() || !"furniture-product-v1".equals(references.path("bindingVersion").asText())) return;
        if(references.has("qualityAudit") && (references.has("geometryAudit") || !references.has("spatialControl"))) return;
        var bindings=RenderFurnitureBindings.from(stored);
        if(!references.has("qualityAudit")) try {
            List<byte[]> boards=new ArrayList<>();for(JsonNode url:references.path("sheetUrls")) boards.add(files.read(url.asText()));
            ObjectNode audit=productAudit.audit(files.read(entry.getImageUrl()),files.read(references.path("bindingGuideUrl").asText(references.path("guideUrl").asText())),boards,bindings,(ArrayNode)references.path("items"),references.path("layoutContext"));
            references.set("qualityAudit",audit);
        } catch(BusinessException e) { references.set("qualityAudit",productAudit.unavailable()); }
        if(!references.has("geometryAudit")&&references.has("spatialControl")) try {
            var spatial=RenderSpatialControl.prepare(stored,bindings);
            references.set("geometryAudit",geometryAudit.audit(files.read(entry.getImageUrl()),files.read(references.path("guideUrl").asText(references.path("bindingGuideUrl").asText())),files.read(references.path("planGuideUrl").asText()),spatial));
        } catch(BusinessException e) { references.set("geometryAudit",geometryAudit.unavailable()); }
        entry.setSnapshotJson(encode(stored));
        previews.updateSnapshot(entry);
    }

    private void validateRequest(RenderPreviewRequest request) {
        if (request==null) throw new BusinessException("请提供当前空间和生成参数");
        id(request.getZoneId(),"空间 ID"); id(request.getClientChangeId(),"生成请求 ID");
        if (request.getConfirmationId() != null) id(request.getConfirmationId(), "方案确认 ID");
        if (request.getCanvasRevision()==null || request.getCanvasRevision()<1 || request.getExpectedSnapshot()==null || !request.getExpectedSnapshot().isObject()
            || encode(request.getExpectedSnapshot()).length()>1_000_000) throw new BusinessException("排版快照或版本无效");
        if (request.getStyle()==null || request.getStyle().isBlank() || request.getStyle().length()>100) throw new BusinessException("请填写 1 至 100 字的效果图风格");
        if (!"ISOMETRIC".equals(request.getView())) throw new BusinessException("AI效果图统一使用45度斜俯视视角");
        if (request.getInstructions()==null) request.setInstructions("");
        if (request.getInstructions().length()>500) throw new BusinessException("补充偏好最多 500 字");
        if (request.getViewImage()!=null && !request.getViewImage().isBlank()) throw new BusinessException("AI效果图已统一使用带家具定位标识的45度空间结构参考，请重新打开发起");
    }
    private void project(String value) { id(value,"项目 ID"); }
    private void id(String value,String label) { if (value==null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException(label+"无效"); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new BusinessException("效果预览快照无法保存"); } }
    private JsonNode decode(String value) { try { return RenderPreviewScene.canonical(json.readTree(value)); } catch (Exception e) { throw new BusinessException("效果预览快照无法读取"); } }
}
