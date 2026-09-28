package com.accessflow;

import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1B - the derived repository lookups against the real MySQL table.
 *
 * AssertJ rather than the {@code assert} keyword, as in the other Phase 1 tests:
 * a bare {@code assert} is only evaluated when the JVM runs with {@code -ea}, so
 * it can silently stop asserting without the build ever reporting a problem.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User saved;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmployeeId("EMP-9001");
        user.setFirstName("Ravi");
        user.setLastName("Deshmukh");
        user.setEmail("ravi.deshmukh@accessflow.local");
        user.setPassword("placeholder-not-a-real-hash");
        user.setRole(User.Role.MANAGER);
        user.setDepartment("Finance");
        user.setActive(true);

        saved = userRepository.save(user);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("save() persists the user and assigns the generated id")
    void saveAssignsGeneratedId() {
        assertThat(saved.getId()).as("save() must return a persisted entity with an id").isNotNull();
    }

    @Test
    @DisplayName("findByEmployeeId returns the saved user")
    void findByEmployeeIdReturnsSavedUser() {
        User found = userRepository.findByEmployeeId("EMP-9001")
                .orElseThrow(() -> new AssertionError("findByEmployeeId must return the saved user"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmployeeId()).isEqualTo("EMP-9001");
        assertThat(found.getEmail()).isEqualTo("ravi.deshmukh@accessflow.local");
        assertThat(found.getFirstName()).isEqualTo("Ravi");
        assertThat(found.getLastName()).isEqualTo("Deshmukh");
        assertThat(found.getRole()).isEqualTo(User.Role.MANAGER);
        assertThat(found.getDepartment()).isEqualTo("Finance");
        assertThat(found.isActive()).isTrue();
    }

    @Test
    @DisplayName("findByEmail returns the saved user")
    void findByEmailReturnsSavedUser() {
        User found = userRepository.findByEmail("ravi.deshmukh@accessflow.local")
                .orElseThrow(() -> new AssertionError("findByEmail must return the saved user"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmployeeId()).isEqualTo("EMP-9001");
    }

    @Test
    @DisplayName("derived lookups return empty for unknown values")
    void unknownLookupsReturnEmpty() {
        assertThat(userRepository.findByEmployeeId("EMP-DOES-NOT-EXIST")).isEmpty();
        assertThat(userRepository.findByEmail("nobody@accessflow.local")).isEmpty();
        assertThat(userRepository.existsByEmployeeId("EMP-DOES-NOT-EXIST")).isFalse();
        assertThat(userRepository.existsByEmail("nobody@accessflow.local")).isFalse();
    }

    @Test
    @DisplayName("existsByEmployeeId and existsByEmail detect the saved user")
    void existenceChecksFindSavedUser() {
        assertThat(userRepository.existsByEmployeeId("EMP-9001")).isTrue();
        assertThat(userRepository.existsByEmail("ravi.deshmukh@accessflow.local")).isTrue();
    }
}
