package com.accessflow.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_employee_id", columnNames = "employee_id"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        })
public class User {

    public enum Role {
        EMPLOYEE,
        MANAGER,
        IT_ADMIN,
        SUPER_ADMIN
    }

    /**
     * Required by JPA. Must stay public: tests and future mapping layers
     * construct User instances from outside this package. Declaring it
     * explicitly means a future convenience constructor cannot silently
     * remove the no-argument constructor Hibernate depends on.
     */
    public User() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password", nullable = false, length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.EMPLOYEE;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Identity is the database id only. Business fields are deliberately excluded:
     * employeeId, email and lastName are all mutable, so including them would let a
     * User stop being equal to itself after an update, corrupting any Set or Map it
     * sits in.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User)) {
            return false;
        }
        User that = (User) other;
        return id != null && id.equals(that.getId());
    }

    /**
     * Intentionally constant. A hash derived from mutable or generated state changes
     * when the id is assigned, which would strand the instance in a HashSet after
     * save(). Uses User.class rather than getClass() so a Hibernate proxy and a real
     * instance still collide correctly.
     */
    @Override
    public int hashCode() {
        return User.class.hashCode();
    }

    /**
     * Never includes the password. A field-by-field dump would write credential
     * material into application logs, exception messages and log aggregators.
     */
    @Override
    public String toString() {
        return "User{"
                + "id=" + id
                + ", employeeId='" + employeeId + '\''
                + ", email='" + email + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", role=" + role
                + ", department='" + department + '\''
                + ", active=" + active
                + '}';
    }
}
