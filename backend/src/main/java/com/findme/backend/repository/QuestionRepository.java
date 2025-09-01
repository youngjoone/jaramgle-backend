package com.findme.backend.repository;

import com.findme.backend.entity.Question;
import com.findme.backend.entity.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, String> {
    List<Question> findByTest(Test test);
}