package com.virco.agent.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    public static String hash(String raw) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            var spec = new PBEKeySpec(raw.toCharArray(), salt, 65536, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(key);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static boolean matches(String raw, String stored) {
        String[] p = stored.split(":");
        byte[] salt = Base64.getDecoder().decode(p[0]);
        byte[] expect = Base64.getDecoder().decode(p[1]);
        try {
            var spec = new PBEKeySpec(raw.toCharArray(), salt, 65536, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expect);
        } catch (Exception e) { return false; }
    }
}