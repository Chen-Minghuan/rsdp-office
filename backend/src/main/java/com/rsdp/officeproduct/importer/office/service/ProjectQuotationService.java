package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationLatestDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationLineMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 项目报价的权限、流程和确认事务入口。 */
@Service
@RequiredArgsConstructor
public class ProjectQuotationService {

    private final OfficeProjectAccessService accessService;
    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeQuotationMapper quotationMapper;
    private final OfficeQuotationLineMapper quotationLineMapper;
    private final OfficeAuditService auditService;
    private final ProjectQuotationPreviewBuilder previewBuilder;
    private final ProjectQuotationDraftBuilder draftBuilder;
    private final QuotationRecordAssembler recordAssembler;
    private final OfficeProposalConfirmationService proposalConfirmations;
    private final OfficeDesignFloorMapper floorMapper;
    private final OfficeWorkspaceLockMapper floorLocks;

    @Transactional
    public ProjectQuotationPreviewDto preview(String projectId, String mode, BigDecimal value) {
        OfficeDesignProject project = requireProjectAccess(projectId);
        // 与画布/清单编辑使用相同的事务锁，试算明细与其确认 ID 来自同一状态。
        lockProjectFloors(projectId);
        var preview = previewBuilder.build(project, QuotationPricing.of(mode, value));
        Map<String, String> sources = new LinkedHashMap<>();
        for (var floor : preview.getFloors()) {
            var status = proposalConfirmations.latest(floor.getFloorId());
            if (status != null && status.current()) sources.put(floor.getFloorId(), status.confirmationId());
        }
        preview.setProposalConfirmationIds(sources);
        return preview;
    }

    /** 确认报价后保留历史版本，并在同一事务内更新当前版本和审计。 */
    @Transactional
    public ProjectQuotationRecordDto confirm(String projectId, ConfirmProjectQuotationRequest request) {
        OfficeDesignProject project = requireProjectAccess(projectId);
        lockProjectFloors(projectId);
        ProjectQuotationDraftBuilder.Draft draft = draftBuilder.build(project, request);
        Map<String, String> sources = requireConfirmedSchemes(draft, request);
        OfficeQuotation quotation = draft.quotation();
        quotation.setFeesJson(recordAssembler.writeFees(draft.fees()));
        quotation.setProposalConfirmationsJson(recordAssembler.writeProposalSources(sources));

        // 锁定项目后再分配版本，首次确认也能串行写入。
        if (projectMapper.selectOne(new QueryWrapper<OfficeDesignProject>().select("project_id")
                .eq("project_id", projectId).last("FOR UPDATE")) == null) {
            throw new BusinessException("项目不存在");
        }
        OfficeQuotation previous = findLatest(projectId, false);
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
        for (OfficeQuotationLine row : draft.rows()) {
            row.setQuotationId(quotationId);
            quotationLineMapper.insert(row);
        }
        ProjectQuotationRecordDto record = recordAssembler.assemble(quotation, draft.rows(), draft.fees());
        auditService.record("QUOTATION_CONFIRMED", "PROJECT", projectId,
            "quotationId=" + quotationId + "; revision=" + revision + "; total=" + quotation.getTotalAmount()
                + "; lines=" + record.getLineCount() + "; missing=" + record.getMissingPriceLines());
        return record;
    }

    /** 导出前的试算，与确认共用重算逻辑。 */
    public ProjectQuotationRecordDto previewConfirmation(String projectId, ConfirmProjectQuotationRequest request) {
        ProjectQuotationDraftBuilder.Draft draft = draftBuilder.build(requireProjectAccess(projectId), request);
        return recordAssembler.assemble(draft.quotation(), draft.rows(), draft.fees());
    }

    public ProjectQuotationRecordDto latest(String projectId) {
        accessService.requirePermission(projectId, OfficeProjectPermission.QUOTE_READ);
        OfficeQuotation quotation = findLatest(projectId, true);
        if (quotation == null) {
            return null;
        }
        List<OfficeQuotationLine> rows = quotationLineMapper.findByQuotationId(quotation.getQuotationId());
        ProjectQuotationRecordDto record = recordAssembler.assemble(quotation, rows, recordAssembler.readFees(quotation.getFeesJson()));
        if (!accessService.can(projectId, OfficeProjectPermission.MARGIN_READ)) {
            record.setPricingMode(null);
            record.setPricingValue(null);
            record.getLines().forEach(line -> {
                line.setFactoryPrice(null);
                line.setIssue(null);
            });
            record.setMissingPriceLines(null);
        }
        return record;
    }

    /** 最近一条报价记录（任意状态）的时间信息；没有报价记录时返回 null。 */
    public ProjectQuotationLatestDto latestAny(String projectId) {
        accessService.requirePermission(projectId, OfficeProjectPermission.QUOTE_READ);
        OfficeQuotation quotation = findLatest(projectId, false);
        if (quotation == null) {
            return null;
        }
        ProjectQuotationLatestDto latest = new ProjectQuotationLatestDto();
        latest.setQuotationId(quotation.getQuotationId());
        latest.setStatus(quotation.getStatus());
        latest.setRevision(quotation.getRevision());
        latest.setCreatedAt(quotation.getCreatedAt());
        return latest;
    }

    public ProjectQuotationRecordDto latestForExport(String projectId) {
        accessService.requirePermission(projectId, OfficeProjectPermission.EXPORT_QUOTE);
        ProjectQuotationRecordDto record = latest(projectId);
        if (record == null) throw new BusinessException("项目暂无已确认报价");
        return record;
    }

    /** 报价行涉及的每个楼层都必须持有当前有效的方案确认快照；缺失或已失效即拒绝确认报价。预览/试算不调用此门禁。 */
    private Map<String, String> requireConfirmedSchemes(ProjectQuotationDraftBuilder.Draft draft, ConfirmProjectQuotationRequest request) {
        Map<String, String> floors = new LinkedHashMap<>();
        for (OfficeQuotationLine row : draft.rows()) {
            floors.putIfAbsent(row.getFloorId(), row.getFloorName());
        }
        List<String> unconfirmed = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, String> floor : floors.entrySet()) {
            OfficeProposalConfirmationService.Status status = proposalConfirmations.latest(floor.getKey());
            if (status == null || !status.current()) {
                unconfirmed.add(floor.getValue());
            } else if (request.getProposalConfirmationIds() == null
                    || !status.confirmationId().equals(request.getProposalConfirmationIds().get(floor.getKey()))) {
                throw new BusinessException("楼层「" + floor.getValue() + "」的方案已变化，请重新试算后确认报价");
            } else {
                sources.put(floor.getKey(), status.confirmationId());
            }
        }
        if (!unconfirmed.isEmpty()) {
            throw new BusinessException("楼层「" + String.join("、", unconfirmed)
                + "」的方案尚未确认或已失效，请先在项目清单页确认当前方案后再确认报价");
        }
        return sources;
    }

    private void lockProjectFloors(String projectId) {
        floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>().eq("project_id", projectId)).stream()
            .map(OfficeDesignFloor::getFloorId).distinct().sorted().forEach(floorLocks::lockWorkspace);
    }

    private OfficeQuotation findLatest(String projectId, boolean confirmedOnly) {
        return quotationMapper.selectOne(new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).eq(confirmedOnly, "status", "CONFIRMED").orderByDesc("revision").last("limit 1"));
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
