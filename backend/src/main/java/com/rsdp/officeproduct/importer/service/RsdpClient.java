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

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 主项目（RSDP）接口客户端。
 *
 * <p>主项目登录成功通过 HttpOnly Cookie 下发 JWT（rsdp_token），响应体不再携带 token；
 * 本客户端登录后缓存该 Cookie，后续请求以 Cookie 头回带，401 时自动重登并重试一次。
 * 用于读取主项目目录、字典和历史导入记录。
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
     * @param path 以 /api/v1 开头的接口路径（查询值需编码）
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

    private BusinessException unavailable(Exception e, String path) {
        log.error("调用主项目失败: {}", path, e);
        return new BusinessException("主项目数据服务不可用，请确认 RSDP 后端已启动");
    }

    /** 主项目前端地址（图片等资源的绝对路径拼接用）。 */
    public String baseUrl() {
        ensureConfigured();
        return properties.getBaseUrl().replaceAll("/+$", "");
    }

    String catalogFactoryCode() {
        return properties.getCatalogFactoryCode();
    }

    /**
     * 调用主项目 GET 接口，返回可由本服务重新发送的二进制响应。
     *
     * @param path 以 /api/v1 开头的接口路径
     * @return 上游状态码、内容类型和字节流
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
        ResponseEntity<byte[]> upstream = restTemplate.exchange(
            baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        HttpHeaders responseHeaders = new HttpHeaders();
        MediaType contentType = upstream.getHeaders().getContentType();
        if (contentType != null) responseHeaders.setContentType(contentType);
        String cacheControl = upstream.getHeaders().getCacheControl();
        if (cacheControl != null) responseHeaders.setCacheControl(cacheControl);
        // 上游分块已被 HTTP 客户端解包，出站响应由本服务重新编码。
        return new ResponseEntity<>(upstream.getBody(), responseHeaders, upstream.getStatusCode());
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
            URI.create(baseUrl() + path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode root;
        try {
            root = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new BusinessException("主项目接口响应解析失败");
        }
        int code = root.path("code").asInt(-1);
        if (code != 200) {
            throw new BusinessException(code >= 400 && code < 600 ? code : 400,
                    "主项目接口返回错误: " + root.path("message").asText("未知错误"));
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
        return new BusinessException(e.getStatusCode().value(), "主项目接口返回错误: " + message);
    }
}
