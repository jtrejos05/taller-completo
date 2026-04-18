package co.universidad.taller.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Table(name = "exam_submissions")
public class ExamSubmission {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "exam_id", nullable = false)
    private String examId;

    @Column(name = "respuestas", columnDefinition = "TEXT")
    private String respuestas;

    @Column(name = "puntaje", precision = 5, scale = 2)
    private BigDecimal puntaje;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }

    public void setRespuestas(List<Map<String, Object>> lista) {
        if (lista == null) { this.respuestas = "[]"; return; }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lista.size(); i++) {
            Map<String, Object> item = lista.get(i);
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : item.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"")
                  .append(e.getValue()).append("\"");
                first = false;
            }
            sb.append("}");
            if (i < lista.size() - 1) sb.append(",");
        }
        sb.append("]");
        this.respuestas = sb.toString();
    }
}
