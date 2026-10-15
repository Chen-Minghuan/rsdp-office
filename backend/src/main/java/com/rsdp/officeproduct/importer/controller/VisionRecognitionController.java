package com.rsdp.officeproduct.importer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.service.VisionRecognitionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/vision")
@RequiredArgsConstructor
@Validated
public class VisionRecognitionController {

    private final VisionRecognitionClient visionRecognitionClient;

    @PostMapping("/recognize")
    public Result<JsonNode> recognize(@RequestPart("file") MultipartFile file,
                                     @RequestParam(defaultValue = "1") int pageNumber) {
        return Result.ok(visionRecognitionClient.recognize(file, pageNumber));
    }
}
