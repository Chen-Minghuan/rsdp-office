package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.entity.CategoryDict;
import com.rsdp.officeproduct.importer.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dicts")
@RequiredArgsConstructor
@Validated
public class DictController {

    private final DictService dictService;

    @GetMapping("/{type}")
    public Result<List<CategoryDict>> listByType(@PathVariable String type) {
        return Result.ok(dictService.listByType(type));
    }
}
