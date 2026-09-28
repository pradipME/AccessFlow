package com.accessflow.controller;

import java.util.List;

import com.accessflow.dto.LoginRequest;
import com.accessflow.dto.LoginResponse;
import com.accessflow.exception.InvalidCredentialsException;
import com.accessflow.security.AccessFlowUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login endpoint.
 *
 * HTTP Basic remains the transport for the stateless API, but an explicit
 * login route exists so a client can verify credentials up front and receive a
 * structured error body on failure instead of a bare 401 from the filter.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (AuthenticationException ex) {
            // One generic message for every failure mode. Distinguishing
            // "unknown email" from "wrong password" would confirm which
            // addresses are registered.
            throw new InvalidCredentialsException();
        }

        AccessFlowUserDetails principal = (AccessFlowUserDetails) authentication.getPrincipal();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return ResponseEntity.status(HttpStatus.OK)
                .body(LoginResponse.of(principal.getId(), principal.getUsername(),
                        principal.getRole().name(), authorities));
    }
}
