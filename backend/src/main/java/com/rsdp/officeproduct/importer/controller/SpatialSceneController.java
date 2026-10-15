package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.dto.spatial.*;
import com.rsdp.officeproduct.importer.service.spatial.SpatialSceneService;
import com.rsdp.officeproduct.importer.service.spatial.SpatialVersionConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/floor-plan")
@RequiredArgsConstructor
public class SpatialSceneController {

    private final SpatialSceneService spatialSceneService;

    @PostMapping("/design-scenes")
    public ResponseEntity<SpatialResult<MutationData>> createScene(@RequestBody CreateSceneRequest request) {
        SpatialSceneSnapshot scene = spatialSceneService.createScene(request);
        MutationData data = new MutationData();
        data.setClientChangeId(request.getClientChangeId());
        data.setSceneId(scene.getSceneId());
        data.setPreviousRevision(0L);
        data.setRevision(1L);
        data.setAffectedSpaceIds(scene.getSpaces().stream().map(SpatialSpaceSnapshot::getSpaceId).toList());
        data.setInvalidatedSpaceIds(java.util.List.of());
        data.setConfirmationIds(java.util.List.of());
        data.setConfirmedSceneId(null);
        data.setScene(scene);
        return ResponseEntity.ok(SpatialResult.ok(data));
    }

    @GetMapping("/design-scenes/{sceneId}")
    public ResponseEntity<SpatialResult<SpatialSceneSnapshot>> getScene(@PathVariable String sceneId) {
        SpatialSceneSnapshot scene = spatialSceneService.getScene(sceneId);
        if (scene == null) {
            return ResponseEntity.status(404).body(SpatialResult.error(404, "Scene not found", null));
        }
        return ResponseEntity.ok(SpatialResult.ok(scene));
    }

    @PutMapping("/design-scenes/{sceneId}/draft")
    public ResponseEntity<SpatialResult<MutationData>> saveDraft(@PathVariable String sceneId,
                                                                  @RequestBody DraftRequest request) {
        try {
            return ResponseEntity.ok(SpatialResult.ok(spatialSceneService.saveDraft(sceneId, request)));
        } catch (SpatialVersionConflictException e) {
            return ResponseEntity.status(409).body(SpatialResult.error(409, e.getMessage(), null));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(SpatialResult.error(400, e.getMessage(), null));
        }
    }

    @PostMapping("/design-scenes/{sceneId}/calibrations")
    public ResponseEntity<SpatialResult<MutationData>> applyCalibration(@PathVariable String sceneId,
                                                                         @RequestBody CalibrationRequest request) {
        try {
            return ResponseEntity.ok(SpatialResult.ok(spatialSceneService.applyCalibration(sceneId, request)));
        } catch (SpatialVersionConflictException e) {
            return ResponseEntity.status(409).body(SpatialResult.error(409, e.getMessage(), null));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(SpatialResult.error(400, e.getMessage(), null));
        }
    }

    @PostMapping("/design-scenes/{sceneId}/confirmations")
    public ResponseEntity<SpatialResult<MutationData>> confirm(@PathVariable String sceneId,
                                                                @RequestBody ConfirmationRequest request) {
        try {
            return ResponseEntity.ok(SpatialResult.ok(spatialSceneService.confirm(sceneId, request)));
        } catch (SpatialVersionConflictException e) {
            return ResponseEntity.status(409).body(SpatialResult.error(409, e.getMessage(), null));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(SpatialResult.error(400, e.getMessage(), null));
        }
    }
}
