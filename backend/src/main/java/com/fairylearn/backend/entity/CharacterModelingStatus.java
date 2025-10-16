package com.fairylearn.backend.entity;

public enum CharacterModelingStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
