package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.service.ProjectQuotationService;
import com.rsdp.officeproduct.importer.office.service.ProjectQuotationWorkbookBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 项目报价：经销商主号专属，服务端逐接口校验身份。 */
@RestController
@RequestMapping("/api/v1/office/projects/{projectId}/quotation")
@RequiredArgsConstructor
public class OfficeQuotationController {

    private final ProjectQuotationService quotationService;
    private final ProjectQuotationWorkbookBuilder workbookBuilder;

    /**
     * 项目报价试算（只读，不落库）。
     *
     * @param mode  COEFFICIENT=出厂价×系数；MARGIN=出厂价÷(1−毛利率)
     * @param value 系数倍数，或毛利率小数（0.35 表示 35%）
     */
    @GetMapping("/preview")
    public Result<ProjectQuotationPreviewDto> preview(@PathVariable String projectId,
                                                      @RequestParam(defaultValue = "COEFFICIENT") String mode,
                                                      @RequestParam(required = false) BigDecimal value) {
        return Result.ok(quotationService.preview(projectId, mode, value));
    }

    /** 确认报价：落库持久化，服务端重算金额；同一项目只保留一条有效报价。 */
    @PostMapping("/confirm")
    public Result<ProjectQuotationRecordDto> confirm(@PathVariable String projectId,
                                                     @RequestBody ConfirmProjectQuotationRequest request) {
        return Result.ok(quotationService.confirm(projectId, request));
    }

    /** 导出当前编辑稿：与确认共用重算，但不落库、不生成报价版本。 */
    @PostMapping("/export")
    public void export(@PathVariable String projectId,
                       @RequestBody ConfirmProjectQuotationRequest request,
                       HttpServletResponse response) throws IOException {
        ProjectQuotationRecordDto draft = quotationService.previewConfirmation(projectId, request);
        writeWorkbook(draft, workbookBuilder.build(draft), response);
    }

    @GetMapping("/latest/export")
    public void exportLatest(@PathVariable String projectId, HttpServletResponse response) throws IOException {
        ProjectQuotationRecordDto record = quotationService.latestForExport(projectId);
        writeWorkbook(record, workbookBuilder.build(record, record.getPricingMode() != null), response);
    }

    private void writeWorkbook(ProjectQuotationRecordDto record, byte[] workbook, HttpServletResponse response) throws IOException {
        String fileName = URLEncoder.encode(record.getProjectName() + "-报价单.xlsx", StandardCharsets.UTF_8)
            .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"quotation.xlsx\"; filename*=UTF-8''" + fileName);
        response.setContentLength(workbook.length);
        response.getOutputStream().write(workbook);
    }

    /** 最近一次确认的报价单，用于页面回显；没有确认记录时 data 为空。 */
    @GetMapping("/latest")
    public Result<ProjectQuotationRecordDto> latest(@PathVariable String projectId) {
        return Result.ok(quotationService.latest(projectId));
    }
}
