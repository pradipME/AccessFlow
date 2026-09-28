package com.accessflow.dto;

import java.time.LocalDateTime;

import com.accessflow.entity.User;

/**
 * Outbound representation of a user.
 *
 * There is no password field, and there must never be one: this record is
 * serialised straight into an HTTP response.
 */
public record UserResponse(
        Long id,
        String employeeId,
        String firstName,
        String lastName,
        String email,
        User.Role role,
        String department,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmployeeId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getRole(),
                user.getDepartment(),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
