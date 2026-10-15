package dev.finflow.auth.api;

import dev.finflow.auth.domain.User;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserResponse toResponse(User user);
}
