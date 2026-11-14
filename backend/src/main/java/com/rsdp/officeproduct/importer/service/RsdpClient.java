package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 主项目（RSDP）接口客户端。
 *
 * <p>主项目登录成功通过 HttpOnly Cookie 下发 JWT（rsdp_token），响应体不再携带 token；
 * 本客户端登录后缓存该 Cookie，后续请求以 Cookie 头回带，401 时自动重登并重试一次。
 * 读：产品列表/详情/变体/报价/字典；写：office 客户的商品录入/导入转发（落主库、归属本厂）。
 * 可见性上限由主项目服务端按服务账号身份控制。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RsdpClient {

    private static final String COOKIE_NAME = "rsdp_token";
    private static final int GET_NETWORK_MAX_ATTEMPTS = 3;

    private final RestTemplate restTemplate;
    private final RsdpMainProperties properties;
    private final ObjectMapper objectMapper;

    /** 缓存的会话 Cookie 值（仅 token 本体，不含属性段）。 */
    private volatile String sessionCookie;

    /**
     * 调用主项目 GET 接口，返回 Result.data 节点。
     *
     * @param path 以 /api/v1 开头的接口路径（可带 query）
     * @return data 节点
     */
    public JsonNode get(String path) {
        ensureConfigured();
        int networkAttempt = 1;
        boolean renewedSession = false;
        while (true) {
            try {
                return doGet(path);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 401 && !renewedSession) {
                    renewedSession = true;
                    log.info("主项目会话失效，重新登录后重试: {}", path);
                    login();
                    continue;
                }
                throw translateError(path, e);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof ResourceAccessException && networkAttempt < GET_NETWORK_MAX_ATTEMPTS) {
                    log.warn("主项目 GET 瞬时网络失败，进行第 {} 次重试: {}", networkAttempt, path);
                    networkBackoff(networkAttempt++);
                    continue;
                }
                throw unavailable(e, path);
            }
        }
    }

    /** 仅幂等 GET 使用：100ms、200ms 短退避；中断时不继续请求。 */
    private void networkBackoff(int attempt) {
        try {
            Thread.sleep(attempt * 100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("主项目请求已中断", e);
        }
    }

    /** 调用主项目 POST 接口（JSON 体），返回 Result.data 节点。 */
    public JsonNode postJson(String path, Object body) {
        return executeWithRetry(() -> {
            HttpHeaders headers = sessionHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.postForEntity(baseUrl() + path,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        }, path);
    }

    /** 调用主项目 PUT 接口（JSON 体），返回 Result.data 节点。 */
    public JsonNode putJson(String path, Object body) {
        return executeWithRetry(() -> {
            HttpHeaders headers = sessionHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.exchange(baseUrl() + path, org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        }, path);
    }

    /** 调用主项目 multipart POST 接口（request JSON 部件 + images 文件部件），返回 Result.data 节点。 */
    public JsonNode postMultipart(String path, Object jsonBody, List<ImagePart> images) {
        return executeWithRetry(() -> {
            var multipart = new org.springframework.util.LinkedMultiValueMap<String, Object>();
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
            multipart.add("request", new HttpEntity<>(objectMapper.writeValueAsString(jsonBody), jsonHeaders));
            for (ImagePart image : images) {
                HttpHeaders fileHeaders = new HttpHeaders();
                fileHeaders.setContentType(mediaTypeOf(image.filename()));
                fileHeaders.setContentDispositionFormData("images", image.filename());
                multipart.add("images", new HttpEntity<>(image.content(), fileHeaders));
            }
            HttpHeaders headers = sessionHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(multipart, headers), String.class);
        }, path);
    }

    /** 主项目服务账号关联的本厂编码（/auth/me 的 factoryCodes 第一个）。 */
    public String mainFactoryCode() {
        JsonNode me = get("/api/v1/auth/me");
        JsonNode codes = me.path("factoryCodes");
        if (!codes.isArray() || codes.isEmpty()) {
            throw new BusinessException("主项目服务账号未关联工厂，无法录入商品");
        }
        return codes.get(0).asText();
    }

    /** 待上传的图片部件。 */
    public record ImagePart(String filename, byte[] content) {}

    private HttpHeaders sessionHeaders() {
        if (sessionCookie == null) {
            login();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + sessionCookie);
        return headers;
    }

    private JsonNode executeWithRetry(CheckedRequest request, String path) {
        ensureConfigured();
        try {
            return readData(request.execute(), path);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.info("主项目会话失效，重新登录后重试: {}", path);
                login();
                try {
                    return readData(request.execute(), path);
                } catch (RestClientResponseException retryError) {
                    throw translateError(path, retryError);
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception retryException) {
                    throw unavailable(retryException, path);
                }
            }
            throw translateError(path, e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e, path);
        }
    }

    private JsonNode readData(ResponseEntity<String> response, String path) {
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                throw new BusinessException("主项目接口返回错误: " + root.path("message").asText("未知错误"));
            }
            return root.path("data");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("主项目接口响应解析失败: " + path);
        }
    }

    private BusinessException unavailable(Exception e, String path) {
        log.error("调用主项目失败: {}", path, e);
        return new BusinessException("主项目数据服务不可用，请确认 RSDP 后端已启动");
    }

    private interface CheckedRequest {
        ResponseEntity<String> execute() throws Exception;
    }

    /** 按扩展名推导图片 Content-Type（主项目按部件类型校验，octet-stream 会被拒）。 */
    private MediaType mediaTypeOf(String filename) {
        String ext = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.IMAGE_JPEG;
        };
    }

    /** 主项目前端地址（图片等资源的绝对路径拼接用）。 */
    public String baseUrl() {
        ensureConfigured();
        return properties.getBaseUrl().replaceAll("/+$", "");
    }

    /**
     * 调用主项目 GET 接口并透传二进制响应（图片代理用）。
     *
     * @param path 以 /api/v1 开头的接口路径
     * @return 主项目原始响应（状态码/内容类型/字节流）
     */
    public ResponseEntity<byte[]> getBinary(String path) {
        ensureConfigured();
        try {
            return doGetBinary(path);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                login();
                return doGetBinary(path);
            }
            throw translateError(path, e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用主项目失败: {}", path, e);
            throw new BusinessException("主项目数据服务不可用，请确认 RSDP 后端已启动");
        }
    }

    private ResponseEntity<byte[]> doGetBinary(String path) {
        if (sessionCookie == null) {
            login();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + sessionCookie);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new BusinessException("未配置主项目连接（rsdp.main.base-url/username/password），请联系管理员");
        }
    }

    private JsonNode doGet(String path) {
        if (sessionCookie == null) {
            login();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + sessionCookie);
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode root;
        try {
            root = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new BusinessException("主项目接口响应解析失败");
        }
        int code = root.path("code").asInt(-1);
        if (code != 200) {
            throw new BusinessException("主项目接口返回错误: " + root.path("message").asText("未知错误"));
        }
        return root.path("data");
    }

    /** 登录主项目并缓存会话 Cookie（同步防止并发重登）。 */
    private synchronized void login() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
            "username", properties.getUsername(),
            "password", properties.getPassword());
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);
            sessionCookie = extractSessionCookie(response.getHeaders());
            if (sessionCookie == null) {
                throw new BusinessException("主项目登录未返回会话 Cookie");
            }
            log.info("主项目服务账号登录成功: {}", properties.getUsername());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.error("主项目登录失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException("主项目服务账号登录失败，请检查 rsdp.main 配置与账号状态");
        } catch (Exception e) {
            log.error("主项目登录异常", e);
            throw new BusinessException("主项目数据服务不可用，请确认 RSDP 后端已启动");
        }
    }

    /** 从 Set-Cookie 头中提取 rsdp_token 的 cookie 值。 */
    private String extractSessionCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (String setCookie : setCookies) {
            if (setCookie != null && setCookie.startsWith(COOKIE_NAME + "=")) {
                String pair = setCookie.split(";", 2)[0];
                return pair.substring(COOKIE_NAME.length() + 1);
            }
        }
        return null;
    }

    private BusinessException translateError(String path, RestClientResponseException e) {
        String message = e.getResponseBodyAsString();
        try {
            JsonNode root = objectMapper.readTree(message);
            message = root.path("message").asText(message);
        } catch (Exception ignored) {
            // 非 JSON 错误体，原样使用
        }
        log.warn("主项目接口错误: {} status={}, body={}", path, e.getStatusCode(), message);
        return new BusinessException("主项目接口返回错误: " + message);
    }
}
