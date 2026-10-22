package by.bsuir.adforecast.user;

import java.time.LocalDateTime;

public record UserResponse(Long id, String login, String email, String fullName, String role, Boolean isActive, LocalDateTime lastLogin) {
}
