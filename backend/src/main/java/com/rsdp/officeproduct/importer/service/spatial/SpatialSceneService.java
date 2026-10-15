package com.rsdp.officeproduct.importer.service.spatial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanRoomResponse;
import com.rsdp.officeproduct.importer.dto.spatial.*;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanRoom;
import com.rsdp.officeproduct.importer.entity.spatial.*;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanRoomMapper;
import com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper;
import com.rsdp.officeproduct.importer.mapper.spatial.*;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpatialSceneService {

    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanRoomMapper roomMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final FloorPlanDocumentMapper documentMapper;
    private final FloorPlanPageMapper pageMapper;
    private final FloorPlanSourceSceneMapper sourceSceneMapper;
    private final FloorPlanDesignSceneMapper designSceneMapper;
    private final FloorPlanDesignRevisionMapper designRevisionMapper;
    private final FloorPlanCalibrationMapper calibrationMapper;
    private final FloorPlanConfirmationMapper confirmationMapper;
    private final FloorPlanOperationReceiptMapper receiptMapper;
    private final SpatialGeometryService geometryService;
    private final SpatialCalibrationService calibrationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SpatialSceneSnapshot createScene(CreateSceneRequest request) {
        String sceneId = IdGenerator.spatialId("SCENE");
        String pageId = request.getPageId();
        String sourceSceneId = null;
        String failedRunId = null;

        if (CreateSceneRequest.MODE_FROM_RECOGNITION.equals(request.getInitializationMode())) {
            sourceSceneId = request.getSourceSceneId();
            if (sourceSceneId == null) {
                throw new IllegalArgumentException("FROM_RECOGNITION requires sourceSceneId");
            }
        } else {
            failedRunId = request.getFailedRunId();
        }

        FloorPlanDesignScene scene = new FloorPlanDesignScene();
        scene.setSceneId(sceneId);
        scene.setProjectId(request.getProjectId());
        scene.setPageId(pageId);
        scene.setSourceSceneId(sourceSceneId);
        scene.setFailedRunId(failedRunId);
        scene.setCurrentRevision(1L);
        scene.setScopeRevision(1L);
        scene.setCreatedBy(CurrentUserContext.currentUsername());
        scene.setCreatedAt(LocalDateTime.now());
        scene.setUpdatedAt(LocalDateTime.now());
        designSceneMapper.insert(scene);

        SpatialSceneSnapshot snapshot = buildInitialSnapshot(scene, request);
        saveRevision(sceneId, 1L, null, request.getClientChangeId(), "CREATE", snapshot);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public SpatialSceneSnapshot getScene(String sceneId) {
        FloorPlanDesignScene scene = designSceneMapper.selectById(sceneId);
        if (scene == null) return null;
        FloorPlanDesignRevision rev = designRevisionMapper.selectByRevision(sceneId, scene.getCurrentRevision());
        if (rev == null) return null;
        return readSnapshot(rev.getSnapshotJson());
    }

    @Transactional
    public MutationData saveDraft(String sceneId, DraftRequest request) {
        FloorPlanDesignScene scene = designSceneMapper.selectById(sceneId);
        if (scene == null) throw new IllegalArgumentException("Scene not found");
        if (!Objects.equals(scene.getCurrentRevision(), request.getBaseRevision())) {
            throw new SpatialVersionConflictException(scene.getCurrentRevision());
        }

        SpatialSceneSnapshot current = getScene(sceneId);
        SpatialSceneSnapshot next = applyDraft(current, request);

        Long nextRevision = scene.getCurrentRevision() + 1;
        scene.setCurrentRevision(nextRevision);
        scene.setUpdatedAt(LocalDateTime.now());
        designSceneMapper.updateRevision(sceneId, nextRevision, next.getScopeRevision());
        saveRevision(sceneId, nextRevision, request.getBaseRevision(), request.getClientChangeId(), "DRAFT", next);

        List<String> invalidated = computeInvalidatedSpaces(current, next);

        MutationData data = new MutationData();
        data.setClientChangeId(request.getClientChangeId());
        data.setSceneId(sceneId);
        data.setPreviousRevision(request.getBaseRevision());
        data.setRevision(nextRevision);
        data.setAffectedSpaceIds(next.getSpaces().stream().map(SpatialSpaceSnapshot::getSpaceId).toList());
        data.setInvalidatedSpaceIds(invalidated);
        data.setConfirmationIds(List.of());
        data.setConfirmedSceneId(next.getConfirmedSceneId());
        data.setScene(next);
        return data;
    }

    @Transactional
    public MutationData applyCalibration(String sceneId, CalibrationRequest request) {
        FloorPlanDesignScene scene = designSceneMapper.selectById(sceneId);
        if (scene == null) throw new IllegalArgumentException("Scene not found");
        if (!Objects.equals(scene.getCurrentRevision(), request.getBaseRevision())) {
            throw new SpatialVersionConflictException(scene.getCurrentRevision());
        }

        SpatialSceneSnapshot current = getScene(sceneId);
        List<Double> matrix = calibrationService.computeSourceToWorld(request.getReferencePoints(), request.getReferenceLengthMm());
        if (matrix == null) throw new IllegalArgumentException("Invalid calibration reference");

        Long calRev = (current.getCalibration() == null ? 0L : current.getCalibration().getRevision()) + 1;
        FloorPlanCalibration cal = new FloorPlanCalibration();
        cal.setSceneId(sceneId);
        cal.setCalibrationRevision(calRev);
        cal.setPageId(request.getSourcePageId());
        cal.setReferencePoints(writeJson(request.getReferencePoints()));
        cal.setReferenceLengthMm(BigDecimal.valueOf(request.getReferenceLengthMm()));
        cal.setMmPerSourceUnit(BigDecimal.valueOf(calibrationService.mmPerSourceUnit(matrix)));
        cal.setSourceToWorld(writeJson(matrix));
        cal.setBasis("TWO_POINT");
        cal.setStatus(SpatialCalibrationSnapshot.STATUS_CONFIRMED);
        cal.setCreatedBy(CurrentUserContext.currentUsername());
        cal.setCreatedAt(LocalDateTime.now());
        calibrationMapper.insert(cal);

        SpatialSceneSnapshot next = transformToWorld(current, matrix, calRev);
        Long nextRevision = scene.getCurrentRevision() + 1;
        scene.setCurrentRevision(nextRevision);
        scene.setUpdatedAt(LocalDateTime.now());
        designSceneMapper.updateRevision(sceneId, nextRevision, next.getScopeRevision());
        saveRevision(sceneId, nextRevision, request.getBaseRevision(), request.getClientChangeId(), "CALIBRATION", next);

        MutationData data = new MutationData();
        data.setClientChangeId(request.getClientChangeId());
        data.setSceneId(sceneId);
        data.setPreviousRevision(request.getBaseRevision());
        data.setRevision(nextRevision);
        data.setAffectedSpaceIds(next.getSpaces().stream().map(SpatialSpaceSnapshot::getSpaceId).toList());
        data.setInvalidatedSpaceIds(data.getAffectedSpaceIds());
        data.setConfirmationIds(List.of());
        data.setConfirmedSceneId(null);
        data.setScene(next);
        return data;
    }

    @Transactional
    public MutationData confirm(String sceneId, ConfirmationRequest request) {
        FloorPlanDesignScene scene = designSceneMapper.selectById(sceneId);
        if (scene == null) throw new IllegalArgumentException("Scene not found");
        if (!Objects.equals(scene.getCurrentRevision(), request.getBaseRevision())) {
            throw new SpatialVersionConflictException(scene.getCurrentRevision());
        }

        SpatialSceneSnapshot current = getScene(sceneId);
        if (!SpatialGeometry.COORDINATE_WORLD_MM.equals(current.getWorkingCoordinateSystem())) {
            throw new IllegalStateException("Scene must be calibrated before confirmation");
        }

        List<String> targetIds = request.getSpaces().stream().map(SpaceCondition::getSpaceId).toList();
        List<SpatialSpaceSnapshot> targets = current.getSpaces().stream()
            .filter(s -> targetIds.contains(s.getSpaceId()) && Boolean.TRUE.equals(s.getIncludedInDesign()))
            .toList();

        if (ConfirmationRequest.SCOPE_SPACES.equals(request.getScope())) {
            if (targets.isEmpty()) throw new IllegalArgumentException("No target spaces");
        } else if (ConfirmationRequest.SCOPE_SCENE.equals(request.getScope())) {
            long included = current.getSpaces().stream().filter(s -> Boolean.TRUE.equals(s.getIncludedInDesign())).count();
            if (included == 0) throw new IllegalStateException("No included spaces");
            targets = current.getSpaces().stream().filter(s -> Boolean.TRUE.equals(s.getIncludedInDesign())).toList();
        }

        // validate geometry and conditions
        for (SpatialSpaceSnapshot s : targets) {
            List<SpatialIssue> issues = geometryService.validate(s.getDesignGeometry());
            if (issues.stream().anyMatch(i -> Boolean.TRUE.equals(i.getBlocksConfirmation()))) {
                throw new IllegalStateException("Space " + s.getSpaceId() + " has blocking geometry issues");
            }
            if (!Objects.equals(s.getGeometryRevision(), findCondition(request, s.getSpaceId()).getGeometryRevision())) {
                throw new IllegalStateException("Geometry revision mismatch for " + s.getSpaceId());
            }
        }

        Long nextRevision = scene.getCurrentRevision() + 1;
        SpatialSceneSnapshot next = copySnapshot(current);
        next.setRevision(nextRevision);

        List<String> confirmationIds = new ArrayList<>();
        for (SpatialSpaceSnapshot s : targets) {
            String cid = IdGenerator.spatialId("CONF");
            confirmationIds.add(cid);
            FloorPlanConfirmation conf = new FloorPlanConfirmation();
            conf.setConfirmationId(cid);
            conf.setSceneId(sceneId);
            conf.setKind("SPACE");
            conf.setSpaceId(s.getSpaceId());
            conf.setSceneRevision(nextRevision);
            conf.setGeometryRevision(s.getGeometryRevision());
            conf.setRequirementRevision(s.getRequirementRevision());
            conf.setCalibrationRevision(current.getCalibration().getRevision());
            conf.setScopeRevision(current.getScopeRevision());
            conf.setGeometryPolicyVersion(request.getGeometryPolicyVersion());
            conf.setSnapshotJson(writeJson(next));
            conf.setCreatedBy(CurrentUserContext.currentUsername());
            conf.setCreatedAt(LocalDateTime.now());
            confirmationMapper.insert(conf);

            SpatialSpaceSnapshot updated = findSpace(next, s.getSpaceId());
            updated.getConfirmation().setStatus(SpatialSpaceConfirmation.STATUS_CONFIRMED);
            updated.getConfirmation().setConfirmationId(cid);
            updated.getConfirmation().setConfirmedRevision(s.getRequirementRevision());
            updated.getConfirmation().setConfirmedGeometryRevision(s.getGeometryRevision());
            updated.getConfirmation().setCalibrationRevision(current.getCalibration().getRevision());
        }

        String confirmedSceneId = null;
        if (ConfirmationRequest.SCOPE_SCENE.equals(request.getScope())) {
            confirmedSceneId = IdGenerator.spatialId("CSCENE");
            FloorPlanConfirmation conf = new FloorPlanConfirmation();
            conf.setConfirmationId(confirmedSceneId);
            conf.setSceneId(sceneId);
            conf.setKind("SCENE");
            conf.setSceneRevision(nextRevision);
            conf.setCalibrationRevision(current.getCalibration().getRevision());
            conf.setScopeRevision(current.getScopeRevision());
            conf.setGeometryPolicyVersion(request.getGeometryPolicyVersion());
            conf.setSnapshotJson(writeJson(next));
            conf.setCreatedBy(CurrentUserContext.currentUsername());
            conf.setCreatedAt(LocalDateTime.now());
            confirmationMapper.insert(conf);
            next.setConfirmedSceneId(confirmedSceneId);
        }

        scene.setCurrentRevision(nextRevision);
        scene.setUpdatedAt(LocalDateTime.now());
        designSceneMapper.updateRevision(sceneId, nextRevision, next.getScopeRevision());
        saveRevision(sceneId, nextRevision, request.getBaseRevision(), request.getClientChangeId(), "CONFIRM", next);

        MutationData data = new MutationData();
        data.setClientChangeId(request.getClientChangeId());
        data.setSceneId(sceneId);
        data.setPreviousRevision(request.getBaseRevision());
        data.setRevision(nextRevision);
        data.setAffectedSpaceIds(targets.stream().map(SpatialSpaceSnapshot::getSpaceId).toList());
        data.setInvalidatedSpaceIds(List.of());
        data.setConfirmationIds(confirmationIds);
        data.setConfirmedSceneId(confirmedSceneId);
        data.setScene(next);
        return data;
    }

    // ==================== helpers ====================

    private SpatialSceneSnapshot buildInitialSnapshot(FloorPlanDesignScene scene, CreateSceneRequest request) {
        SpatialSceneSnapshot snapshot = new SpatialSceneSnapshot();
        snapshot.setSchemaVersion("1.2");
        snapshot.setSceneId(scene.getSceneId());
        snapshot.setProjectId(scene.getProjectId());
        snapshot.setRevision(1L);
        snapshot.setScopeRevision(1L);
        snapshot.setGeometryPolicyVersion(SpatialGeometryService.POLICY_VERSION);
        snapshot.setSourcePageId(scene.getPageId());
        snapshot.setSourceSceneId(scene.getSourceSceneId());
        snapshot.setFailedRunId(scene.getFailedRunId());
        snapshot.setWorkingCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE);

        SpatialCalibrationSnapshot cal = new SpatialCalibrationSnapshot();
        cal.setRevision(0L);
        cal.setStatus(SpatialCalibrationSnapshot.STATUS_UNKNOWN);
        cal.setSourceToWorld(null);
        snapshot.setCalibration(cal);

        snapshot.setLayerAvailability(new SpatialLayerAvailability());
        snapshot.setQualityIssues(List.of());
        snapshot.setTombstones(List.of());
        snapshot.setWalls(List.of());
        snapshot.setOpenings(List.of());
        snapshot.setObstacles(List.of());
        snapshot.setAnnotations(List.of());
        snapshot.setBoundaryRelations(List.of());
        snapshot.setIssueDispositions(List.of());
        snapshot.setConfirmedSceneId(null);

        if (CreateSceneRequest.MODE_EMPTY_PAGE.equals(request.getInitializationMode())) {
            snapshot.setSpaces(List.of());
            snapshot.setUnderlays(List.of(buildUnderlay(scene.getPageId(), null, 0L)));
            return snapshot;
        }

        // FROM_RECOGNITION: load source rooms
        FloorPlanSourceScene source = sourceSceneMapper.selectById(scene.getSourceSceneId());
        List<SpatialSpaceSnapshot> spaces = new ArrayList<>();
        if (source != null && source.getAnalysisId() != null) {
            List<FloorPlanRoom> rooms = roomMapper.selectByAnalysisRevision(source.getAnalysisId(), source.getAnalysisRevision());
            for (FloorPlanRoom room : rooms) {
                spaces.add(toSpaceSnapshot(room, scene.getSourceSceneId()));
            }
        }
        snapshot.setSpaces(spaces);
        snapshot.setUnderlays(List.of(buildUnderlay(scene.getPageId(), null, 0L)));
        return snapshot;
    }

    private SpatialUnderlay buildUnderlay(String pageId, List<Double> imagePixelToWorld, Long transformRevision) {
        SpatialUnderlay u = new SpatialUnderlay();
        u.setUnderlayId(IdGenerator.spatialId("UNDERLAY"));
        u.setSourcePageId(pageId);
        u.setAssetId(null);
        u.setWidthPx(0);
        u.setHeightPx(0);
        u.setImagePixelToWorld(imagePixelToWorld);
        u.setTransformRevision(transformRevision);
        return u;
    }

    private SpatialSpaceSnapshot toSpaceSnapshot(FloorPlanRoom room, String sourceSceneId) {
        SpatialSpaceSnapshot s = new SpatialSpaceSnapshot();
        s.setSpaceId(room.getRoomId());
        s.setKind(SpatialSpaceInput.KIND_ROOM);
        s.setParentSpaceId(null);
        s.setName(room.getLabel());
        s.setCategory(room.getRoomType());
        s.setIncludedInDesign(true);
        s.setExclusionReason(null);
        s.setBoundaryBasis(SpatialSpaceInput.BOUNDARY_INNER_WALL_FACE);
        s.setDesignGeometry(parsePolygon(room.getPolygon()));
        s.setRectangleFrame(null);
        s.setOrigin(SpatialSpaceSnapshot.ORIGIN_VISION);
        s.setEvidenceKind(SpatialSpaceSnapshot.EVIDENCE_REAL_SERVICE);
        s.setSourceRef(null);
        s.setConfidence(null);
        s.setGeometryRevision(1L);
        s.setRequirementRevision(1L);
        s.setMetadataRevision(1L);
        s.setDerived(geometryService.derive(s.getDesignGeometry(), List.of()));
        s.setConfirmation(unconfirmed());
        return s;
    }

    private SpatialGeometry parsePolygon(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<List<Double>> pts = objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
            SpatialGeometry g = new SpatialGeometry();
            g.setType(SpatialGeometry.TYPE_POLYGON);
            g.setCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE);
            g.setOuter(pts);
            g.setOuterVertexIds(geometryService.createVertexIds(pts.size()));
            g.setHoles(List.of());
            g.setHoleVertexIds(List.of());
            return g;
        } catch (Exception e) {
            log.warn("parsePolygon failed", e);
            return null;
        }
    }

    private SpatialSpaceConfirmation unconfirmed() {
        SpatialSpaceConfirmation c = new SpatialSpaceConfirmation();
        c.setStatus(SpatialSpaceConfirmation.STATUS_UNCONFIRMED);
        c.setConfirmationId(null);
        c.setConfirmedRevision(null);
        c.setConfirmedGeometryRevision(null);
        c.setCalibrationRevision(null);
        return c;
    }

    private SpatialSceneSnapshot applyDraft(SpatialSceneSnapshot current, DraftRequest request) {
        SpatialSceneSnapshot next = copySnapshot(current);
        next.setRevision(current.getRevision() + 1);
        next.setWorkingCoordinateSystem(request.getWorkingCoordinateSystem());
        if (request.getCalibrationRevision() != null) {
            next.getCalibration().setRevision(request.getCalibrationRevision());
        }

        // apply deletions first (simplified: just tombstone)
        if (request.getDeletions() != null) {
            for (SpatialDeletion d : request.getDeletions()) {
                next.getTombstones().add(toTombstone(d, next.getRevision()));
            }
        }

        // replace spaces
        if (request.getSpaces() != null) {
            Map<String, SpatialSpaceSnapshot> map = new HashMap<>();
            for (SpatialSpaceSnapshot s : next.getSpaces()) map.put(s.getSpaceId(), s);

            List<SpatialSpaceSnapshot> newSpaces = new ArrayList<>();
            for (SpatialSpaceInput input : request.getSpaces()) {
                SpatialSpaceSnapshot existing = map.get(input.getSpaceId());
                SpatialSpaceSnapshot s = toSpaceSnapshot(input, existing, next.getRevision());
                newSpaces.add(s);
            }
            next.setSpaces(newSpaces);
            next.setScopeRevision(next.getScopeRevision() + 1);
        }

        // other layers (simplified: replace)
        if (request.getWalls() != null) next.setWalls(request.getWalls());
        if (request.getOpenings() != null) next.setOpenings(request.getOpenings());
        if (request.getObstacles() != null) next.setObstacles(request.getObstacles());
        if (request.getAnnotations() != null) next.setAnnotations(request.getAnnotations());
        if (request.getBoundaryRelations() != null) next.setBoundaryRelations(request.getBoundaryRelations());
        if (request.getIssueDispositions() != null) next.setIssueDispositions(request.getIssueDispositions());

        for (SpatialSpaceSnapshot s : next.getSpaces()) {
            s.setDerived(geometryService.derive(s.getDesignGeometry(), next.getObstacles()));
        }
        return next;
    }

    private SpatialSpaceSnapshot toSpaceSnapshot(SpatialSpaceInput input, SpatialSpaceSnapshot existing, Long revision) {
        SpatialSpaceSnapshot s = new SpatialSpaceSnapshot();
        s.setSpaceId(input.getSpaceId());
        s.setKind(input.getKind());
        s.setParentSpaceId(input.getParentSpaceId());
        s.setName(input.getName());
        s.setCategory(input.getCategory());
        s.setIncludedInDesign(input.getIncludedInDesign());
        s.setExclusionReason(input.getExclusionReason());
        s.setBoundaryBasis(input.getBoundaryBasis());
        s.setDesignGeometry(geometryService.ensureVertexIds(input.getDesignGeometry()));
        s.setRectangleFrame(input.getRectangleFrame());

        if (existing != null) {
            s.setOrigin(existing.getOrigin());
            s.setEvidenceKind(existing.getEvidenceKind());
            s.setSourceRef(existing.getSourceRef());
            s.setConfidence(existing.getConfidence());
            s.setGeometryRevision(geometryChanged(existing, s) ? existing.getGeometryRevision() + 1 : existing.getGeometryRevision());
            s.setRequirementRevision(requirementChanged(existing, s) ? existing.getRequirementRevision() + 1 : existing.getRequirementRevision());
            s.setMetadataRevision(metadataChanged(existing, s) ? existing.getMetadataRevision() + 1 : existing.getMetadataRevision());
            s.setConfirmation(copyConfirmation(existing.getConfirmation()));
        } else {
            s.setOrigin(SpatialSpaceSnapshot.ORIGIN_MANUAL);
            s.setEvidenceKind(SpatialSpaceSnapshot.EVIDENCE_MANUAL);
            s.setSourceRef(null);
            s.setConfidence(null);
            s.setGeometryRevision(1L);
            s.setRequirementRevision(1L);
            s.setMetadataRevision(1L);
            s.setConfirmation(unconfirmed());
        }
        return s;
    }

    private boolean geometryChanged(SpatialSpaceSnapshot a, SpatialSpaceSnapshot b) {
        return !Objects.equals(a.getDesignGeometry(), b.getDesignGeometry())
            || !Objects.equals(a.getRectangleFrame(), b.getRectangleFrame())
            || !Objects.equals(a.getBoundaryBasis(), b.getBoundaryBasis());
    }

    private boolean requirementChanged(SpatialSpaceSnapshot a, SpatialSpaceSnapshot b) {
        return !Objects.equals(a.getIncludedInDesign(), b.getIncludedInDesign())
            || !Objects.equals(a.getParentSpaceId(), b.getParentSpaceId())
            || !Objects.equals(a.getKind(), b.getKind())
            || !Objects.equals(a.getCategory(), b.getCategory());
    }

    private boolean metadataChanged(SpatialSpaceSnapshot a, SpatialSpaceSnapshot b) {
        return !Objects.equals(a.getName(), b.getName());
    }

    private SpatialSpaceConfirmation copyConfirmation(SpatialSpaceConfirmation c) {
        SpatialSpaceConfirmation n = new SpatialSpaceConfirmation();
        n.setStatus(c.getStatus());
        n.setConfirmationId(c.getConfirmationId());
        n.setConfirmedRevision(c.getConfirmedRevision());
        n.setConfirmedGeometryRevision(c.getConfirmedGeometryRevision());
        n.setCalibrationRevision(c.getCalibrationRevision());
        return n;
    }

    private SpatialTombstone toTombstone(SpatialDeletion d, Long revision) {
        SpatialTombstone t = new SpatialTombstone();
        t.setLayer(d.getLayer());
        t.setObjectId(d.getObjectId());
        t.setReason(d.getReason());
        t.setDeletedAtRevision(revision);
        return t;
    }

    private SpatialSceneSnapshot transformToWorld(SpatialSceneSnapshot current, List<Double> matrix, Long calRev) {
        SpatialSceneSnapshot next = copySnapshot(current);
        next.setWorkingCoordinateSystem(SpatialGeometry.COORDINATE_WORLD_MM);
        next.getCalibration().setRevision(calRev);
        next.getCalibration().setStatus(SpatialCalibrationSnapshot.STATUS_CONFIRMED);
        next.getCalibration().setSourceToWorld(matrix);

        List<SpatialSpaceSnapshot> spaces = new ArrayList<>();
        for (SpatialSpaceSnapshot s : next.getSpaces()) {
            SpatialSpaceSnapshot t = copySpace(s);
            t.setDesignGeometry(calibrationService.transformGeometry(s.getDesignGeometry(), matrix));
            t.setRectangleFrame(null); // recalc after transform
            t.setGeometryRevision(s.getGeometryRevision() + 1);
            t.setRequirementRevision(s.getRequirementRevision() + 1);
            t.setDerived(geometryService.derive(t.getDesignGeometry(), next.getObstacles()));
            spaces.add(t);
        }
        next.setSpaces(spaces);

        // 同步更新底图变换
        List<SpatialUnderlay> underlays = new ArrayList<>();
        for (SpatialUnderlay u : next.getUnderlays()) {
            SpatialUnderlay copy = new SpatialUnderlay();
            copy.setUnderlayId(u.getUnderlayId());
            copy.setSourcePageId(u.getSourcePageId());
            copy.setAssetId(u.getAssetId());
            copy.setWidthPx(u.getWidthPx());
            copy.setHeightPx(u.getHeightPx());
            copy.setImagePixelToWorld(matrix);
            copy.setTransformRevision(calRev);
            underlays.add(copy);
        }
        next.setUnderlays(underlays);
        return next;
    }

    private List<String> computeInvalidatedSpaces(SpatialSceneSnapshot before, SpatialSceneSnapshot after) {
        List<String> ids = new ArrayList<>();
        Map<String, SpatialSpaceConfirmation> beforeConf = new HashMap<>();
        for (SpatialSpaceSnapshot s : before.getSpaces()) beforeConf.put(s.getSpaceId(), s.getConfirmation());
        for (SpatialSpaceSnapshot s : after.getSpaces()) {
            SpatialSpaceConfirmation bc = beforeConf.get(s.getSpaceId());
            if (bc != null && SpatialSpaceConfirmation.STATUS_CONFIRMED.equals(bc.getStatus())) {
                if (!Objects.equals(s.getGeometryRevision(), bc.getConfirmedGeometryRevision())
                    || !Objects.equals(s.getRequirementRevision(), bc.getConfirmedRevision())) {
                    ids.add(s.getSpaceId());
                    s.getConfirmation().setStatus(SpatialSpaceConfirmation.STATUS_UNCONFIRMED);
                    s.getConfirmation().setConfirmationId(null);
                }
            }
        }
        return ids;
    }

    private SpaceCondition findCondition(ConfirmationRequest request, String spaceId) {
        return request.getSpaces().stream().filter(c -> c.getSpaceId().equals(spaceId)).findFirst().orElseThrow();
    }

    private SpatialSpaceSnapshot findSpace(SpatialSceneSnapshot snapshot, String spaceId) {
        return snapshot.getSpaces().stream().filter(s -> s.getSpaceId().equals(spaceId)).findFirst().orElseThrow();
    }

    private void saveRevision(String sceneId, Long revision, Long parentRevision, String clientChangeId, String action, SpatialSceneSnapshot snapshot) {
        FloorPlanDesignRevision rev = new FloorPlanDesignRevision();
        rev.setSceneId(sceneId);
        rev.setRevision(revision);
        rev.setParentRevision(parentRevision);
        rev.setClientChangeId(clientChangeId);
        rev.setAction(action);
        rev.setSnapshotJson(writeJson(snapshot));
        rev.setCreatedBy(CurrentUserContext.currentUsername());
        rev.setCreatedAt(LocalDateTime.now());
        designRevisionMapper.insert(rev);
    }

    private SpatialSceneSnapshot copySnapshot(SpatialSceneSnapshot s) {
        // deep copy through JSON round-trip for simplicity
        return readSnapshot(writeJson(s));
    }

    private SpatialSpaceSnapshot copySpace(SpatialSpaceSnapshot s) {
        return readSnapshot(writeJson(Map.of("s", s))).getSpaces().get(0);
    }

    private SpatialSceneSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, SpatialSceneSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read snapshot", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }
}
