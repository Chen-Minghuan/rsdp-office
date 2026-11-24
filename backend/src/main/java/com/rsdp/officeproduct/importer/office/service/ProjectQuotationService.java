package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationLineMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 项目报价的权限、流程和确认事务入口。 */
@Service
@RequiredArgsConstructor
public class ProjectQuotationService {

    private enum ConfirmationScope { PROJECT, FLOOR }

    private final OfficeProjectAccessService accessService;
    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeQuotationMapper quotationMapper;
    private final OfficeQuotationLineMapper quotationLineMapper;
    private final OfficeAuditService auditService;
    private final ProjectQuotationPreviewBuilder previewBuilder;
    private final ProjectQuotationDraftBuilder draftBuilder;
    private final QuotationRecordAssembler recordAssembler;

    public ProjectQuotationPreviewDto preview(String projectId, String mode, BigDecimal value) {
        OfficeDesignProject project = requireProjectAccess(projectId);
        return previewBuilder.build(project, QuotationPricing.of(mode, value));
    }

    /** 确认报价后保留历史版本，并在同一事务内更新当前版本和审计。 */
    @Transactional
    public ProjectQuotationRecordDto confirm(String projectId, ConfirmProjectQuotationRequest request) {
        OfficeDesignProject project = requireProjectAccess(projectId);
        ConfirmationScope scope = confirmationScope(request);
        if (scope == ConfirmationScope.FLOOR) {
            requireFloorScopedLines(request);
        }
        ProjectQuotationDraftBuilder.Draft draft = draftBuilder.build(project, request);

        // 锁定项目后再分配版本，首次确认也能串行写入。
        if (projectMapper.selectOne(new QueryWrapper<OfficeDesignProject>().select("project_id")
                .eq("project_id", projectId).last("FOR UPDATE")) == null) {
            throw new BusinessException("项目不存在");
        }
        OfficeQuotation previous = findLatest(projectId);
        ConfirmationDraft confirmation = scope == ConfirmationScope.FLOOR
            ? mergeFloorConfirmation(draft, previous, request.getFloorId())
            : new ConfirmationDraft(draft.quotation(), draft.rows(), draft.fees());
        OfficeQuotation quotation = confirmation.quotation();
        quotation.setFeesJson(recordAssembler.writeFees(confirmation.fees()));
        int revision = previous == null || previous.getRevision() == null ? 1 : previous.getRevision() + 1;
        String quotationId = "QT-" + UUID.randomUUID().toString().replace("-", "");
        quotation.setQuotationId(quotationId);
        quotation.setRevision(revision);
        quotation.setStatus("CONFIRMED");
        quotation.setCreatedBy(accessService.currentUserId());
        quotation.setCreatedAt(LocalDateTime.now());

        OfficeQuotation superseded = new OfficeQuotation();
        superseded.setStatus("SUPERSEDED");
        superseded.setSupersededAt(LocalDateTime.now());
        quotationMapper.update(superseded, new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).eq("status", "CONFIRMED"));

