package com.rsdp.officeproduct.importer.auth;

import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录图形验证码：一次性、5 分钟过期，保存在单实例内存中。
 */
@Component
public class CaptchaService {

    private static final long TTL_SECONDS = 300;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String create() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) code.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        String id = UUID.randomUUID().toString().replace("-", "");
        entries.put(id, new Entry(code.toString(), Instant.now().getEpochSecond() + TTL_SECONDS));
        return id;
    }

    public boolean verify(String id, String code) {
        if (id == null || code == null) return false;
        Entry entry = entries.remove(id);
        if (entry == null || entry.expiresAtEpoch < Instant.now().getEpochSecond()) return false;
        return entry.code.equalsIgnoreCase(code.trim());
    }

    public BufferedImage render(String id) {
        Entry entry = entries.get(id);
        String code = entry == null ? "????" : entry.code;
        int width = 120;
        int height = 44;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 241, 235));
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < 12; i++) {
                g.setColor(new Color(180 + RANDOM.nextInt(60), 160 + RANDOM.nextInt(60), 140 + RANDOM.nextInt(60)));
                g.drawLine(RANDOM.nextInt(width), RANDOM.nextInt(height), RANDOM.nextInt(width), RANDOM.nextInt(height));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(60 + RANDOM.nextInt(90), 50 + RANDOM.nextInt(70), 40 + RANDOM.nextInt(60)));
                g.rotate(Math.toRadians(RANDOM.nextInt(30) - 15), 22 + i * 26, 30);
                g.drawString(String.valueOf(code.charAt(i)), 12 + i * 26, 30);
                g.rotate(0, 22 + i * 26, 30);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private record Entry(String code, long expiresAtEpoch) {}
}
