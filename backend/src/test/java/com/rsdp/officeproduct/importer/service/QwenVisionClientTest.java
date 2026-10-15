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

    @BeforeEach
    void setup() {
        properties = new VisionProperties();
        properties.setApiKey("test-key");
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new QwenVisionClient(restTemplate, properties, mapper,
            new VisionImagePreparer(properties), new SpatialGeometryService());
    }

    @Test
    void usesQwenAuthenticationAndSameImageForPreviewWithoutInventingPhysicalDimensions() throws Exception {
        server.expect(requestTo(properties.getBaseUrl() + "/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(jsonPath("$.model").value("qwen3-vl-plus"))
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
    void missingKeyFailsBeforeRequestInsteadOfReturningDemoRooms() throws Exception {
        properties.setApiKey("");
        BusinessException error = assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        assertTrue(error.getMessage().contains("DASHSCOPE_API_KEY"));
        server.verify();
    }

    @Test
    void unnamedRoomsAreKeptAndBadPolygonsAreRejected() throws Exception {
        server.expect(anything()).andRespond(withSuccess(completion("""
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
        server.expect(anything()).andRespond(withSuccess(completion("{\"rooms\":[]}"), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognize(image(), 1);
        assertEquals("PARTIAL", result.path("parseStatus").asText());
        assertEquals(0, result.path("rooms").size());
        assertEquals(0, result.path("unnamedRegions").size());
        assertTrue(result.path("preview").path("pngBase64").isTextual());
        server.verify();
    }

    @Test
    void refusesInvalidJsonAndTruncatedOutputs() throws Exception {
        server.expect(anything()).andRespond(withSuccess(completion("not-json"), MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.recognize(image(), 1));
        server.verify();
        server.reset();
        server.expect(anything()).andRespond(withSuccess(completion("{\"rooms\":[]}").replace("\"stop\"", "\"length\""), MediaType.APPLICATION_JSON));
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
        server.expect(anything()).andRespond(withSuccess(completion("{\"dimensionReferences\":[]}"), MediaType.APPLICATION_JSON));
        JsonNode result = client.recognizeDimensions(image);
        assertEquals("NO_DIMENSIONS", result.path("dimensionCalibration").path("status").asText());
        assertFalse(result.has("rooms"));
        server.verify(); server.reset();
        server.expect(anything()).andRespond(withSuccess(completion("{\"rooms\":[]}"), MediaType.APPLICATION_JSON));
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
        server.expect(anything()).andRespond(withSuccess(completion("""
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
        server.expect(anything()).andRespond(withSuccess(completion("null"), MediaType.APPLICATION_JSON));
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
