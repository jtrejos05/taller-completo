package co.universidad.taller.saga;

import co.universidad.taller.model.EvaluacionPayload;
import co.universidad.taller.model.ExamSubmission;
import co.universidad.taller.model.Entities.Grade;
import co.universidad.taller.model.Entities.SagaExecution;
import co.universidad.taller.model.Entities.SagaExecution.SagaEstado;
import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Queue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.java.Log;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SagaOrchestrator — Núcleo del patrón Saga Orchestration.
 *
 * FIX: Se agrega auto-inyección (@EJB SagaOrchestrator self) para que
 * las llamadas internas pasen por el proxy EJB y respeten
 * @TransactionAttribute. Sin esto, llamar this.paso1() omite el proxy
 * y todas las transacciones se ejecutan sin contexto JTA.
 */
@Stateless
@Log
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class SagaOrchestrator {

    // Auto-inyección: las llamadas a self.método() pasan por el proxy EJB
    // y respetan los @TransactionAttribute de cada método.
    @EJB
    private SagaOrchestrator self;

    @PersistenceContext(unitName = "ExamPU")
    private EntityManager examEM;

    @PersistenceContext(unitName = "StudentPU")
    private EntityManager studentEM;

    @Resource(lookup = "java:/jms/RabbitCF")
    private ConnectionFactory rabbitCF;

    @Resource(lookup = "java:/jms/queue/EvaluationResults")
    private Queue evaluationQueue;

    // ──────────────────────────────────────────────────────────────
    //  MÉTODO PRINCIPAL
    // ──────────────────────────────────────────────────────────────

    public Map<String, String> ejecutar(EvaluacionPayload payload) {
        UUID submissionId = null;
        UUID gradeId      = null;

        // Llamadas via self. → pasan por el proxy → @TransactionAttribute aplicado
        SagaExecution sagaLog = self.iniciarSagaLog(payload);

        try {
            submissionId = self.paso1GuardarExamen(payload);
            self.actualizarSagaLog(sagaLog, SagaEstado.STEP1_OK, null);
            log.info("[SAGA ①] Examen guardado: " + submissionId);

            gradeId = self.paso2GuardarNota(payload, submissionId);
            self.actualizarSagaLog(sagaLog, SagaEstado.STEP2_OK, null);
            log.info("[SAGA ②] Nota guardada: " + gradeId);

            self.paso3PublicarEvento(payload, submissionId);
            self.actualizarSagaLog(sagaLog, SagaEstado.STEP3_OK, null);
            log.info("[SAGA ③] Evento publicado en JMS");

            Map<String, String> resultado = new HashMap<>();
            resultado.put("status",       "ok");
            resultado.put("submissionId", submissionId.toString());
            resultado.put("gradeId",      gradeId.toString());
            return resultado;

        } catch (Exception e) {
            log.log(Level.SEVERE, "[SAGA FALLO] " + e.getMessage() + " — iniciando compensación", e);
            self.actualizarSagaLog(sagaLog, SagaEstado.STEP2_FAILED, e.getMessage());
            compensar(submissionId, gradeId, sagaLog);
            throw new SagaException("Transacción distribuida fallida: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ①
    // ──────────────────────────────────────────────────────────────

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public UUID paso1GuardarExamen(EvaluacionPayload payload) {
        ExamSubmission submission = new ExamSubmission();
        submission.setStudentId(payload.getStudentId());
        submission.setExamId(payload.getExamId());
        submission.setRespuestas(payload.getRespuestas());
        submission.setPuntaje(BigDecimal.valueOf(payload.getPuntaje()));
        examEM.persist(submission);
        examEM.flush();
        return submission.getId();
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ②
    // ──────────────────────────────────────────────────────────────

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public UUID paso2GuardarNota(EvaluacionPayload payload, UUID submissionId) {
        co.universidad.taller.model.Entities.Student student =
            studentEM.find(co.universidad.taller.model.Entities.Student.class, payload.getStudentId());

        if (student == null) {
            throw new IllegalArgumentException("Estudiante no encontrado: " + payload.getStudentId());
        }

        Grade grade = new Grade();
        grade.setStudent(student);
        grade.setExamId(payload.getExamId());
        grade.setNota(BigDecimal.valueOf(payload.getPuntaje()));
        grade.setSubmissionId(submissionId);
        studentEM.persist(grade);
        studentEM.flush();
        return grade.getId();
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ③
    // ──────────────────────────────────────────────────────────────

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void paso3PublicarEvento(EvaluacionPayload payload, UUID submissionId) {
        try (var connection = rabbitCF.createConnection();
             var session    = connection.createSession(false,
                                 jakarta.jms.Session.AUTO_ACKNOWLEDGE)) {

            var producer = session.createProducer(evaluationQueue);

            String jsonEvento = String.format(
                """
                {
                  "studentId":    "%s",
                  "examId":       "%s",
                  "puntaje":      %.2f,
                  "submissionId": "%s"
                }
                """,
                payload.getStudentId(),
                payload.getExamId(),
                payload.getPuntaje(),
                submissionId.toString()
            );

            var message = session.createTextMessage(jsonEvento);
            message.setJMSDeliveryMode(jakarta.jms.DeliveryMode.PERSISTENT);
            message.setStringProperty("contentType", "application/json");
            producer.send(message);

        } catch (jakarta.jms.JMSException e) {
            throw new RuntimeException("Error publicando evento JMS: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  COMPENSACIONES
    // ──────────────────────────────────────────────────────────────

    private void compensar(UUID submissionId, UUID gradeId, SagaExecution sagaLog) {
        boolean ok = true;

        if (gradeId != null) {
            try {
                self.compensarPaso2(gradeId);
                log.info("[COMPENSAR ②] Nota eliminada: " + gradeId);
            } catch (Exception ex) {
                ok = false;
                log.log(Level.SEVERE, "[COMPENSAR ② FALLO] " + gradeId, ex);
            }
        }

        if (submissionId != null) {
            try {
                self.compensarPaso1(submissionId);
                log.info("[COMPENSAR ①] Submission eliminada: " + submissionId);
            } catch (Exception ex) {
                ok = false;
                log.log(Level.SEVERE, "[COMPENSAR ① FALLO] " + submissionId, ex);
            }
        }

        self.actualizarSagaLog(
            sagaLog,
            ok ? SagaEstado.COMPENSATED : SagaEstado.COMPENSATION_FAILED,
            ok ? null : "Fallo durante compensación"
        );
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void compensarPaso1(UUID submissionId) {
        ExamSubmission s = examEM.find(ExamSubmission.class, submissionId);
        if (s != null) { examEM.remove(s); examEM.flush(); }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void compensarPaso2(UUID gradeId) {
        Grade g = studentEM.find(Grade.class, gradeId);
        if (g != null) { studentEM.remove(g); studentEM.flush(); }
    }

    // ──────────────────────────────────────────────────────────────
    //  SAGA LOG — ahora públicos para que el proxy EJB los intercepte
    // ──────────────────────────────────────────────────────────────

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public SagaExecution iniciarSagaLog(EvaluacionPayload payload) {
        SagaExecution sagaLog = new SagaExecution();
        sagaLog.setStudentId(payload.getStudentId());
        sagaLog.setExamId(payload.getExamId());
        sagaLog.setEstado(SagaEstado.STARTED);
        examEM.persist(sagaLog);
        examEM.flush();
        return sagaLog;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void actualizarSagaLog(SagaExecution sagaLog, SagaEstado estado, String detalle) {
        try {
            SagaExecution managed = examEM.find(SagaExecution.class, sagaLog.getId());
            if (managed != null) {
                managed.setEstado(estado);
                managed.setDetalle(detalle);
                examEM.flush();
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "No se pudo actualizar saga_log: " + e.getMessage());
        }
    }
}