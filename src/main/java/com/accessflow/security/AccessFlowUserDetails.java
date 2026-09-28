package com.accessflow.security;

import java.util.Collection;
import java.util.List;

import com.accessflow.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Adapts the {@link User} entity to Spring Security's UserDetails contract.
 *
 * The username is the email address, because that is what the API
 * authenticates with. The password held here is the BCrypt hash, and
 * {@link #getPassword()} is only ever read by the authentication provider.
 *
 * The name and department are carried as well so the Thymeleaf layer can greet
 * the signed-in user without loading the entity again on every page. They are a
 * snapshot taken at authentication, so a name change shows up on the next
 * sign-in. Nothing here is a credential and none of it is ever serialised: the
 * API builds its responses from entities, not from the principal.
 */
public class AccessFlowUserDetails implements UserDetails {

    private static final String ROLE_PREFIX = "ROLE_";

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String firstName;
    private final String lastName;
    private final String department;
    private final User.Role role;
    private final boolean active;

    public AccessFlowUserDetails(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPassword();
        this.firstName = user.getFirstName();
        this.lastName = user.getLastName();
        this.department = user.getDepartment();
        this.role = user.getRole();
        this.active = user.isActive();
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getDepartment() {
        return department;
    }

    public User.Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /**
     * Inactive accounts keep their credentials but can no longer authenticate,
     * which is how employee offboarding is enforced.
     */
    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
