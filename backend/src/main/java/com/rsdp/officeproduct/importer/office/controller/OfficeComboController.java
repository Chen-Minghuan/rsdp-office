package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.OfficeComboDto;
import com.rsdp.officeproduct.importer.office.service.OfficeComboService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office/combos")
@RequiredArgsConstructor
public class OfficeComboController {

    private final OfficeComboService comboService;

    @GetMapping
    public Result<List<OfficeComboDto>> combos() {
        return Result.ok(comboService.listCombos());
    }

    @PostMapping
    public Result<OfficeComboDto> createCombo(@RequestBody ComboNameRequest request) {
        return Result.ok(comboService.createCombo(request.name()));
    }

    @PatchMapping("/{comboId}")
    public Result<Void> renameCombo(@PathVariable String comboId, @RequestBody ComboNameRequest request) {
        comboService.renameCombo(comboId, request.name());
        return Result.ok(null);
    }

    @PostMapping("/{comboId}/items")
    public Result<Void> addComboItems(@PathVariable String comboId, @RequestBody SaveComboItemsRequest request) {
        comboService.addComboItems(comboId, request.rspuIds());
        return Result.ok(null);
    }

    @DeleteMapping("/{comboId}/items/{rspuId}")
    public Result<Void> removeComboItem(@PathVariable String comboId, @PathVariable String rspuId) {
        comboService.removeComboItem(comboId, rspuId);
        return Result.ok(null);
    }

    @DeleteMapping("/{comboId}")
    public Result<Void> deleteCombo(@PathVariable String comboId) {
        comboService.deleteCombo(comboId);
        return Result.ok(null);
    }

    public record ComboNameRequest(String name) {
    }

    public record SaveComboItemsRequest(List<String> rspuIds) {
    }
}
