package com.erp.auth.service;

import com.erp.auth.dto.*;
import com.erp.auth.model.*;
import com.erp.auth.exception.AuthException;
import com.erp.auth.repository.RefreshTokenRepository;
import com.erp.auth.repository.UserRepository;
import com.erp.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${jwt.refresh-expiry-ms}")
    private long refreshExpiryMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthException(
                    "An account with this email already exists",
                    HttpStatus.CONFLICT
            );
        }

        User user = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(Role.USER))
                .build();

        User savedUser = userRepository.save(user);
        log.info("New user registered: id={}, email={}", savedUser.getId(), savedUser.getEmail());

        return buildAuthResponse(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AuthException(
                        "Invalid email or password",
                        HttpStatus.UNAUTHORIZED
                ));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AuthException(
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED
            );
        }

        refreshTokenRepository.revokeAllUserTokens(user);

        log.info("User logged in: id={}", user.getId());
         return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {

        RefreshToken stored = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new AuthException(
                        "Refresh token not found",
                        HttpStatus.UNAUTHORIZED
                ));

        if (stored.isRevoked()) {
            throw new AuthException(
                    "Refresh token has been revoked",
                    HttpStatus.UNAUTHORIZED
            );
        }

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException(
                    "Refresh token has expired. Please log in again.",
                    HttpStatus.UNAUTHORIZED
            );
        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return buildAuthResponse(stored.getUser());
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String rawRefreshToken = jwtUtil.generateRefreshToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .token(rawRefreshToken)
                .user(user)
                .expiresAt(LocalDateTime.now()
                        .plusNanos(refreshExpiryMs * 1_000_000L))
                .build();

        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(refreshExpiryMs)
                .userId(user.getId())
                .email(user.getEmail())
                .roles(user.getRoles().stream()
                        .map(Enum::name)
                        .collect(Collectors.toSet()))
                .build();
    }
}