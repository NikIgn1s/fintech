package dev.finflow.auth.service;

import dev.finflow.auth.api.AuthResponse;
import dev.finflow.auth.api.LoginRequest;
import dev.finflow.auth.api.RegisterRequest;
import dev.finflow.auth.api.UserMapper;
import dev.finflow.auth.api.UserResponse;
import dev.finflow.auth.domain.User;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.auth.security.JwtService;
import dev.finflow.common.error.ConflictException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final UserMapper userMapper;
    private final String timingGuardHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            RefreshTokenService refreshTokens, UserMapper userMapper) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.userMapper = userMapper;
        this.timingGuardHash = passwordEncoder.encode("timing-guard");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("email-taken", "Email already registered",
                    "A user with this email already exists");
        }
        User user = User.register(email, passwordEncoder.encode(request.password()), request.fullName());
        return userMapper.toResponse(users.save(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
        String hash = user == null ? timingGuardHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return respond(user, refreshTokens.issue(user.getId()));
    }

    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        User user = users.findById(rotation.userId()).orElseThrow(InvalidRefreshTokenException::new);
        return respond(user, rotation.refreshToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    private AuthResponse respond(User user, String refreshToken) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole());
        return AuthResponse.bearer(accessToken, refreshToken, jwtService.accessTtl().toSeconds());
    }
}
