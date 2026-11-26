package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos;
import com.rsdp.officeproduct.importer.office.dto.OfficeSchemeDtos;
import com.rsdp.officeproduct.importer.office.service.OfficeModuleRecommendationService;
import com.rsdp.officeproduct.importer.office.service.OfficeProductBundleService;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectAccessService;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectPermission;
import com.rsdp.officeproduct.importer.office.service.OfficeProposalConfirmationService;
import com.rsdp.officeproduct.importer.office.service.OfficeRoomSchemeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office")
@RequiredArgsConstructor
public class OfficeProductSchemeController {
    private final OfficeRoomSchemeService rooms;
    private final OfficeProposalConfirmationService confirmations;
    private final OfficeProductBundleService bundles;
    private final OfficeModuleRecommendationService recommendations;
    private final OfficeProjectAccessService access;

    @GetMapping("/floors/{floorId}/room-scheme")
    public Result<OfficeSchemeDtos.State> state(@PathVariable String floorId, @RequestParam String zoneId) {
        return Result.ok(rooms.state(floorId, zoneId));
    }

    @PostMapping("/floors/{floorId}/room-scheme/activate")
    public Result<OfficeSchemeDtos.Result> activate(@PathVariable String floorId, @RequestBody OfficeSchemeDtos.Change change) {
        access.requireFloorPermission(floorId, OfficeProjectPermission.EDIT);
        return Result.ok(rooms.activate(floorId, change));
    }

    @PostMapping("/floors/{floorId}/room-scheme/clear-selection")
    public Result<OfficeSchemeDtos.Result> clear(@PathVariable String floorId, @RequestBody OfficeSchemeDtos.Change change) {
        access.requireFloorPermission(floorId, OfficeProjectPermission.EDIT);
        return Result.ok(rooms.clearSelection(floorId, change));
    }

    @PostMapping("/floors/{floorId}/room-scheme/apply-products")
    public Result<OfficeSchemeDtos.Result> apply(@PathVariable String floorId, @RequestBody OfficeProductBundleDtos.Apply apply) {
        access.requireFloorPermission(floorId, OfficeProjectPermission.EDIT);
        return Result.ok(recommendations.applyRoomProducts(floorId, apply));
    }

    @GetMapping("/floors/{floorId}/proposal-confirmation")
    public Result<OfficeProposalConfirmationService.Status> latest(@PathVariable String floorId) {
        return Result.ok(confirmations.latest(floorId));
    }

    @PostMapping("/floors/{floorId}/proposal-confirmation")
    public Result<OfficeProposalConfirmationService.Status> confirm(
        @PathVariable String floorId, @RequestBody OfficeProposalConfirmationService.Request request) {
        return Result.ok(confirmations.confirm(floorId, request));
    }

    @GetMapping("/product-bundles")
    public Result<List<OfficeProductBundleDtos.Bundle>> bundles() {
        return Result.ok(bundles.list());
    }

    @PutMapping("/product-bundles/{bundleId}")
    public Result<OfficeProductBundleDtos.Bundle> saveBundle(@PathVariable String bundleId, @RequestBody OfficeProductBundleDtos.Save save) {
        return Result.ok(bundles.save(bundleId, save));
    }

    @DeleteMapping("/product-bundles/{bundleId}")
    public Result<Void> deleteBundle(@PathVariable String bundleId, @RequestParam int revision) {
        bundles.delete(bundleId, revision);
        return Result.ok(null);
    }
}
