package com.accessflow;

import java.time.LocalDateTime;

import com.accessflow.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1B - the User entity against the real MySQL table.
 *
 * The assertions are AssertJ rather than the {@code assert} keyword on purpose.
 * A bare {@code assert} is a JVM language feature that is only evaluated when the
 * JVM is started with {@code -ea}, so these checks would silently pass under a
 * runner or an IDE launch configuration that does not enable them, and they
 * report nothing useful when they do fail. This is the entity-mapping test for
 * the table every other test in the suite depends on, so it is worth making sure
 * it cannot become a test that asserts nothing.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
@Transactional
class UserEntityMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Hibernate creates the users table from the User entity and can round-trip a row")
    void userEntityMapsToUsersTable() {
        User user = new User();
        user.setEmployeeId("EMP-0001");
        user.setFirstName("Asha");
        user.setLastName("Patil");
        user.setEmail("asha.patil@accessflow.local");
        user.setPassword("placeholder-not-a-real-hash");
        user.setRole(User.Role.EMPLOYEE);
        user.setDepartment("Engineering");
        user.setActive(true);

        entityManager.persist(user);
        entityManager.flush();

        Long generatedId = user.getId();
        assertThat(generatedId).as("IDENTITY generation must assign the id").isNotNull();

        assertThat(user.getCreatedAt()).as("@CreationTimestamp must be populated").isNotNull();
        assertThat(user.getUpdatedAt()).as("@UpdateTimestamp must be populated").isNotNull();
        assertThat(user.getRole()).as("role must default to EMPLOYEE").isEqualTo(User.Role.EMPLOYEE);
        assertThat(user.isActive()).as("active must default to true").isTrue();

        entityManager.clear();

        User reloaded = entityManager.find(User.class, generatedId);
        assertThat(reloaded).as("row must be readable back from MySQL").isNotNull();
        assertThat(reloaded.getEmployeeId()).isEqualTo("EMP-0001");
        assertThat(reloaded.getEmail()).isEqualTo("asha.patil@accessflow.local");
        assertThat(reloaded.getLastName()).isEqualTo("Patil");
        assertThat(reloaded.getRole()).isEqualTo(User.Role.EMPLOYEE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        LocalDateTime originalUpdatedAt = reloaded.getUpdatedAt();

        reloaded.setLastName("Patil-Sharma");
        reloaded.setActive(false);
        entityManager.flush();
        entityManager.clear();

        User updated = entityManager.find(User.class, generatedId);
        assertThat(updated.getLastName()).isEqualTo("Patil-Sharma");
        assertThat(updated.isActive()).as("update must be persisted").isFalse();
        assertThat(updated.getUpdatedAt())
                .as("@UpdateTimestamp must advance on update")
                .isAfter(originalUpdatedAt);
    }

    @Test
    @DisplayName("equals and hashCode are driven by the generated id and survive a reload")
    void identityIsBasedOnId() {
        User user = new User();
        user.setEmployeeId("EMP-7001");
        user.setFirstName("Nikhil");
        user.setLastName("Rao");
        user.setEmail("nikhil.rao@accessflow.local");
        user.setPassword("placeholder-not-a-real-hash");
        user.setRole(User.Role.EMPLOYEE);
        user.setDepartment("Engineering");
        user.setActive(true);

        entityManager.persist(user);
        entityManager.flush();

        Long id = user.getId();
        entityManager.clear();

        User reloaded = entityManager.find(User.class, id);
        assertThat(user).as("the same row loaded twice must be equal").isEqualTo(reloaded);
        assertThat(reloaded).isEqualTo(user);
        assertThat(user.hashCode())
                .as("hashCode must be stable across a reload")
                .isEqualTo(reloaded.hashCode());

        User other = new User();
        other.setEmployeeId("EMP-7002");
        other.setFirstName("Nikhil");
        other.setLastName("Rao");
        other.setEmail("nikhil.rao2@accessflow.local");
        other.setPassword("placeholder-not-a-real-hash");
        other.setRole(User.Role.EMPLOYEE);
        other.setDepartment("Engineering");
        other.setActive(true);

        assertThat(user).as("different rows must not be equal").isNotEqualTo(other);
        assertThat(user.equals(null)).as("equals must reject null").isFalse();
        assertThat(user.equals("not a user")).as("equals must reject a foreign type").isFalse();
    }

    @Test
    @DisplayName("toString never exposes the password")
    void toStringOmitsPassword() {
        User user = new User();
        user.setEmployeeId("EMP-7003");
        user.setFirstName("Sara");
        user.setLastName("Khan");
        user.setEmail("sara.khan@accessflow.local");
        user.setPassword("super-secret-hash-value");
        user.setRole(User.Role.EMPLOYEE);
        user.setDepartment("Engineering");
        user.setActive(true);

        String text = user.toString();

        assertThat(text).as("toString must not leak the password")
                .doesNotContain("super-secret-hash-value");
        assertThat(text.toLowerCase()).as("toString must not name the password field")
                .doesNotContain("password");
        assertThat(text).as("toString must still identify the user").contains("EMP-7003");
    }
}
