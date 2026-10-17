package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class QwenVisionClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private VisionProperties properties;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private QwenVisionClient client;
    private VisionTaskExecutors executors;
    @org.junit.jupiter.api.AfterEach
    void stopExecutors() { executors.destroy(); }

    @BeforeEach
    void setup() {
        properties = new VisionProperties();
        properties.setApiKey("test-key");
        properties.setRefineRoomContours(false);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        executors = new VisionTaskExecutors();
        client = new QwenVisionClient(restTemplate, properties, mapper,
            new VisionImagePreparer(properties), new SpatialGeometryService(), executors);
    }

    @Test
    void ungroundedModelConfidenceIsLoweredEvenWhenNoWallCorrectionWasAttempted() throws Exception {
        properties.setRefineRoomContours(true);
        server.expect(requestTo(properties.getBaseUrl() + "/chat/completions"))
            .andExpect(jsonPath("$.max_tokens").value(4096))
            .andRespond(withSuccess(completion("{\"labels\":[]}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.getBaseUrl() + "/chat/completions"))
            .andExpect(jsonPath("$.max_tokens").value(12288))
            .andRespond(withSuccess(completion("""
                {"rooms":[{"label":"开放办公区","roomType":"OPEN_OFFICE","confidence":"high",
                "polygon":[[100,100],[900,100],[900,900],[100,900]]}]}
                """), MediaType.APPLICATION_JSON));
        var result = client.recognize(image(), 1);
        assertEquals("low", result.path("rooms").get(0).path("confidence").asText());
        assertFalse(result.path("rooms").get(0).path("contourGrounded").asBoolean());
        assertTrue(result.path("qualityIssues").toString().contains("ROOM_CONTOUR_UNVERIFIED"));
        assertEquals("high", result.path("initialModelOutput").path("rooms").get(0).path("confidence").asText());
        server.verify();
    }

    @Test
    void actualOverflowResponseStillImportsTheMainOfficeAndAllSixSupervisorRooms() throws Exception {
        properties.setRefineRoomContours(true);
        JsonNode fixture; byte[] png;
        try (var json = getClass().getResourceAsStream("/floorplans/overflow-open-floor.json");
             var image = getClass().getResourceAsStream("/floorplans/thin-partitions.png")) {
            fixture = mapper.readTree(json); png = image.readAllBytes();
        }
        server.expect(jsonPath("$.max_tokens").value(12288))
            .andRespond(withSuccess(completion(fixture.path("initialModelOutput").toString()), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096))
            .andRespond(withSuccess(completion(fixture.path("labelLocalization").toString()), MediaType.APPLICATION_JSON));
        var result = client.recognize(new MockMultipartFile("file", "7.png", "image/png", png), 1);
        int open = 0, offices = 0;
        for (var room : result.path("rooms")) {
            if ("OPEN_OFFICE".equals(room.path("roomType").asText())) {
                open++; assertEquals("开放办公区(38人)", room.path("label").asText());
                assertTrue(room.path("polygon").size() > 4, "保留左侧凹角和柱角");
                for (var point : room.path("polygon")) {
                    assertTrue(point.get(0).asDouble() >= 0 && point.get(0).asDouble() <= 1849);
                    assertTrue(point.get(1).asDouble() >= 0 && point.get(1).asDouble() <= 1270);
                }
            }
            if ("主管室".equals(room.path("label").asText())) offices++;
        }
        assertEquals(1, open); assertEquals(6, offices);
        assertEquals(1175, result.path("initialModelOutput").path("rooms").get(13).path("polygon").get(1).get(0).asInt(), "原始错误响应保留");
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        server.verify();
    }

    @Test
    void actualMixedOfficeResponseRetainsMissingRoomsEvenWithClosedEdgeTupleEncoding() throws Exception {
        properties.setRefineRoomContours(true);
        for (String fixtureName : java.util.List.of("mixed-office-hybrid", "mixed-office-flat")) {
            JsonNode fixture; byte[] png;
            try (var json = getClass().getResourceAsStream("/floorplans/" + fixtureName + ".json");
                 var image = getClass().getResourceAsStream("/floorplans/mixed-office.png")) {
                fixture = mapper.readTree(json); png = image.readAllBytes();
            }
            assertEquals(fixture.path("sourceImageSha256").asText(), VisionImagePreparer.sha256(png));
            server.expect(jsonPath("$.max_tokens").value(12288))
                .andRespond(withSuccess(completion(fixture.path("initialModelOutput").toString()), MediaType.APPLICATION_JSON));
            server.expect(jsonPath("$.max_tokens").value(4096))
                .andRespond(withSuccess(completion(fixture.path("labelLocalization").toString()), MediaType.APPLICATION_JSON));
            var result = client.recognize(new MockMultipartFile("file", "8.png", "image/png", png), 1);
            var names = new java.util.HashSet<String>();
            for (var room : result.path("rooms")) {
                names.add(room.path("label").asText());
                assertTrue(room.path("polygon").size() >= 3);
                assertTrue(room.path("widthMm").isNull());
                if (!room.path("contourGrounded").asBoolean()) assertEquals("low", room.path("confidence").asText());
            }
            assertEquals(22, result.path("rooms").size(), names.toString());
            assertTrue(names.containsAll(java.util.Set.of("会议室", "办公区", "展厅", "总经室", "财务室", "技术部", "副总办公室", "文印", "储物间", "休闲茶室")), names.toString());
            assertFalse(names.contains("钢琴")); assertFalse(names.contains("外机"));
            assertEquals(fixture.path("initialModelOutput"), result.path("initialModelOutput"), "保留原始错误编码");
            assertEquals(2, result.path("semanticModelOutput").path("rooms").get(0).path("polygon").get(0).size());
            assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
            if (fixtureName.equals("mixed-office-hybrid")) {
                var tea = java.util.stream.StreamSupport.stream(result.path("rooms").spliterator(), false)
                    .filter(room -> "休闲茶室".equals(room.path("label").asText())).findFirst().orElseThrow();
                assertFalse(tea.path("contourGrounded").asBoolean(), "不能把茶室扩张到楼梯核心区");
                assertEquals(fixture.path("initialModelOutput").path("rooms").get(22).path("polygon").get(0).get(0).asDouble() * 1894 / 1000,
                    tea.path("polygon").get(0).get(0).asDouble(), .001);
            }
            server.verify(); server.reset();
        }
    }

    @Test
    void disconnectedEdgeTuplesAreNotGuessedIntoRoomPolygons() throws Exception {
        server.expect(jsonPath("$.max_tokens").value(12288))
            .andRespond(withSuccess(completion("""
                {"rooms":[{"label":"会议室","polygon":[[10,10,50,10],[60,10,50,50],[50,50,10,10]]}]}
                """), MediaType.APPLICATION_JSON));
        var result = client.recognize(image(), 1);
        assertEquals(0, result.path("rooms").size());
        assertEquals("会议室", result.path("unresolvedSpaces").get(0).path("label").asText());
        server.verify();
    }

    @Test
    void independentRequestsOverlapBeforeEitherResponseIsReleased() throws Exception {
        properties.setRefineRoomContours(true);
        var entered = new java.util.concurrent.CountDownLatch(2);
        String labelsResponse = completion("{\"labels\":[]}");
        String roomsResponse = completion("{\"rooms\":[]}");
        var transport = new RestTemplate();
        transport.getInterceptors().add((request, body, execution) -> {
            int tokens = mapper.readTree(body).path("max_tokens").asInt();
            entered.countDown();
            try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "两个请求必须同时在途，不能等待前一个返回"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
            return new org.springframework.mock.http.client.MockClientHttpResponse(
                (tokens == 4096 ? labelsResponse : roomsResponse).getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.OK);
        });
        var parallel = new QwenVisionClient(transport, properties, mapper, new VisionImagePreparer(properties), new SpatialGeometryService(), executors);
        var stages = new java.util.ArrayList<String>();
        var progress = new RecognitionProgress(mapper, value -> stages.add(value.path("stage").asText()));
        var result = parallel.recognizePrepared(new VisionImagePreparer(properties).prepare(image(), 1), progress);
        assertNotNull(result);
        assertTrue(progress.snapshot().path("timingsMs").has("spaces"));
        assertTrue(progress.snapshot().path("timingsMs").has("labels"));
        assertTrue(progress.snapshot().path("timingsMs").has("contours"));
        assertEquals("RECOGNIZING", stages.get(0));
        assertEquals("REFINING", stages.get(stages.size() - 1));
        progress.stage("FAILED");
        progress.completed("late", System.nanoTime());
        progress.stage("DONE");
        assertEquals("FAILED", progress.snapshot().path("stage").asText());
        assertFalse(progress.snapshot().path("timingsMs").has("late"));
    }

    @Test
    void usesQwenAuthenticationAndSameImageForPreviewWithoutInventingPhysicalDimensions() throws Exception {
        server.expect(requestTo(properties.getBaseUrl() + "/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(jsonPath("$.model").value("qwen3-vl-max"))
            .andExpect(jsonPath("$.enable_thinking").value(false))
            .andExpect(jsonPath("$.response_format.type").value("json_object"))
            .andExpect(jsonPath("$.messages[0].content[1].image_url.url").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
            .andRespond(withSuccess(completion("""
                {"rooms":[{"label":"会议室","roomType":"MEETING_ROOM","confidence":"high",
                 "widthMm":9999,"areaM2":9999,
                 "polygon":[[0,0],[1000,0],[1000,500],[500,500],[500,1000],[0,1000]]}]}
                """), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognize(image(), 1);
        assertEquals("qwen", result.path("recognitionSource").asText());
        assertEquals("px", result.path("units").asText());
        assertEquals("NEEDS_REVIEW", result.path("parseStatus").asText());
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        assertTrue(result.path("unitResolution").path("scaleToMm").isNull());
        JsonNode room = result.path("rooms").get(0);
        assertEquals(6, room.path("polygon").size());
        assertEquals(200, room.path("polygon").get(1).get(0).asDouble());
        assertEquals(50, room.path("polygon").get(2).get(1).asDouble());
        assertTrue(room.path("widthMm").isNull());
        assertTrue(room.path("areaM2").isNull());
        assertEquals(9999, result.path("modelOutput").path("rooms").get(0).path("widthMm").asInt());
        assertEquals(result.path("drawingBounds"), result.path("preview").path("bounds"));
        byte[] preview = Base64.getDecoder().decode(result.path("preview").path("pngBase64").asText());
        assertEquals(200, ImageIO.read(new ByteArrayInputStream(preview)).getWidth());
        assertFalse(properties.toString().contains("test-key"));
        server.verify();
    }

    @Test
    void defaultWallGroundingLocatesLabelsIndependentlyAndPreservesInitialDimensions() throws Exception {
        assertTrue(new VisionProperties().isRefineRoomContours());
        properties.setRefineRoomContours(true);
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("""
            {"rooms":[{"label":"办公室","roomType":"EXECUTIVE_OFFICE",
              "polygon":[[105,131],[430,131],[430,350],[105,350]]}],
             "dimensionReferences":[
              {"text":"10000mm","confidence":"high","start":[0,0],"end":[1000,0]},
              {"text":"8000mm","confidence":"high","start":[0,0],"end":[0,1000]}]}
            """), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096))
            .andExpect(jsonPath("$.messages[0].content.length()").value(2))
            .andRespond(withSuccess(completion("""
                {"labels":[{"text":"办公室","bbox":[200,200,280,230]}]}
                """), MediaType.APPLICATION_JSON));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(RasterWallContoursTest.drawing(false), "png", bytes);
        var result = client.recognize(new MockMultipartFile("file", "plan.png", "image/png", bytes.toByteArray()), 1);
        assertEquals(1, result.path("contourRefinement").path("acceptedCount").asInt());
        assertEquals("INDEPENDENT_LABEL_DETECTION", result.path("contourRefinement").path("seedSource").asText());
        assertEquals("DETECTED", result.path("modelOutput").path("labelLocalization").path("status").asText());
        assertTrue(result.path("rooms").get(0).path("contourGrounded").asBoolean());
        assertEquals(240, result.path("rooms").get(0).path("labelPoint").path("x").asDouble());
        assertEquals(4, result.path("initialModelOutput").path("rooms").get(0).path("polygon").size());
        assertEquals(6, result.path("rooms").get(0).path("polygon").size());
        assertEquals(result.path("initialModelOutput").path("dimensionReferences"), result.path("modelOutput").path("dimensionReferences"));
        assertEquals("READY", result.path("dimensionCalibration").path("status").asText());
        assertEquals(10, result.path("dimensionCalibration").path("mmPerPixel").asDouble());
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        assertEquals("px", result.path("units").asText());
        assertEquals(result.path("drawingBounds"), result.path("preview").path("bounds"));
        server.verify();
    }

    @Test
    void semanticOnlyModelOutputGetsItsContourFromWallTopology() throws Exception {
        properties.setRefineRoomContours(true);
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("""
            {"labels":[{"text":"办公室甲","roomType":"EXECUTIVE_OFFICE","confidence":"high"}]}
            """), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withSuccess(completion("""
            {"labels":[{"text":"办公室甲","bbox":[200,200,280,230]}]}
            """), MediaType.APPLICATION_JSON));

        JsonNode result = client.recognize(new MockMultipartFile("file", "plan.png", "image/png",
            png(RasterWallContoursTest.drawing(false))), 1);

        assertEquals(1, result.path("rooms").size());
        assertTrue(result.path("rooms").get(0).path("polygon").size() >= 4);
        assertTrue(result.path("rooms").get(0).path("contourGrounded").asBoolean());
        assertEquals("QWEN_LABELS_ONLY", result.path("contourRefinement").path("semanticBoundaryPolicy").asText());
        assertEquals("PNG_WALL_TOPOLOGY_V1", result.path("contourRefinement").path("method").asText());
        assertTrue(result.path("semanticModelOutput").path("rooms").get(0).path("semanticOnly").asBoolean());
        assertFalse(result.path("initialModelOutput").path("labels").isMissingNode());
        server.verify();
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void failedIndependentDetectionUsesPrimaryTextBoxWithoutClaimingIndependentGrounding() throws Exception {
        assertPrimaryTextFallback(true);
    }

    @Test
    void disablingSecondaryDetectionStillBuildsSemanticContours() throws Exception {
        assertPrimaryTextFallback(false);
    }

    private void assertPrimaryTextFallback(boolean secondaryEnabled) throws Exception {
        properties.setRefineRoomContours(secondaryEnabled);
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("""
            {"labels":[{"text":"办公室甲","bbox":[200,200,280,230],"roomType":"EXECUTIVE_OFFICE","confidence":"high"}]}
            """), MediaType.APPLICATION_JSON));
        if (secondaryEnabled) server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var result = client.recognize(new MockMultipartFile("file", "plan.png", "image/png",
            png(RasterWallContoursTest.drawing(false))), 1);
        assertEquals(1, result.path("rooms").size());
        assertEquals(1, result.path("unnamedRegions").size(), "无房名的另一个封闭空间仍可保留");
        var room = result.path("rooms").get(0);
        assertTrue(room.path("polygon").size() >= 6, "墙线生成 L 形，文字框不能代替房间");
        assertEquals("PRIMARY_SEMANTIC_LABEL", room.path("contourGroundingSource").asText());
        assertFalse(room.path("contourGrounded").asBoolean());
        assertEquals("low", room.path("confidence").asText());
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        assertFalse(result.path("initialModelOutput").path("labels").get(0).has("polygon"));
        if (secondaryEnabled) assertEquals("UNAVAILABLE", result.path("independentLabelLocalization").path("status").asText());
        assertEquals("STRUCTURAL_PIXEL_MASK", result.path("contourRefinement").path("wallMaskSource").asText());
        assertEquals("STRUCTURAL_WALL_MASK", result.path("contourRefinement").path("stages").get(0).asText());
        assertFalse(result.has("wallSegmentation"));
        assertFalse(result.path("qualityIssues").toString().contains("未配置墙体分割模型"));
        server.verify();
    }

    @Test
    void missingAllTextPositionsRetainsSemanticsAndDiscoversUnlabelledWallRegions() throws Exception {
        properties.setRefineRoomContours(true);
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("""
            {"labels":[{"text":"办公室甲","roomType":"EXECUTIVE_OFFICE"}]}
            """), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var result = client.recognize(new MockMultipartFile("file", "plan.png", "image/png",
            png(RasterWallContoursTest.drawing(false))), 1);
        assertEquals(0, result.path("rooms").size(), "没有位置证据不能给未命名区域强行套房名");
        assertEquals(2, result.path("unnamedRegions").size());
        assertEquals(1, result.path("unresolvedSpaces").size());
        for (var room : result.path("unnamedRegions")) {
            assertEquals("UNLABELLED_WALL_REGION", room.path("contourRecovery").asText());
            assertFalse(room.path("contourGrounded").asBoolean());
            assertEquals("low", room.path("confidence").asText());
        }
        server.verify();
    }

    @Test
    void semanticRealImageKeepsSixOfficesAndUsesPrimaryTextEvidenceForTheCentralOpenFloor() throws Exception {
        properties.setRefineRoomContours(true);
        JsonNode fixture; byte[] bytes;
        try (var json = getClass().getResourceAsStream("/floorplans/thin-partitions.json");
             var input = getClass().getResourceAsStream("/floorplans/thin-partitions.png")) {
            fixture = mapper.readTree(json); bytes = input.readAllBytes();
        }
        var primary = mapper.createObjectNode(); var labels = primary.putArray("labels");
        // Derived semantic-only contract fixture. Existing frozen OCR plus one visually annotated
        // central text box; this is not a new live model response or a boundary gold annotation.
        var used = new java.util.HashSet<Integer>();
        for (var room : fixture.path("initialModelOutput").path("rooms")) {
            if (!room.path("label").isTextual()) continue;
            var label = labels.addObject().put("text", room.path("label").asText())
                .put("roomType", room.path("roomType").asText()).put("confidence", "high");
            if (room.has("dimensionText")) label.set("dimensionText", room.path("dimensionText").deepCopy());
            var detected = fixture.path("labelLocalization").path("labels");
            for (int i = 0; i < detected.size(); i++) if (!used.contains(i)
                && room.path("label").asText().equals(detected.get(i).path("text").asText())) {
                label.set("bbox", detected.get(i).path("bbox").deepCopy()); used.add(i); break;
            }
            if ("OPEN_OFFICE".equals(room.path("roomType").asText())) label.set("bbox", mapper.readTree("[620,650,695,670]"));
        }
        var before = primary.deepCopy();
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion(primary.toString()), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withSuccess(completion(fixture.path("labelLocalization").toString()), MediaType.APPLICATION_JSON));
        var result = client.recognize(new MockMultipartFile("file", "7.png", "image/png", bytes), 1);
        int offices = 0, open = 0;
        for (var room : result.path("rooms")) {
            if ("主管室".equals(room.path("label").asText()) && room.path("contourGrounded").asBoolean()) offices++;
            if ("OPEN_OFFICE".equals(room.path("roomType").asText())) {
                open++;
                assertEquals("low", room.path("confidence").asText());
                assertFalse(room.path("contourGrounded").asBoolean());
                assertTrue(room.path("polygon").size() > 4, "保留凹角和柱角");
                var polygon = new java.awt.geom.Path2D.Double();
                var ring = room.path("polygon");
                polygon.moveTo(ring.get(0).get(0).asDouble(), ring.get(0).get(1).asDouble());
                for (var point : ring) polygon.lineTo(point.get(0).asDouble(), point.get(1).asDouble());
                polygon.closePath();
                assertTrue(polygon.contains(1100, 650)); assertTrue(polygon.contains(1500, 800));
                assertFalse(polygon.contains(550, 750)); assertFalse(polygon.contains(1000, 300));
            }
        }
        assertEquals(6, offices); assertEquals(1, open);
        assertEquals(before, result.path("initialModelOutput"));
        assertEquals(11, result.path("contourRefinement").path("acceptedCount").asInt());
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        server.verify();
    }

    @Test
    void frozenLiveSemanticResponseCannotPromoteAWomensToiletStallToTheWholeRoom() throws Exception {
        properties.setRefineRoomContours(true);
        JsonNode fixture; byte[] bytes;
        try (var json = getClass().getResourceAsStream("/floorplans/semantic-labels.json");
             var input = getClass().getResourceAsStream("/floorplans/thin-partitions.png")) {
            fixture = mapper.readTree(json); bytes = input.readAllBytes();
        }
        assertEquals(fixture.path("sourceImageSha256").asText(), VisionImagePreparer.sha256(bytes));
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion(fixture.path("initialModelOutput").toString()), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withSuccess(completion(fixture.path("labelLocalization").toString()), MediaType.APPLICATION_JSON));
        var result = client.recognize(new MockMultipartFile("file", "7.png", "image/png", bytes), 1);
        var women = java.util.stream.StreamSupport.stream(result.path("rooms").spliterator(), false)
            .filter(room -> "女卫".equals(room.path("label").asText())).findFirst().orElseThrow();
        var ring = women.path("polygon"); var polygon = new java.awt.geom.Path2D.Double();
        polygon.moveTo(ring.get(0).get(0).asDouble(), ring.get(0).get(1).asDouble());
        for (var point : ring) polygon.lineTo(point.get(0).asDouble(), point.get(1).asDouble());
        polygon.closePath();
        assertTrue(polygon.contains(550, 750), "隔间部分保留");
        assertTrue(polygon.contains(620, 750), "右侧通行部分不能截掉");
        assertFalse(polygon.contains(480, 750));
        assertEquals("mid", women.path("confidence").asText());
        assertEquals(11, result.path("contourRefinement").path("acceptedCount").asInt());
        assertEquals(1, result.path("modelOutput").path("openSpaceRecovery").path("recoveredCount").asInt());
        assertEquals(fixture.path("initialModelOutput"), result.path("initialModelOutput"));
        server.verify();
    }

    @Test
    void unavailableLabelDetectionRetainsInitialRecognitionAndReportsFallback() throws Exception {
        properties.setRefineRoomContours(true);
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("{\"rooms\":[]}"), MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.max_tokens").value(4096)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var result = client.recognize(image(), 1);
        assertEquals("UNAVAILABLE", result.path("modelOutput").path("labelLocalization").path("status").asText());
        assertTrue(result.path("qualityIssues").toString().contains("房名独立定位未完成"));
        assertTrue(result.path("initialModelOutput").path("rooms").isArray());
        server.verify();
    }

    @Test
    void missingKeyFailsBeforeRequestInsteadOfReturningDemoRooms() throws Exception {
        properties.setApiKey("");
        BusinessException error = assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        assertTrue(error.getMessage().contains("DASHSCOPE_API_KEY"));
        server.verify();
    }

    @Test
    void unnamedRoomsAreKeptAndBadPolygonsAreRejected() throws Exception {
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("""
            {"rooms":[
              {"label":null,"polygon":[[0,0],[500,0],[500,500],[0,500]]},
              {"label":"越界","polygon":[[0,0],[1500,0],[0,1000]]},
              {"label":"自交","polygon":[[0,0],[1000,1000],[0,1000],[1000,0]]}
            ]}
            """), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognize(image(), 1);
        assertEquals(0, result.path("rooms").size());
        assertEquals(1, result.path("unnamedRegions").size());
        assertTrue(result.path("qualityIssues").toString().contains("INVALID_ROOM_POLYGON"));
        server.verify();
    }

    @Test
    void emptyDetectionStaysEmptyAndRetainsPreview() throws Exception {
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("{\"rooms\":[]}"), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognize(image(), 1);
        assertEquals("PARTIAL", result.path("parseStatus").asText());
        assertEquals(0, result.path("rooms").size());
        assertEquals(0, result.path("unnamedRegions").size());
        assertTrue(result.path("preview").path("pngBase64").isTextual());
        server.verify();
    }

    @Test
    void refusesInvalidJsonAndTruncatedOutputs() throws Exception {
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("not-json"), MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        server.verify();
        server.reset();
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("{\"rooms\":[]}").replace("\"stop\"", "\"length\""), MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        server.verify();
    }

    @Test
    void authenticationErrorDoesNotExposeProviderBody() throws Exception {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sk-private-debug"));
        BusinessException error = assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        assertTrue(error.getMessage().contains("密钥"));
        assertFalse(error.getMessage().contains("sk-private-debug"));
        server.verify();
    }

    @Test
    void customServiceStillReceivesMultipartFileAndPageNumber() throws Exception {
        properties.setUrl("https://example.test/recognize");
        properties.setApiKey(null);
        VisionRecognitionClient external = new VisionRecognitionClient(restTemplate, properties, mapper, client);
        server.expect(requestTo(properties.getUrl()))
            .andExpect(header(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith("multipart/form-data")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pageNumber\"")))
            .andRespond(withSuccess("{\"rooms\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(external.recognize(image(), 1).path("rooms").isArray());
        server.verify();
    }

    @Test
    void readsDimensionsAlongsideRoomsButDoesNotAutoConfirmOrTrustModelScale() throws Exception {
        server.expect(anything()).andExpect(content().string(org.hamcrest.Matchers.containsString("dimensionReferences")))
            .andRespond(withSuccess(completion("""
                {"rooms":[],"mmPerPixel":999,"dimensionReferences":[
                  {"text":"2000mm","confidence":"high","start":[0,0],"end":[1000,0]},
                  {"text":"1000mm","confidence":"high","start":[0,0],"end":[0,1000]}]}
                """), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognize(image(), 1);
        assertEquals("READY", result.path("dimensionCalibration").path("status").asText());
        assertEquals(10, result.path("dimensionCalibration").path("mmPerPixel").asDouble());
        assertFalse(result.path("unitResolution").path("confirmed").asBoolean());
        assertTrue(result.path("dimensionCalibration").path("confirmationId").isNull());
        server.verify();
    }

    @Test
    void dimensionOnlyUsesCachedStandardImageAndRequiresDimensionOutput() throws Exception {
        var image = new VisionImagePreparer(properties).prepare(image(), 1);
        server.expect(jsonPath("$.max_tokens").value(8192)).andRespond(withSuccess(completion("{\"dimensionReferences\":[]}"), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognizeDimensions(image);
        assertEquals("NO_DIMENSIONS", result.path("dimensionCalibration").path("status").asText());
        assertFalse(result.has("rooms"));
        server.verify(); server.reset();
        server.expect(jsonPath("$.max_tokens").value(8192)).andRespond(withSuccess(completion("{\"rooms\":[]}"), MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.recognizeDimensions(image));
        server.verify();
    }

    @Test
    void locatedLineEndpointsOverrideModelCoordinatesAndUnknownIdsCannotContribute() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(DimensionLineLocatorTest.drawing(), "png", bytes);
        var prepared = new VisionImagePreparer(properties).prepare(new MockMultipartFile("file", "plan.png", "image/png", bytes.toByteArray()), 1);
        server.expect(anything()).andExpect(content().string(org.hamcrest.Matchers.containsString("LINE-1")))
            .andRespond(withSuccess(completion("""
                {"dimensionReferences":[
                  {"lineId":"LINE-1","text":"8400mm","confidence":"high","start":[0,0],"end":[1,1]},
                  {"lineId":"LINE-2","text":"4400mm","confidence":"high"},
                  {"lineId":"LINE-999","text":"99999mm","confidence":"high"}]}
                """), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognizeDimensions(prepared);
        assertEquals("READY", result.path("dimensionCalibration").path("status").asText());
        assertEquals(10, result.path("dimensionCalibration").path("mmPerPixel").asDouble(), 0.05);
        assertEquals("REJECTED", result.path("dimensionCalibration").path("references").get(2).path("status").asText());
        assertEquals("MARGIN_STROKE", result.path("dimensionModelOutput").path("dimensionReferences").get(0).path("endpointSource").asText());
        server.verify();
    }

    @Test
    void cropEndpointsMapBackToStandardImageAndUnknownRegionsAreRejected() throws Exception {
        var prepared = new VisionImagePreparer(properties).prepare(image(), 1);
        server.expect(jsonPath("$.max_tokens").value(8192)).andRespond(withSuccess(completion("""
            {"dimensionReferences":[
              {"region":"BOTTOM","text":"2000mm","confidence":"high","start":[0,0],"end":[1000,0]},
              {"region":"LEFT","text":"1000mm","confidence":"high","start":[0,0],"end":[0,1000]},
              {"region":"INVALID","text":"9999mm","confidence":"high","start":[0,0],"end":[1000,1000]}]}
            """), MediaType.APPLICATION_JSON));
        JsonNode report = client.recognizeDimensions(prepared).path("dimensionCalibration");
        assertEquals("READY", report.path("status").asText());
        assertEquals(82, report.path("references").get(0).path("start").get(1).asDouble());
        assertEquals(10, report.path("mmPerPixel").asDouble());
        assertEquals("REJECTED", report.path("references").get(2).path("status").asText());
        server.verify();
    }

    @Test
    void nullModelOutputFailsAsBusinessError() throws Exception {
        server.expect(jsonPath("$.max_tokens").value(12288)).andRespond(withSuccess(completion("null"), MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        server.verify();
    }

    private String completion(String output) throws Exception {
        var response = mapper.createObjectNode().put("id", "test-request");
        response.putArray("choices").addObject().put("finish_reason", "stop")
            .putObject("message").put("role", "assistant").put("content", output);
        return response.toString();
    }

    private MockMultipartFile image() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "plan.png", "image/png", out.toByteArray());
    }
}
