package com.accessflow.exception;

public class AccessRequestNotFoundException extends RuntimeException {

    public AccessRequestNotFoundException(Long id) {
        super("Access request not found: " + id);
    }
}
