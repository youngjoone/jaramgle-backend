package com.fairylearn.backend.exception;

public class CharacterModelingException extends RuntimeException {

    public CharacterModelingException(String message) {
        super(message);
    }

    public CharacterModelingException(String message, Throwable cause) {
        super(message, cause);
    }
}
