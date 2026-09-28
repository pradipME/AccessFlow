package com.accessflow.web;

import com.accessflow.entity.User;
import com.accessflow.security.AccessFlowUserDetails;
import com.accessflow.service.AccessRequestService;

/**
 * The signed-in user as the templates need to see them.
 *
 * Built from the {@link AccessFlowUserDetails} on the authentication, never from
 * anything the browser sent, so a page cannot present itself as another employee.
 * There is deliberately no password field: the BCrypt hash reaches no view, and a
 * record without such a component cannot accidentally serialise one.
 *
 * Lives in the web layer rather than in the dto package because it is a view
 * model, not part of the API contract.
 */
public record CurrentUser(
        Long id,
        String email,
        String firstName,
        String lastName,
        String department,
        User.Role role,
        boolean reviewer
) {

    public static CurrentUser from(AccessFlowUserDetails details) {
        return new CurrentUser(
                details.getId(),
                details.getUsername(),
                details.getFirstName(),
                details.getLastName(),
                details.getDepartment(),
                details.getRole(),
                AccessRequestService.isReviewerRole(details.getRole())
        );
    }

    public String displayName() {
        return firstName + " " + lastName;
    }
}
