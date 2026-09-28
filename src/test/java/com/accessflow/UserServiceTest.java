package com.accessflow;

import com.accessflow.entity.User;
import com.accessflow.exception.DuplicateUserException;
import com.accessflow.exception.UserNotFoundException;
import com.accessflow.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
@Transactional
class UserServiceTest {

    private static final String EMPLOYEE_ID = "EMP-5001";
    private static final String EMAIL = "anita.sharma@accessflow.local";

    @Autowired
    private UserService userService;

    private User newUser(String employeeId, String email) {
        User user = new User();
        user.setEmployeeId(employeeId);
        user.setFirstName("Anita");
        user.setLastName("Sharma");
        user.setEmail(email);
        user.setPassword("placeholder-not-a-real-hash");
        user.setRole(User.Role.EMPLOYEE);
        user.setDepartment("Engineering");
        user.setActive(true);
        return user;
    }

    @Test
    @DisplayName("createUser persists the user and returns it with a generated id")
    void createUserPersistsUser() {
        User created = userService.createUser(newUser(EMPLOYEE_ID, EMAIL));

        assertNotNull(created.getId(), "createUser must return a persisted user with an id");
        assertTrue(userService.existsByEmployeeId(EMPLOYEE_ID));
        assertTrue(userService.existsByEmail(EMAIL));
    }

    @Test
    @DisplayName("getUserById returns the created user")
    void getUserByIdReturnsCreatedUser() {
        Long id = userService.createUser(newUser(EMPLOYEE_ID, EMAIL)).getId();

        User found = userService.getUserById(id);

        assertEquals(id, found.getId());
        assertEquals(EMPLOYEE_ID, found.getEmployeeId());
        assertEquals(EMAIL, found.getEmail());
    }

    @Test
    @DisplayName("getUserByEmployeeId returns the created user")
    void getUserByEmployeeIdReturnsCreatedUser() {
        userService.createUser(newUser(EMPLOYEE_ID, EMAIL));

        User found = userService.getUserByEmployeeId(EMPLOYEE_ID);

        assertEquals(EMPLOYEE_ID, found.getEmployeeId());
        assertEquals(EMAIL, found.getEmail());
    }

    @Test
    @DisplayName("getUserByEmail returns the created user")
    void getUserByEmailReturnsCreatedUser() {
        userService.createUser(newUser(EMPLOYEE_ID, EMAIL));

        User found = userService.getUserByEmail(EMAIL);

        assertEquals(EMAIL, found.getEmail());
        assertEquals(EMPLOYEE_ID, found.getEmployeeId());
    }

    @Test
    @DisplayName("createUser rejects a duplicate employeeId without overwriting the original")
    void duplicateEmployeeIdIsRejected() {
        User original = userService.createUser(newUser(EMPLOYEE_ID, EMAIL));

        User candidate = newUser(EMPLOYEE_ID, "someone.else@accessflow.local");
        candidate.setLastName("Different");

        DuplicateUserException thrown = assertThrows(DuplicateUserException.class,
                () -> userService.createUser(candidate));

        assertEquals("employeeId", thrown.getField());
        assertEquals(EMPLOYEE_ID, thrown.getValue());

        User reloaded = userService.getUserById(original.getId());
        assertEquals("Sharma", reloaded.getLastName(), "the original user must not be modified");
        assertEquals(EMAIL, reloaded.getEmail(), "the original email must not be overwritten");
    }

    @Test
    @DisplayName("createUser rejects a duplicate email without overwriting the original")
    void duplicateEmailIsRejected() {
        User original = userService.createUser(newUser(EMPLOYEE_ID, EMAIL));

        User candidate = newUser("EMP-5002", EMAIL);
        candidate.setLastName("Different");

        DuplicateUserException thrown = assertThrows(DuplicateUserException.class,
                () -> userService.createUser(candidate));

        assertEquals("email", thrown.getField());
        assertEquals(EMAIL, thrown.getValue());

        User reloaded = userService.getUserById(original.getId());
        assertEquals(EMPLOYEE_ID, reloaded.getEmployeeId(), "the original employeeId must not be modified");
        assertEquals("Sharma", reloaded.getLastName());
    }

    @Test
    @DisplayName("getUserById throws UserNotFoundException for an unknown id")
    void unknownIdThrows() {
        UserNotFoundException thrown = assertThrows(UserNotFoundException.class,
                () -> userService.getUserById(9_999_999L));

        assertTrue(thrown.getMessage().contains("9999999"), thrown.getMessage());
    }

    @Test
    @DisplayName("getUserByEmployeeId throws UserNotFoundException for an unknown employeeId")
    void unknownEmployeeIdThrows() {
        UserNotFoundException thrown = assertThrows(UserNotFoundException.class,
                () -> userService.getUserByEmployeeId("EMP-NOT-HERE"));

        assertTrue(thrown.getMessage().contains("EMP-NOT-HERE"), thrown.getMessage());
    }

    @Test
    @DisplayName("getUserByEmail throws UserNotFoundException for an unknown email")
    void unknownEmailThrows() {
        UserNotFoundException thrown = assertThrows(UserNotFoundException.class,
                () -> userService.getUserByEmail("ghost@accessflow.local"));

        assertTrue(thrown.getMessage().contains("ghost@accessflow.local"), thrown.getMessage());
    }

    @Test
    @DisplayName("existence checks return false for unknown values")
    void existenceChecksReturnFalseForUnknownValues() {
        assertFalse(userService.existsByEmployeeId("EMP-NOT-HERE"));
        assertFalse(userService.existsByEmail("ghost@accessflow.local"));
    }
}
