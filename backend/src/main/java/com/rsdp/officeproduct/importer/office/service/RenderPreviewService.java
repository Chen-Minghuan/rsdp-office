package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final RenderLayoutContext layoutContext;
    private final OfficeWorkspaceLockMapper workspaceLocks;
    private final RenderPreviewMapper previews;
    private final QwenImageClient provider;
    private final RenderPreviewFiles files;
    private final RenderProperties properties;
    private final ObjectMapper json;

    @Transactional
    public ObjectNode create(String projectId, RenderPreviewRequest request) {
        project(projectId);
        validateRequest(request);
        workspaceLocks.lockWorkspace(projectId);
        JsonNode expected=RenderPreviewScene.canonical(request.getExpectedSnapshot());
        String hash=VisionImagePreparer.sha256(encode(RenderPreviewScene.canonical(json.valueToTree(Map.of("snapshot",expected,"style",request.getStyle(),"view",request.getView(),
            "instructions",request.getInstructions(),"model",properties.getModel())))).getBytes(StandardCharsets.UTF_8));
        RenderPreview previous=previews.byChange(projectId,request.getClientChangeId());
        if (previous!=null) {
            if (!hash.equals(previous.getContentHash())) throw new BusinessException("同一生成请求对应不同内容，请重新发起");
            return response(previous);
        }
        var canvas=office.getCanvas(projectId);
        if (canvas==null || !Objects.equals(canvas.getRevision(),request.getCanvasRevision())) throw new BusinessException("排版版本已变化，请保存当前布局后重新生成");
        JsonNode scene=RenderPreviewScene.snapshot(json.valueToTree(canvas.getObjects()),request.getZoneId());
        if (!scene.equals(expected)) throw new BusinessException("当前保存的空间或家具已变化，请重新读取后生成");
        RenderPreviewScene.validate(scene);
        // Concept preview is deliberately allowed before product selection. It cannot confirm a final scheme.
        var physicalContext=layoutContext.prepare(projectId,scene);
        var products=productReferences.prepare(scene);
        provider.requireConfigured();
        RenderPreview entry=new RenderPreview();
        entry.setPreviewId("RPRE-"+UUID.randomUUID()); entry.setProjectId(projectId); entry.setZoneId(request.getZoneId());
        entry.setZoneName(scene.path("zone").path("name").asText("未命名空间")); entry.setCanvasRevision(canvas.getRevision());
        entry.setClientChangeId(request.getClientChangeId()); entry.setContentHash(hash); entry.setSnapshotJson(encode(scene));
        entry.setStyle(request.getStyle()); entry.setView(request.getView()); entry.setInstructions(request.getInstructions());
        entry.setModel(properties.getModel()); entry.setProviderBaseUrl(provider.base()); entry.setStatus("SUBMITTING");
        entry.setCreatedAt(LocalDateTime.now()); entry.setUpdatedAt(entry.getCreatedAt());
        entry.setPrompt(RenderPreviewScene.prompt(scene,entry.getStyle(),entry.getView(),entry.getInstructions()) + physicalContext.prompt() + products.prompt());
        byte[] reference=RenderPreviewScene.referencePng(scene);
        entry.setReferenceUrl(files.reference(entry.getPreviewId(),reference));
        byte[] guide=RenderSceneGuide.png(scene,entry.getView());
        var referenceImages=new ArrayList<byte[]>(); referenceImages.add(guide); referenceImages.addAll(products.images());
        ObjectNode storedScene=scene.deepCopy();
        ObjectNode references=storedScene.putObject("renderProductReferences");
        references.set("layoutContext",physicalContext.facts());
        references.put("guideUrl",files.reference(entry.getPreviewId()+"-space-guide",guide));
        references.put("version",RenderProductReferences.VERSION); references.set("items",products.manifest());
        var urls=references.putArray("sheetUrls");
        for (int i=0;i<products.images().size();i++) urls.add(files.reference(entry.getPreviewId()+"-products-"+(i+1),products.images().get(i)));
        entry.setSnapshotJson(encode(storedScene));
        previews.insert(entry);
        try {
            JsonNode result=provider.submit(referenceImages,entry.getPrompt());
            String taskId=result.path("output").path("task_id").asText();
            if (!taskId.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException("生图服务没有返回有效任务，请核对百炼请求记录后重试");
            entry.setProviderTaskId(taskId); entry.setProviderRequestId(result.path("request_id").asText());
            entry.setStatus("PENDING");
        } catch (BusinessException e) {
            entry.setStatus("FAILED"); entry.setErrorMessage(e.getMessage());
        }
        entry.setUpdatedAt(LocalDateTime.now()); previews.update(entry);
        return response(entry);
    }

    public List<ObjectNode> list(String projectId,String zoneId) {
        project(projectId); id(zoneId,"空间 ID");
        return previews.list(projectId,zoneId).stream().map(this::response).toList();
    }

    @Transactional
    public ObjectNode get(String projectId,String previewId) {
        project(projectId); id(previewId,"预览 ID");
        RenderPreview entry=previews.lock(projectId,previewId);
        if (entry==null) throw new BusinessException("效果预览不存在或不属于当前项目");
        if (List.of("SUBMITTING","PENDING","RUNNING").contains(entry.getStatus())) {
            if (entry.getCreatedAt().isBefore(LocalDateTime.now().minusHours(23))) {
                entry.setStatus("FAILED"); entry.setErrorMessage("任务已超过可查询时限，请重新生成");
            } else if (entry.getProviderTaskId()==null) {
                entry.setStatus("FAILED"); entry.setErrorMessage("提交结果未知，请核对百炼请求记录后重试");
            } else {
                // A network/download error leaves the task pollable; never bill a replacement automatically.
                JsonNode result=provider.poll(entry.getProviderTaskId(),entry.getProviderBaseUrl());
                JsonNode output=result.path("output"); String status=output.path("task_status").asText();
                switch (status) {
                    case "PENDING", "RUNNING" -> entry.setStatus(status);
                    case "SUCCEEDED" -> {
                        String image=null;
                        for (JsonNode choice : output.path("choices")) for (JsonNode part : choice.path("message").path("content"))
                            if (part.hasNonNull("image") && image==null) image=part.path("image").asText();
                        if (image==null || image.isBlank()) { entry.setStatus("FAILED"); entry.setErrorMessage("生图任务已结束，但没有返回图片，请核对百炼请求记录"); }
                        else { entry.setImageUrl(files.download(entry.getPreviewId(),image)); entry.setStatus("SUCCEEDED"); entry.setErrorMessage(null); }
                    }
                    case "FAILED", "CANCELED", "UNKNOWN" -> { entry.setStatus("FAILED"); entry.setErrorMessage("UNKNOWN".equals(status) ? "百炼任务不存在或已过期，请核对任务记录后重新生成" : "生图任务失败或被取消，请检查百炼任务详情、内容要求及额度后重试"); }
                    default -> throw new BusinessException("暂时无法读取生图任务状态，请稍后查询");
                }
                if (result.hasNonNull("request_id")) entry.setProviderRequestId(result.path("request_id").asText());
            }
            entry.setUpdatedAt(LocalDateTime.now()); previews.update(entry);
        }
        return response(entry);
    }

    private ObjectNode response(RenderPreview entry) {
        ObjectNode result=json.valueToTree(entry);
        ObjectNode snapshot=(ObjectNode)decode(entry.getSnapshotJson());
        JsonNode references=snapshot.remove("renderProductReferences");
        result.set("snapshot",snapshot);
        if (references!=null) { result.set("productReferences",references); if(references.has("layoutContext")) result.set("layoutContext",references.get("layoutContext")); if(references.hasNonNull("guideUrl")) result.put("layoutGuideUrl",references.path("guideUrl").asText()); }
        result.put("productImagesUsed", references!=null && !references.path("items").isEmpty());
        boolean stale=true;
        try {
            var canvas=office.getCanvas(entry.getProjectId());
            stale=canvas==null || !snapshot.equals(RenderPreviewScene.snapshot(json.valueToTree(canvas.getObjects()),entry.getZoneId()));
        } catch (BusinessException ignored) { /* A deleted/unavailable room makes this a historical result. */ }
        result.put("stale",stale);
        return result;
    }

    private void validateRequest(RenderPreviewRequest request) {
        if (request==null) throw new BusinessException("请提供当前空间和生成参数");
        id(request.getZoneId(),"空间 ID"); id(request.getClientChangeId(),"生成请求 ID");
        if (request.getCanvasRevision()==null || request.getCanvasRevision()<1 || request.getExpectedSnapshot()==null || !request.getExpectedSnapshot().isObject()
            || encode(request.getExpectedSnapshot()).length()>1_000_000) throw new BusinessException("排版快照或版本无效");
        if (request.getStyle()==null || request.getStyle().isBlank() || request.getStyle().length()>100) throw new BusinessException("请填写 1 至 100 字的效果图风格");
        if (!List.of("ISOMETRIC","INTERIOR").contains(request.getView()==null ? "" : request.getView())) throw new BusinessException("请选择鸟瞰或室内透视视角");
        if (request.getInstructions()==null) request.setInstructions("");
        if (request.getInstructions().length()>500) throw new BusinessException("补充偏好最多 500 字");
    }
    private void project(String value) { id(value,"项目 ID"); }
    private void id(String value,String label) { if (value==null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException(label+"无效"); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new BusinessException("效果预览快照无法保存"); } }
    private JsonNode decode(String value) { try { return RenderPreviewScene.canonical(json.readTree(value)); } catch (Exception e) { throw new BusinessException("效果预览快照无法读取"); } }
}
