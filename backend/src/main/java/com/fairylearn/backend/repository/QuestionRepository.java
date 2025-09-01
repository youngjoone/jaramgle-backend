package com.fairylearn.backend.repository;

import com.fairylearn.backend.entity.Question;
import com.fairylearn.backend.entity.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, String> {
    List<Question> findByTest(Test test);
}