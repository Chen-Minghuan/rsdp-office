package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Monotonic timings, emitted only at actual phase transitions/completions. */
public final class RecognitionProgress {
    private final long started = System.nanoTime();
    private final String startedAt = Instant.now().toString();
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private final Consumer<ObjectNode> sink;
    private final ObjectMapper json;
    private String stage = "QUEUED";
    private boolean cacheHit;
    private boolean terminal;

    public RecognitionProgress(ObjectMapper json, Consumer<ObjectNode> sink) { this.json = json; this.sink = sink; }
    public synchronized void stage(String value) { if (terminal) return; stage = value; terminal = "DONE".equals(value) || "FAILED".equals(value); publish(); }
    public synchronized void completed(String name, long since) { if (terminal) return; timings.put(name, (System.nanoTime() - since) / 1_000_000); publish(); }
    public synchronized void cacheHit(boolean hit) { cacheHit = hit; }
    public synchronized ObjectNode snapshot() {
        ObjectNode value = json.createObjectNode().put("stage", stage).put("startedAt", startedAt)
            .put("updatedAt", Instant.now().toString()).put("elapsedMs", (System.nanoTime() - started) / 1_000_000).put("cacheHit", cacheHit);
        value.set("timingsMs", json.valueToTree(timings));
        return value;
    }
    private void publish() { sink.accept(snapshot()); }
}
