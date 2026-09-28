package com.accessflow.dto;

import com.accessflow.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(

        @NotBlank(message = "employeeId is required")
        @Size(max = 50, message = "employeeId must not exceed 50 characters")
        String employeeId,

        @NotBlank(message = "firstName is required")
        @Size(max = 100, message = "firstName must not exceed 100 characters")
        String firstName,

        @NotBlank(message = "lastName is required")
        @Size(max = 100, message = "lastName must not exceed 100 characters")
        String lastName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a well-formed email address")
        @Size(max = 255, message = "email must not exceed 255 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 255, message = "password must be between 8 and 255 characters")
        String password,

        @NotNull(message = "role is required")
        User.Role role,

        @Size(max = 100, message = "department must not exceed 100 characters")
        String department
) {
}
