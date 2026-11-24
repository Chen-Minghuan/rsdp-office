package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class LocalProductDelivery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String key;
    private final List<String> workspaces;

    public LocalProductDelivery(JdbcTemplate jdbc, ObjectMapper json, @Value("${rsdp.main.base-url}") String baseUrl,
            @Value("${RSDP_OFFICE_INTAKE_KEY:}") String key,
            @Value("${RSDP_OFFICE_INTAKE_WORKSPACES:}") String workspaces) {
        this.jdbc = jdbc;
        this.json = json;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/v1/integrations/office-product-intake");
        this.key = key;
        this.workspaces = Arrays.stream(workspaces.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private record Task(String workspace, UUID batchId, String payload, int attempts) {}

    @Scheduled(fixedDelayString = "${rsdp.office-product.delivery-delay-ms:3000}")
    public void deliver() {
        if (key.length() < 32 || workspaces.isEmpty()) return;
        UUID claim = UUID.randomUUID();
        List<Task> tasks = jdbc.query("""
            with candidate as (
                select workspace_id, batch_id from office_product_outbox
                where workspace_id = any(string_to_array(?, ',')) and state in ('PENDING','SENDING')
                  and next_attempt_at <= now() order by next_attempt_at, created_at, batch_id for update skip locked limit 1
            )
            update office_product_outbox o set state = 'SENDING', attempts = attempts + 1,
                next_attempt_at = now() + interval '2 minutes', claim_id = ?
            from candidate c where o.workspace_id = c.workspace_id and o.batch_id = c.batch_id
            returning o.workspace_id, o.batch_id, o.payload::text, o.attempts
            """, (r, i) -> new Task(r.getString(1), r.getObject(2, UUID.class), r.getString(3), r.getInt(4)),
            String.join(",", workspaces), claim);
        for (Task task : tasks) {
            try {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                    .header("X-Office-Intake-Key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(task.payload())).build();
                var reply = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = reply.statusCode();
                if (status < 200 || status >= 300) {
                    retry(task, claim, "RSDP HTTP " + status, status == 400 || status == 409 || status == 413 || status == 422);
                    continue;
                }
                JsonNode response = json.readTree(reply.body());
                String reviewId = response == null ? "" : response.path("data").path("id").asText();
                if (response == null || response.path("code").asInt() != 200 || reviewId.isBlank()) {
                    retry(task, claim, "RSDP 未返回审核单回执", false);
                    continue;
                }
                jdbc.update("""
                    update office_product_outbox set state = 'DELIVERED', review_id = ?, payload = null,
                        claim_id = null, last_error = null
                    where workspace_id = ? and batch_id = ? and claim_id = ?
                    """, reviewId, task.workspace(), task.batchId(), claim);
            } catch (IOException e) {
                retry(task, claim, "无法连接 RSDP 或回执无法读取", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void retry(Task task, UUID claim, String reason, boolean permanent) {
        int delaySeconds = Math.min(900, 5 * (1 << Math.min(task.attempts(), 8)));
        jdbc.update("""
            update office_product_outbox set state = ?, last_error = ?, claim_id = null,
                next_attempt_at = now() + make_interval(secs => ?)
            where workspace_id = ? and batch_id = ? and claim_id = ?
            """, permanent ? "FAILED" : "PENDING", reason, delaySeconds, task.workspace(), task.batchId(), claim);
        log.warn("Office 商品批次 {} 投递{}，{}", task.batchId(), permanent ? "需要处理" : "将重试", reason);
    }
}
