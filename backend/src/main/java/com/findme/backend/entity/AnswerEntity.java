package com.findme.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "answers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // For H2 and Postgres IDENTITY
    private Long id;

    @Column(name = "question_id", nullable = false)
    private String questionId;

    private int value;

    @Column(name = "submission_id", nullable = false)
    private String submissionId; // To group answers for a single submission
}