        quotationMapper.insert(quotation);
        int lineNo = 0;
        for (OfficeQuotationLine row : confirmation.rows()) {
            row.setQuotationId(quotationId);
            row.setLineNo(lineNo++);
            quotationLineMapper.insert(row);
        }
        ProjectQuotationRecordDto record = recordAssembler.assemble(quotation, confirmation.rows(), confirmation.fees());
        auditService.record("QUOTATION_CONFIRMED", "PROJECT", projectId,
            "quotationId=" + quotationId + "; revision=" + revision + "; total=" + quotation.getTotalAmount()
                + "; lines=" + record.getLineCount() + "; missing=" + record.getMissingPriceLines()
                + "; scope=" + scope);
        return record;
    }

    /** 导出前的试算，与确认共用重算逻辑。 */
    public ProjectQuotationRecordDto previewConfirmation(String projectId, ConfirmProjectQuotationRequest request) {
        ProjectQuotationDraftBuilder.Draft draft = draftBuilder.build(requireProjectAccess(projectId), request);
        return recordAssembler.assemble(draft.quotation(), draft.rows(), draft.fees());
    }

    public ProjectQuotationRecordDto latest(String projectId) {
        requireProjectAccess(projectId);
        OfficeQuotation quotation = findLatest(projectId);
        if (quotation == null) {
            return null;
        }
        List<OfficeQuotationLine> rows = quotationLineMapper.findByQuotationId(quotation.getQuotationId());
        return recordAssembler.assemble(quotation, rows, recordAssembler.readFees(quotation.getFeesJson()));
    }

    private OfficeQuotation findLatest(String projectId) {
        return quotationMapper.selectOne(new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).orderByDesc("revision").last("limit 1"));
    }

    private ConfirmationScope confirmationScope(ConfirmProjectQuotationRequest request) {
        String rawScope = request == null ? null : request.getConfirmationScope();
        if (!StringUtils.hasText(rawScope) || ConfirmationScope.PROJECT.name().equalsIgnoreCase(rawScope)) {
            return ConfirmationScope.PROJECT;
        }
        if (ConfirmationScope.FLOOR.name().equalsIgnoreCase(rawScope)) {
            return ConfirmationScope.FLOOR;
        }
        throw new BusinessException("确认范围仅支持 PROJECT 或 FLOOR");
    }

    private void requireFloorScopedLines(ConfirmProjectQuotationRequest request) {
        String floorId = request.getFloorId();
        if (!StringUtils.hasText(floorId)) {
            throw new BusinessException("楼层确认缺少 floorId");
        }
        if (request.getLines() == null || request.getLines().isEmpty()) {
            return;
        }
        for (ConfirmProjectQuotationRequest.Line line : request.getLines()) {
            if (line == null || !floorId.equals(line.getFloorId())) {
                throw new BusinessException("楼层确认明细必须全部属于当前楼层");
            }
        }
    }

    /**
     * 楼层确认创建新的项目报价版本：当前楼层按最新商品目录核验和重建，其他楼层直接复制已确认快照。
     * 这样其他楼层的停售商品、历史价格和展示信息不会被本次确认重新校验或静默改写。
     */
    private ConfirmationDraft mergeFloorConfirmation(ProjectQuotationDraftBuilder.Draft draft, OfficeQuotation previous,
                                                      String floorId) {
        if (previous == null) {
            return new ConfirmationDraft(draft.quotation(), draft.rows(), draft.fees());
        }

        List<OfficeQuotationLine> rows = new ArrayList<>();
        for (OfficeQuotationLine previousRow : quotationLineMapper.findByQuotationId(previous.getQuotationId())) {
            if (!floorId.equals(previousRow.getFloorId())) {
                rows.add(copyLine(previousRow));
            }
        }
        for (OfficeQuotationLine currentRow : draft.rows()) {
            rows.add(copyLine(currentRow));
        }

        List<QuotationFeeDto> fees = previousFees(previous);
        OfficeQuotation quotation = draft.quotation();
        quotation.setPricingMode(previous.getPricingMode());
        quotation.setPricingValue(previous.getPricingValue());
        quotation.setTotalAmount(totalAmount(rows, fees));
        return new ConfirmationDraft(quotation, rows, fees);
    }

    private List<QuotationFeeDto> previousFees(OfficeQuotation previous) {
        return StringUtils.hasText(previous.getFeesJson())
            ? recordAssembler.readFees(previous.getFeesJson())
            : new ArrayList<>();
    }

    private BigDecimal totalAmount(List<OfficeQuotationLine> rows, List<QuotationFeeDto> fees) {
        BigDecimal goodsTotal = rows.stream()
            .filter(row -> row.getUnitPrice() != null)
            .map(row -> QuotationPricing.subtotal(row.getUnitPrice(), row.getQty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeTotal = fees.stream()
            .map(QuotationFeeDto::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return QuotationPricing.money(goodsTotal.add(feeTotal));
    }

    private OfficeQuotationLine copyLine(OfficeQuotationLine source) {
        OfficeQuotationLine copy = new OfficeQuotationLine();
        copy.setFloorId(source.getFloorId());
        copy.setFloorName(source.getFloorName());
        copy.setZoneId(source.getZoneId());
        copy.setZoneName(source.getZoneName());
        copy.setProductId(source.getProductId());
        copy.setRspuId(source.getRspuId());
        copy.setProductName(source.getProductName());
        copy.setVariant(source.getVariant());
        copy.setImageUrl(source.getImageUrl());
        copy.setQty(source.getQty());
        copy.setFactoryPrice(source.getFactoryPrice());
        copy.setUnitPrice(source.getUnitPrice());
        copy.setIssue(source.getIssue());
        return copy;
    }

    private record ConfirmationDraft(OfficeQuotation quotation, List<OfficeQuotationLine> rows,
                                     List<QuotationFeeDto> fees) {
    }

    private OfficeDesignProject requireProjectAccess(String projectId) {
        OfficeDesignProject project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        accessService.requireWorkspaceOwner(projectId);
        return project;
    }
}
