package com.accessflow.controller;

import java.util.List;

import com.accessflow.dto.UserCreateRequest;
import com.accessflow.dto.UserResponse;
import com.accessflow.entity.User;
import com.accessflow.security.AccessFlowUserDetails;
import com.accessflow.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST API for users.
 *
 * Talks to UserService only; UserRepository is never touched here. Entities are
 * mapped to UserResponse on the way out, and UserResponse has no password field,
 * so no hash can be serialised into a response.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request,
                                                   Authentication authentication,
                                                   UriComponentsBuilder uriBuilder) {
        User created = userService.createUser(toEntity(request, resolveRole(request, authentication)));

        return ResponseEntity
                .created(uriBuilder.path("/api/users/{id}").buildAndExpand(created.getId()).toUri())
                .body(UserResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers().stream()
                .map(UserResponse::from)
                .toList();

        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(UserResponse.from(userService.getUserById(id)));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<UserResponse> getUserByEmployeeId(@PathVariable String employeeId) {
        return ResponseEntity.ok(UserResponse.from(userService.getUserByEmployeeId(employeeId)));
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserResponse> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(UserResponse.from(userService.getUserByEmail(email)));
    }

    /**
     * Registration is public, so the role in the request body cannot be trusted:
     * accepting it verbatim would let anyone self-register as SUPER_ADMIN. Only a
     * SUPER_ADMIN may assign a role, and everyone else lands on EMPLOYEE.
     *
     * Reads the principal off the Authentication rather than off a Principal
     * parameter, because a Principal parameter hands back the Authentication token
     * itself, not the UserDetails it wraps. The instanceof also has to cope with the
     * anonymous token Spring Security substitutes on a public request, whose
     * principal is the String "anonymousUser".
     */
    private User.Role resolveRole(UserCreateRequest request, Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AccessFlowUserDetails details
                && details.getRole() == User.Role.SUPER_ADMIN) {
            return request.role();
        }
        return User.Role.EMPLOYEE;
    }

    /**
     * Explicit and deliberately narrow: every field is copied by hand so a new
     * column on the entity is a visible code change rather than an accidental
     * leak into the API. The password is set separately by UserService, which
     * hashes it.
     */
    private User toEntity(UserCreateRequest request, User.Role role) {
        User user = new User();
        user.setEmployeeId(request.employeeId());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setPassword(request.password());
        user.setRole(role);
        user.setDepartment(request.department());
        user.setActive(true);
        return user;
    }
}
