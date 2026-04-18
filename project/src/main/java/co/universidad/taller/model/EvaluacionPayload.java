package co.universidad.taller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class EvaluacionPayload {

    @NotBlank(message = "studentId es obligatorio")
    private String studentId;

    @NotBlank(message = "examId es obligatorio")
    private String examId;

    @NotNull
    private List<Map<String, Object>> respuestas;

    private double puntaje;
}
