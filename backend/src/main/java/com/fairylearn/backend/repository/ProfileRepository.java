package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {
    
}
