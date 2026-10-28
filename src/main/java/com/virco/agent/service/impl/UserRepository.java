package com.virco.agent.service.impl;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public interface UserRepository {
    Optional<UserRecord> findByUsername(String username);
    Optional<UserRecord> findById(String id);
    UserRecord save(UserRecord user);
}

