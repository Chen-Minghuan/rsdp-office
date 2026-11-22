package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.GenerateOfficeRequirementsSummaryRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeRequirementsSummaryDto;
import com.rsdp.officeproduct.importer.office.service.OfficeRequirementsSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/office/projects")
@RequiredArgsConstructor
public class OfficeRequirementsSummaryController {
    private final OfficeRequirementsSummaryService summaryService;

    @PostMapping("/{projectId}/requirements/summary")
    public Result<OfficeRequirementsSummaryDto> summarize(@PathVariable String projectId,
                                                           @RequestBody GenerateOfficeRequirementsSummaryRequest request) {
        if (request == null || request.getRequirementsRevision() == null) {
            throw new BusinessException("项目需求版本不能为空");
        }
        return Result.ok(summaryService.ensureSummary(projectId, request.getRequirementsRevision()));
    }
}
