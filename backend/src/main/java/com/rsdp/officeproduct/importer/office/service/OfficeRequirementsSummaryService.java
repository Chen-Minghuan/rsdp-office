package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RequirementsSummaryProperties;
import com.rsdp.officeproduct.importer.office.dto.OfficeRequirementsSummaryDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.service.RequirementsSummaryClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class OfficeRequirementsSummaryService {
    private static final String PROMPT_VERSION = "requirements-facts-v1";

    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeProjectAccessService accessService;
    private final RequirementsSummaryClient summaryClient;
    private final RequirementsSummaryProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<OfficeRequirementsSummaryDto>> inFlight = new ConcurrentHashMap<>();

    public OfficeRequirementsSummaryDto ensureSummary(String projectId, int requirementsRevision) {
        accessService.requireMember(projectId);
        OfficeDesignProject project = requireProject(projectId);
        requireRevision(project, requirementsRevision);
        String sourceText = project.getCustomerRequirements();
        if (!StringUtils.hasText(sourceText)) {
            throw new BusinessException("请先填写项目需求，再进行AI总结");
        }
        if (sourceText.length() > 4000) {
            throw new BusinessException("项目需求不能超过4000字，请精简后再总结");
        }
        OfficeRequirementsSummaryDto cached = readCached(project);
        if (cached != null) return cached;

        String key = projectId + ':' + requirementsRevision + ':' + PROMPT_VERSION + ':' + properties.getModel();
        CompletableFuture<OfficeRequirementsSummaryDto> task = new CompletableFuture<>();
        CompletableFuture<OfficeRequirementsSummaryDto> existing = inFlight.putIfAbsent(key, task);
        if (existing != null) return await(existing);
        try {
            OfficeRequirementsSummaryDto generated = generateAndSave(project, sourceText, requirementsRevision);
            task.complete(generated);
            return generated;
        } catch (Throwable failure) {
            task.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, task);
        }
    }

    private OfficeRequirementsSummaryDto generateAndSave(OfficeDesignProject project, String sourceText,
                                                          int requirementsRevision) {
        List<String> keyPoints = summaryClient.summarize(sourceText);
        String summaryJson = writeSummary(keyPoints);
        int updated = projectMapper.saveRequirementsSummary(project.getProjectId(), requirementsRevision,
            summaryJson, properties.getModel(), PROMPT_VERSION, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException("项目需求已修改，请使用最新原文重新总结");
        }
        return response(keyPoints, requirementsRevision, false);
    }

    private OfficeRequirementsSummaryDto readCached(OfficeDesignProject project) {
        if (!Integer.valueOf(project.getRequirementsRevision()).equals(project.getRequirementsSummaryRevision())
            || !PROMPT_VERSION.equals(project.getRequirementsSummaryPromptVersion())
            || !properties.getModel().equals(project.getRequirementsSummaryModel())
            || !StringUtils.hasText(project.getRequirementsSummary())) {
            return null;
        }
        try {
            var root = objectMapper.readTree(project.getRequirementsSummary());
            List<String> keyPoints = objectMapper.readValue(root.path("keyPoints").toString(),
                objectMapper.getTypeFactory().constructParametricType(List.class, String.class));
            if (keyPoints.isEmpty() || keyPoints.stream().anyMatch(point -> !StringUtils.hasText(point))) return null;
            return response(keyPoints, project.getRequirementsRevision(), true);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeSummary(List<String> keyPoints) {
        try {
            return objectMapper.writeValueAsString(Map.of("keyPoints", keyPoints));
        } catch (Exception e) {
            throw new BusinessException("项目需求总结无法保存", e);
        }
    }

    private OfficeRequirementsSummaryDto response(List<String> keyPoints, int sourceRevision, boolean cacheHit) {
        OfficeRequirementsSummaryDto response = new OfficeRequirementsSummaryDto();
        response.setKeyPoints(keyPoints);
        response.setSourceRevision(sourceRevision);
        response.setCacheHit(cacheHit);
        return response;
    }

    private OfficeRequirementsSummaryDto await(CompletableFuture<OfficeRequirementsSummaryDto> task) {
        try {
            return task.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof BusinessException businessException) throw businessException;
            throw e;
        }
    }

    private void requireRevision(OfficeDesignProject project, int requirementsRevision) {
        if (project.getRequirementsRevision() != requirementsRevision) {
            throw new BusinessException("项目需求已修改，请使用最新原文重新总结");
        }
    }

    private OfficeDesignProject requireProject(String projectId) {
        OfficeDesignProject project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
        if (project == null) throw new BusinessException("项目不存在");
        return project;
    }
}
