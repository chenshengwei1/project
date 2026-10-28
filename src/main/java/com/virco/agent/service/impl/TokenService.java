package com.virco.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.virco.agent.config.AppProperties;

@Component
public class TokenService {
    private final SecretKey key;
    private final long ttlMillis;
    private final String devToken;

    public TokenService(AppProperties props) {
        this.key = new SecretKeySpec(props.getAuth().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.ttlMillis = props.getAuth().getTokenTtlMinutes() * 60_000L;
        this.devToken = props.getAuth().getToken();
    }

    /** 生成 token：userId.expiry.signature，Base64URL 安全 */
    public String issue(String userId) {
        long expiry = System.currentTimeMillis() + ttlMillis;
        String payload = userId + "." + expiry;
        return payload + "." + sign(payload);
    }

    /** 校验 token：签名对 + 未过期 → 返回 userId；否则 null */
    public String verify(String token) {
        if (devToken != null && !devToken.isBlank() && devToken.equals(token)) {
            return "u1";   // 开发直通
        }
        String[] p = token.split("\\.");
        if (p.length != 3) return null;
        if (!MessageDigest.isEqual(
                sign(p[0] + "." + p[1]).getBytes(StandardCharsets.UTF_8),
                p[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        if (System.currentTimeMillis() > Long.parseLong(p[1])) return null;
        return p[0];
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}