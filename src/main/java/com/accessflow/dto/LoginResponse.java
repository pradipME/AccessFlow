package com.accessflow.dto;

import java.util.List;

/**
 * Result of a successful login.
 *
 * Reports who the caller is and what they may do. It deliberately carries no
 * password and no hash: echoing the credential back to the client would defeat
 * the point of hashing it.
 */
public record LoginResponse(
        Long id,
        String email,
        String role,
        List<String> authorities
) {

    public static LoginResponse of(Long id, String email, String role, List<String> authorities) {
        return new LoginResponse(id, email, role, authorities);
    }
}
