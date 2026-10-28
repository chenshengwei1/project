package com.virco.agent.auth;

import com.virco.agent.exception.ApiException;
import com.virco.agent.service.impl.TokenService;
import com.virco.agent.service.impl.UserRecord;
import com.virco.agent.service.impl.UserRepository;

import lombok.RequiredArgsConstructor;

import com.virco.agent.entity.ChatDTO.*;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor 
public class AuthSupport {

    private final TokenService tokenService;
    private final UserRepository userRepo;


    public LoginResponse login(LoginRequest req) {
        UserRecord u = userRepo.findByUsername(req.username())
                .orElseThrow(ApiException::unauthorized);
        if (!PasswordHasher.matches(req.password(), u.passwordHash())) {
            throw ApiException.unauthorized();   // 不要区分"用户不存在"和"密码错"
        }
        String token = tokenService.issue(u.id());
        return new LoginResponse(token, u.id(), u.name());
    }

    public User requireUser(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthorized();
        }
        String userId = tokenService.verify(header.substring(7));
        if (userId == null) {
            throw ApiException.unauthorized();
        }
        return userRepo.findById(userId)
                .map(u -> new User(u.id(), u.name(), u.avatarUrl(), u.email()))
                .orElseThrow(ApiException::unauthorized);
    }

    public record User(String id, String name, String avatarUrl, String email) {

    }
}