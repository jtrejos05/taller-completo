package co.universidad.taller.saga;

import co.universidad.taller.model.EvaluacionPayload;
import co.universidad.taller.model.ExamSubmission;
import co.universidad.taller.model.Entities.Grade;
import co.universidad.taller.model.Entities.SagaExecution;
import co.universidad.taller.model.Entities.SagaExecution.SagaEstado;
import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Queue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.extern.java.Log;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SagaOrchestrator — Núcleo del patrón Saga Orchestration.
 *
 * <h3>¿Por qué NOT_SUPPORTED en lugar de REQUIRED?</h3>
 * <p>Saga Orchestration es incompatible con JTA global (Two-Phase Commit).
 * Si usáramos {@code REQUIRED}, el contenedor WildFly envolvería TODOS
 * los pasos en una sola transacción distribuida (2PC), que es exactamente
 * lo que queremos evitar. Con {@code NOT_SUPPORTED}, cada paso maneja
 * su propia transacción local mediante {@code REQUIRES_NEW}.</p>
 *
 * <h3>Flujo de la Saga</h3>
 * <ol>
 *   <li>① {@link #paso1GuardarExamen} — INSERT en exam_db (ExamPU)</li>
 *   <li>② {@link #paso2GuardarNota} — INSERT en student_db (StudentPU)</li>
 *   <li>③ {@link #paso3PublicarEvento} — PUBLISH en RabbitMQ (JMS)</li>
 * </ol>
 *
 * <h3>Compensaciones (orden inverso)</h3>
 * <ul>
 *   <li>{@link #compensarPaso2} — DELETE grade si ② fue exitoso</li>
 *   <li>{@link #compensarPaso1} — DELETE submission si ① fue exitoso</li>
 * </ul>
 *
 * <h3>WildFly / Jakarta EE</h3>
 * <ul>
 *   <li>{@code @Stateless}: bean sin estado, WildFly gestiona el pool.</li>
 *   <li>{@code @PersistenceContext(unitName="ExamPU")}: inyecta el
 *       EntityManager configurado en persistence.xml para exam_db.</li>
 *   <li>{@code @PersistenceContext(unitName="StudentPU")}: ídem para student_db.</li>
 *   <li>{@code @Resource(lookup="java:/jms/RabbitCF")}: inyecta la
 *       ConnectionFactory configurada en standalone.xml para RabbitMQ.</li>
 * </ul>
 */
@Stateless
@Log
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
// ↑ CRÍTICO: deshabilita JTA global. Cada paso REQUIRES_NEW crea su
//   propia transacción local y la commitea/rollbackea de forma independiente.
public class SagaOrchestrator {

    // ──────────────────────────────────────────────────────────────
    //  INYECCIÓN DE RECURSOS (WildFly gestiona el ciclo de vida)
    // ──────────────────────────────────────────────────────────────

    /**
     * EntityManager para exam_db.
     * Configurado en persistence.xml bajo la unidad "ExamPU".
     * JNDI datasource: java:/ExamDS (definido en standalone.xml).
     */
    @PersistenceContext(unitName = "ExamPU")
    private EntityManager examEM;

    /**
     * EntityManager para student_db.
     * Configurado en persistence.xml bajo la unidad "StudentPU".
     * JNDI datasource: java:/StudentDS (definido en standalone.xml).
     */
    @PersistenceContext(unitName = "StudentPU")
    private EntityManager studentEM;

    /**
     * ConnectionFactory JMS para publicar en RabbitMQ.
     * Configurado en standalone.xml bajo el resource-adapter AMQP.
     * Alternativa: usar el cliente AMQP nativo (ver RabbitMQPublisher).
     */
    @Resource(lookup = "java:/jms/RabbitCF")
    private ConnectionFactory rabbitCF;

    /**
     * Destino JMS que mapea a la cola "evaluation.results" en RabbitMQ.
     * Configurado como administered object en standalone.xml.
     */
    @Resource(lookup = "java:/jms/queue/EvaluationResults")
    private Queue evaluationQueue;

    // ──────────────────────────────────────────────────────────────
    //  MÉTODO PRINCIPAL — orquesta los 3 pasos
    // ──────────────────────────────────────────────────────────────

    /**
     * Ejecuta la Saga de evaluación.
     *
     * @param payload datos de la evaluación a procesar
     * @return mapa con submission_id y grade_id si todo fue exitoso
     * @throws SagaException si algún paso falla (después de compensar)
     */
    public Map<String, String> ejecutar(EvaluacionPayload payload) {
        UUID submissionId = null;
        UUID gradeId      = null;

        // Registrar inicio en el log de Saga (auditoría)
        SagaExecution sagaLog = iniciarSagaLog(payload);

        try {
            // ── PASO ①: guardar examen en exam_db ─────────────────
            submissionId = paso1GuardarExamen(payload);
            actualizarSagaLog(sagaLog, SagaEstado.STEP1_OK, null);
            log.info("[SAGA ①] Examen guardado: " + submissionId);

            // ── PASO ②: guardar nota en student_db ────────────────
            gradeId = paso2GuardarNota(payload, submissionId);
            actualizarSagaLog(sagaLog, SagaEstado.STEP2_OK, null);
            log.info("[SAGA ②] Nota guardada: " + gradeId);

            // ── PASO ③: publicar evento en RabbitMQ (async) ───────
            paso3PublicarEvento(payload, submissionId);
            actualizarSagaLog(sagaLog, SagaEstado.STEP3_OK, null);
            log.info("[SAGA ③] Evento publicado en RabbitMQ");

            // SAGA COMPLETADA
            Map<String, String> resultado = new HashMap<>();
            resultado.put("status",        "ok");
            resultado.put("submissionId",  submissionId.toString());
            resultado.put("gradeId",       gradeId.toString());
            return resultado;

        } catch (Exception e) {
            log.log(Level.SEVERE, "[SAGA FALLO] " + e.getMessage() + " — iniciando compensación", e);
            actualizarSagaLog(sagaLog, SagaEstado.STEP2_FAILED, e.getMessage());

            // Compensar en orden INVERSO a lo que se alcanzó a guardar
            compensar(submissionId, gradeId, sagaLog);

            throw new SagaException("Transacción distribuida fallida: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ①: Guardar examen en exam_db
    // ──────────────────────────────────────────────────────────────

    /**
     * Inserta un registro en exam_db.exam_submissions.
     *
     * <p>{@code REQUIRES_NEW}: WildFly crea una transacción LOCAL nueva,
     * la commitea al salir del método, y la cierra. Es completamente
     * independiente de cualquier transacción del llamador.</p>
     *
     * @return UUID generado por PostgreSQL para el submission
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public UUID paso1GuardarExamen(EvaluacionPayload payload) {
        ExamSubmission submission = new ExamSubmission();
        submission.setStudentId(payload.getStudentId());
        submission.setExamId(payload.getExamId());
        submission.setRespuestas(payload.getRespuestas());
        submission.setPuntaje(BigDecimal.valueOf(payload.getPuntaje()));

        // persist() + flush() para forzar el INSERT y obtener el UUID generado
        examEM.persist(submission);
        examEM.flush();

        return submission.getId();
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ②: Guardar nota en student_db
    // ──────────────────────────────────────────────────────────────

    /**
     * Inserta un registro en student_db.grades.
     *
     * <p>{@code submissionId} es una referencia LÓGICA (no FK real):
     * PostgreSQL no puede validar una FK hacia otra base de datos.
     * La consistencia es garantizada por la Saga.</p>
     *
     * @param submissionId UUID del examen guardado en el paso ①
     * @return UUID generado para la nota
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public UUID paso2GuardarNota(EvaluacionPayload payload, UUID submissionId) {
        // Buscar el estudiante en student_db
        co.universidad.taller.model.Entities.Student student =
            studentEM.find(co.universidad.taller.model.Entities.Student.class, payload.getStudentId());

        if (student == null) {
            throw new IllegalArgumentException(
                "Estudiante no encontrado: " + payload.getStudentId()
            );
        }

        Grade grade = new Grade();
        grade.setStudent(student);
        grade.setExamId(payload.getExamId());
        grade.setNota(BigDecimal.valueOf(payload.getPuntaje()));
        grade.setSubmissionId(submissionId); // referencia lógica cross-BD

        studentEM.persist(grade);
        studentEM.flush();

        return grade.getId();
    }

    // ──────────────────────────────────────────────────────────────
    //  PASO ③: Publicar evento en RabbitMQ (asíncrono)
    // ──────────────────────────────────────────────────────────────

    /**
     * Publica un evento JSON en la cola evaluation.results de RabbitMQ.
     *
     * <p>El email_consumer (MDB) lo procesará de forma asíncrona.
     * El cliente recibe la respuesta HTTP ANTES de que el email llegue
     * (desacoplamiento temporal).</p>
     *
     * <p>El mensaje es PERSISTENTE: sobrevive restart del broker.
     * El ACK lo da el MDB cuando termina de procesar exitosamente.</p>
     *
     * <p>{@code NOT_SUPPORTED}: la publicación JMS no participa en
     * ninguna transacción JTA para evitar el XA protocol con RabbitMQ.</p>
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void paso3PublicarEvento(EvaluacionPayload payload, UUID submissionId) {
        try (var connection = rabbitCF.createConnection();
             var session    = connection.createSession(false,
                                 jakarta.jms.Session.AUTO_ACKNOWLEDGE)) {

            var producer = session.createProducer(evaluationQueue);

            // Construir mensaje JSON con los datos del evento
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
            // DeliveryMode.PERSISTENT: el mensaje sobrevive restart del broker
            message.setJMSDeliveryMode(jakarta.jms.DeliveryMode.PERSISTENT);
            message.setStringProperty("contentType", "application/json");

            producer.send(message);

        } catch (jakarta.jms.JMSException e) {
            throw new RuntimeException("Error publicando evento en RabbitMQ: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  COMPENSACIONES (orden inverso)
    // ──────────────────────────────────────────────────────────────

    /**
     * Orquesta las transacciones compensatorias en orden inverso.
     * Se llama únicamente cuando la Saga falla en algún paso.
     */
    private void compensar(UUID submissionId, UUID gradeId, SagaExecution sagaLog) {
        boolean compensacionExitosa = true;

        // Compensar paso ②: eliminar nota (si se llegó a guardar)
        if (gradeId != null) {
            try {
                compensarPaso2(gradeId);
                log.info("[COMPENSAR ②] Nota eliminada: " + gradeId);
            } catch (Exception ex) {
                compensacionExitosa = false;
                log.log(Level.SEVERE,
                    "[COMPENSAR ②  FALLO] No se pudo revertir nota " + gradeId, ex);
                // En producción: alertar al equipo de operaciones vía PagerDuty/OpsGenie
            }
        }

        // Compensar paso ①: eliminar examen (si se llegó a guardar)
        if (submissionId != null) {
            try {
                compensarPaso1(submissionId);
                log.info("[COMPENSAR ①] Submission eliminada: " + submissionId);
            } catch (Exception ex) {
                compensacionExitosa = false;
                log.log(Level.SEVERE,
                    "[COMPENSAR ①  FALLO] No se pudo revertir examen " + submissionId, ex);
            }
        }

        actualizarSagaLog(
            sagaLog,
            compensacionExitosa ? SagaEstado.COMPENSATED : SagaEstado.COMPENSATION_FAILED,
            compensacionExitosa ? null : "Fallo durante compensación — revisión manual requerida"
        );
    }

    /**
     * Compensación del paso ①: elimina el submission de exam_db.
     *
     * <p>{@code REQUIRES_NEW}: transacción LOCAL independiente.
     * Si esta compensación falla, NO afecta a otras compensaciones en curso.</p>
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void compensarPaso1(UUID submissionId) {
        ExamSubmission submission = examEM.find(ExamSubmission.class, submissionId);
        if (submission != null) {
            examEM.remove(submission);
            examEM.flush();
        }
    }

    /**
     * Compensación del paso ②: elimina la nota de student_db.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void compensarPaso2(UUID gradeId) {
        Grade grade = studentEM.find(Grade.class, gradeId);
        if (grade != null) {
            studentEM.remove(grade);
            studentEM.flush();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  SAGA LOG — auditoría en exam_db.saga_executions
    // ──────────────────────────────────────────────────────────────

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    private SagaExecution iniciarSagaLog(EvaluacionPayload payload) {
        SagaExecution log = new SagaExecution();
        log.setStudentId(payload.getStudentId());
        log.setExamId(payload.getExamId());
        log.setEstado(SagaEstado.STARTED);
        examEM.persist(log);
        examEM.flush();
        return log;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    private void actualizarSagaLog(SagaExecution sagaLog, SagaEstado estado, String detalle) {
        try {
            SagaExecution managed = examEM.find(SagaExecution.class, sagaLog.getId());
            if (managed != null) {
                managed.setEstado(estado);
                managed.setDetalle(detalle);
                examEM.flush();
            }
        } catch (Exception e) {
            // El log de auditoría no debe interrumpir el flujo principal
            log.log(Level.WARNING, "No se pudo actualizar saga_log: " + e.getMessage());
        }
    }
}
