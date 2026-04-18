package co.universidad.taller.model.Entities;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "saga_executions")
public class SagaExecution {

    public enum SagaEstado {
        STARTED, STEP1_OK, STEP2_OK, STEP3_OK,
        STEP2_FAILED, COMPENSATED, COMPENSATION_FAILED
    }

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "student_id")
    private String studentId;

    @Column(name = "exam_id")
    private String examId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private SagaEstado estado;

    @Column(name = "detalle", length = 1000)
    private String detalle;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
