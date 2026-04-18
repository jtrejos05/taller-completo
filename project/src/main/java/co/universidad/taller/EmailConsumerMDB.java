package co.universidad.taller.mdb;

import co.universidad.taller.service.EstudianteService;
import co.universidad.taller.service.EmailService;
import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.extern.java.Log;

import java.io.StringReader;
import java.util.logging.Level;

/**
 * EmailConsumerMDB — Message-Driven Bean que consume la cola EvaluationResults.
 *
 * FIX: destination cambiado de "evaluation.results" a "EvaluationResults"
 * para coincidir con la cola creada en ActiveMQ Artemis via configure.cli.
 */
@MessageDriven(
    activationConfig = {
        @ActivationConfigProperty(
            propertyName  = "destinationType",
            propertyValue = "jakarta.jms.Queue"
        ),
        // CORREGIDO: nombre de la cola en Artemis (creada en configure.cli)
        @ActivationConfigProperty(
            propertyName  = "destination",
            propertyValue = "EvaluationResults"
        ),
        @ActivationConfigProperty(
            propertyName  = "acknowledgeMode",
            propertyValue = "Auto-acknowledge"
        ),
        @ActivationConfigProperty(
            propertyName  = "maxSession",
            propertyValue = "1"
        )
    }
)
@Log
public class EmailConsumerMDB implements MessageListener {

    @Inject
    private EstudianteService estudianteService;

    @Inject
    private EmailService emailService;

    @Override
    public void onMessage(Message message) {
        try {
            if (!(message instanceof TextMessage textMessage)) {
                log.warning("[EMAIL MDB] Mensaje recibido no es TextMessage, ignorando");
                return;
            }

            String body = textMessage.getText();
            log.info("[EMAIL MDB] Procesando: " + body);

            JsonObject evento;
            try (var reader = Json.createReader(new StringReader(body))) {
                evento = reader.readObject();
            }

            String studentId = evento.getString("studentId");
            String examId    = evento.getString("examId");
            double puntaje   = evento.getJsonNumber("puntaje").doubleValue();

            EstudianteService.EstudianteInfo info = estudianteService.obtenerInfo(studentId);

            if (info == null) {
                log.warning("[EMAIL MDB] Estudiante " + studentId + " no encontrado. Email no enviado.");
                return;
            }

            emailService.enviarResultado(
                info.email(),
                info.nombre(),
                examId,
                puntaje
            );

            log.info("[EMAIL MDB] Email enviado a " + info.email());

        } catch (Exception e) {
            log.log(Level.SEVERE, "[EMAIL MDB] Error procesando mensaje: " + e.getMessage(), e);
            throw new EJBException("Error procesando evento de evaluación", e);
        }
    }
}