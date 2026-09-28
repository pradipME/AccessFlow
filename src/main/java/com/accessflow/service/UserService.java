package com.accessflow.service;

import java.util.List;

import com.accessflow.entity.User;
import com.accessflow.exception.DuplicateUserException;
import com.accessflow.exception.UserNotFoundException;
import com.accessflow.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Hashes the supplied password with BCrypt before it reaches the database.
     * The caller may pass a plaintext password, but nothing downstream of this
     * method ever sees it, so plaintext cannot be persisted even by mistake.
     */
    @Transactional
    public User createUser(User user) {
        if (userRepository.existsByEmployeeId(user.getEmployeeId())) {
            throw new DuplicateUserException("employeeId", user.getEmployeeId());
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new DuplicateUserException("email", user.getEmail());
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id));
    }

    public User getUserByEmployeeId(String employeeId) {
        return userRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new UserNotFoundException("employeeId " + employeeId));
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("email " + email));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public boolean existsByEmployeeId(String employeeId) {
        return userRepository.existsByEmployeeId(employeeId);
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }
}
