package dev.finflow.auth.service;

import dev.finflow.auth.api.UserMapper;
import dev.finflow.auth.api.UserResponse;
import dev.finflow.auth.repository.UserRepository;
import dev.finflow.common.error.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return users.findById(id)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
