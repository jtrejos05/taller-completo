package co.universidad.taller.rest;

import co.universidad.taller.model.EvaluacionPayload;
import co.universidad.taller.saga.SagaException;
import co.universidad.taller.saga.SagaOrchestrator;
import jakarta.ejb.EJB;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.java.Log;

import java.util.Map;
import java.util.logging.Level;

/**
 * EvaluacionResource — Endpoint REST para registrar evaluaciones.
 *
 * <h3>WildFly / RESTEasy</h3>
 * <p>RESTEasy es la implementación JAX-RS de WildFly. Esta clase se
 * detecta automáticamente por la anotación {@code @Path} gracias al
 * {@link TallerApplication} que activa el escaneo de recursos JAX-RS.</p>
 *
 * <p>La deserialización JSON→Java y serialización Java→JSON es gestionada
 * por JSON-B (Yasson), que WildFly provee como {@code MessageBodyReader/Writer}.
 * No se necesita un {@code ObjectMapper} manual.</p>
 *
 * <h3>Idempotencia</h3>
 * <p>Si el cliente reintenta la solicitud (timeout de red), el paso ②
 * fallará con {@code PersistenceException} por el UNIQUE constraint
 * {@code (student_id, exam_id)} en grades. El orquestador compensará
 * el paso ①, dejando el sistema en estado limpio. En producción se
 * añadiría una verificación previa para devolver el resultado anterior.</p>
 *
 * <h3>Ruta</h3>
 * {@code POST http://localhost:8080/taller-distribuido/api/evaluacion}
 */
@Path("/evaluacion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Log
public class EvaluacionResource {

    /**
     * Inyección del EJB orquestador.
     * WildFly gestiona el pool de instancias de SagaOrchestrator.
     * @EJB es equivalente a @Inject para EJBs locales en WildFly 30.
     */
    @EJB
    private SagaOrchestrator orquestador;

    // ──────────────────────────────────────────────────────────────
    //  POST /evaluacion — Registrar evaluación
    // ──────────────────────────────────────────────────────────────

    /**
     * Recibe la evaluación de un estudiante y ejecuta la Saga.
     *
     * <p>El método no necesita {@code @TransactionAttribute} propio:
     * delega toda la lógica transaccional al {@link SagaOrchestrator}
     * que maneja sus propias transacciones locales con REQUIRES_NEW.</p>
     *
     * @param payload datos de la evaluación (deserializado por JSON-B)
     * @return HTTP 200 con {submissionId, gradeId} o HTTP 500 si falla
     */
    @POST
    public Response submitEvaluacion(@Valid EvaluacionPayload payload) {
        log.info(String.format(
            "[REQUEST] student=%s exam=%s puntaje=%.2f",
            payload.getStudentId(),
            payload.getExamId(),
            payload.getPuntaje()
        ));

        try {
            Map<String, String> resultado = orquestador.ejecutar(payload);
            log.info("[SAGA OK] " + resultado);
            // HTTP 200 OK con el resultado JSON
            return Response.ok(resultado).build();

        } catch (SagaException e) {
            // La Saga ya ejecutó las compensaciones antes de lanzar la excepción.
            // Devolvemos HTTP 500 con el detalle del fallo.
            log.log(Level.SEVERE, "[SAGA FALLIDA] " + e.getMessage());
            return Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(
                    "error",   "Transacción distribuida fallida",
                    "detalle", e.getMessage()
                ))
                .build();

        } catch (jakarta.validation.ConstraintViolationException e) {
            // Bean Validation falló: payload inválido
            return Response
                .status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Datos inválidos: " + e.getMessage()))
                .build();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  GET /evaluacion/health — Health check del servicio
    // ──────────────────────────────────────────────────────────────

    /**
     * Endpoint de health check.
     * En WildFly con MicroProfile Health, esto se complementa con
     * {@code @Readiness} y {@code @Liveness} probes en /health.
     */
    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(Map.of(
            "status",  "UP",
            "service", "saga-orchestrator",
            "server",  "WildFly 30 / Jakarta EE 10"
        )).build();
    }
}
