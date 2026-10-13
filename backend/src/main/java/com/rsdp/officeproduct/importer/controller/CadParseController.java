package com.rsdp.officeproduct.importer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.service.CadParseClient;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cad")
@RequiredArgsConstructor
@Validated
public class CadParseController {

    private final CadParseClient cadParseClient;

    @PostMapping("/parse")
    public Result<JsonNode> parseCad(@RequestPart("file") MultipartFile file) {
        return Result.ok(cadParseClient.parse(file));
    }
}
