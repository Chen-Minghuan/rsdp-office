package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.*;
import com.rsdp.officeproduct.importer.office.service.OfficeService;
import com.rsdp.officeproduct.importer.office.service.OfficeModuleRecommendationService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/office")
@RequiredArgsConstructor
public class OfficeController {

    private final OfficeService officeService;
    private final OfficeModuleRecommendationService moduleRecommendations;

    @PostMapping("/projects/{projectId}/module-recommendations")
    public Result<ModuleRecommendationDto> recommendModules(@PathVariable String projectId, @RequestBody ModuleRecommendationRequest request) {
        return Result.ok(moduleRecommendations.recommend(projectId, request));
    }

    @PostMapping("/projects/{projectId}/module-products")
    public Result<ModuleRecommendationDto.Selection> selectModuleProduct(@PathVariable String projectId, @RequestBody ModuleRecommendationRequest request) {
        return Result.ok(moduleRecommendations.select(projectId, request));
    }

    @GetMapping("/projects/{projectId}/canvas")
    public Result<CanvasDto> getCanvas(@PathVariable String projectId) {
        return Result.ok(officeService.getCanvas(projectId));
    }

    @PutMapping("/projects/{projectId}/canvas")
    public Result<CanvasDto> saveCanvas(@PathVariable String projectId, @RequestBody SaveCanvasRequest request) {
        request.setProjectId(projectId);
        return Result.ok(officeService.saveCanvas(request));
    }

    @GetMapping("/projects/{projectId}/cart")
    public Result<List<CartItemDto>> listCart(@PathVariable String projectId) {
        return Result.ok(officeService.listCart(projectId));
    }

    @PostMapping("/projects/{projectId}/cart")
    public Result<CartItemDto> addCartItem(@PathVariable String projectId, @RequestBody CartItemDto dto) {
        return Result.ok(officeService.addCartItem(projectId, dto));
    }

    @PutMapping("/projects/{projectId}/cart/{lineId}")
    public Result<CartItemDto> updateCartItem(@PathVariable String projectId, @PathVariable String lineId,
                                              @RequestParam Integer qty) {
        return Result.ok(officeService.updateCartItemQty(projectId, lineId, qty));
    }

    @DeleteMapping("/projects/{projectId}/cart/{lineId}")
    public Result<Void> deleteCartItem(@PathVariable String projectId, @PathVariable String lineId) {
        officeService.deleteCartItem(projectId, lineId);
        return Result.ok(null);
    }

    @DeleteMapping("/projects/{projectId}/cart")
    public Result<Void> clearCart(@PathVariable String projectId) {
        officeService.clearCart(projectId);
        return Result.ok(null);
    }

    @GetMapping("/projects/{projectId}/agent-messages")
    public Result<List<AgentMessageDto>> listMessages(@PathVariable String projectId) {
        return Result.ok(officeService.listMessages(projectId));
    }

    @PostMapping("/projects/{projectId}/agent-messages")
    public Result<AgentMessageDto> sendMessage(@PathVariable String projectId, @RequestBody SendMessageRequest request) {
        return Result.ok(officeService.sendMessage(projectId, request));
    }

    @GetMapping("/products")
    public Result<List<OfficeProductDto>> listProducts() {
        return Result.ok(officeService.listOfficeProducts());
    }

    @PostMapping("/projects/{projectId}/proposal/export")
    public void exportProposal(@PathVariable String projectId, HttpServletResponse response) throws IOException {
        List<CartItemDto> cart = officeService.listCart(projectId);
        List<OfficeProductDto> products = officeService.listOfficeProducts();

        response.setContentType("text/csv;charset=utf-8");
        String fileName = URLEncoder.encode("RSDP_办公方案_演示BOM", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".csv");

        StringBuilder sb = new StringBuilder();
        sb.append("\uFEFF");
        sb.append("空间区域,演示商品ID,商品名称,规格,数量,参考单价,参考小计\r\n");
        for (CartItemDto item : cart) {
            OfficeProductDto p = products.stream()
                .filter(x -> x.getId().equals(item.getProductId())).findFirst().orElse(null);
            sb.append(quote(item.getZoneName())).append(",");
            sb.append(quote(p != null ? p.getId() : item.getProductId())).append(",");
            sb.append(quote(p != null ? p.getName() : "")).append(",");
            sb.append(quote(p != null ? p.getVariant() : "")).append(",");
            sb.append(item.getQty()).append(",");
            sb.append(p != null ? p.getPrice() : 0).append(",");
            sb.append(p != null ? p.getPrice().multiply(new java.math.BigDecimal(item.getQty())) : 0).append("\r\n");
        }
        response.getWriter().write(sb.toString());
    }

    @PostMapping("/projects/{projectId}/reset")
    public Result<Void> resetProject(@PathVariable String projectId) {
        officeService.resetProjectData(projectId);
        return Result.ok(null);
    }

    private String quote(String value) {
        String v = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }
}
