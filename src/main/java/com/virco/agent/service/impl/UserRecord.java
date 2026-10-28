package com.virco.agent.service.impl;
/**
 * UserRecord
 * @param id
 * @param username
 * @param passwordHash
 * @param name
 * @param avatarUrl
 * @param email
 */
public record UserRecord(String id, String username, String passwordHash,
                String name, String avatarUrl, String email) {}