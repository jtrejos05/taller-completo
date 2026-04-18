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
 * EmailConsumerMDB — Message-Driven Bean que consume la cola evaluation.results.
 *
 * <h3>¿Qué es un MDB?</h3>
 * <p>Un Message-Driven Bean es un EJB que WildFly activa automáticamente
 * cada vez que llega un mensaje a la cola configurada. Es el equivalente
 * Java EE del consumer.py con pika.BlockingConnection.</p>
 *
 * <h3>Configuración en standalone.xml</h3>
 * <p>El resource-adapter AMQP de RabbitMQ debe estar configurado en
 * standalone.xml para que este MDB pueda conectarse. Ver wildfly-config/standalone.xml.</p>
 *
 * <h3>Desacoplamiento total</h3>
 * <p>Este bean NO conoce al {@code SagaOrchestrator}. Solo sabe:
 * <ol>
 *   <li>Escuchar la cola {@code evaluation.results}</li>
 *   <li>Extraer los datos del mensaje JSON</li>
 *   <li>Obtener el email del estudiante desde student_db</li>
 *   <li>Enviar el email vía SMTP</li>
 * </ol>
 * Si el servidor SMTP falla, el MDB lanza una excepción y WildFly
 * re-entrega el mensaje automáticamente (sin pérdida).</p>
 *
 * <h3>Equivalencia con consumer.py</h3>
 * <pre>
 *  consumer.py                         EmailConsumerMDB.java
 *  ─────────────────────               ─────────────────────────
 *  channel.basic_consume(...)      →   @MessageDriven(activationConfig)
 *  def callback(ch, method, body)  →   onMessage(Message message)
 *  ch.basic_ack(...)               →   Session.AUTO_ACKNOWLEDGE
 *  ch.basic_nack(requeue=False)    →   throw RuntimeException
 * </pre>
 */
@MessageDriven(
    /*
     * activationConfig: configura cómo este MDB se conecta al broker.
     * Estas propiedades son específicas del resource-adapter AMQP de RabbitMQ.
     * El resource-adapter traduce las propiedades JMS al protocolo AMQP 0-9-1.
     */
    activationConfig = {
        // Tipo de destino JMS: Queue (no Topic)
        @ActivationConfigProperty(
            propertyName  = "destinationType",
            propertyValue = "jakarta.jms.Queue"
        ),
        // Nombre de la cola en RabbitMQ
        @ActivationConfigProperty(
            propertyName  = "destination",
            propertyValue = "evaluation.results"
        ),
        /*
         * AUTO_ACKNOWLEDGE: WildFly envía el ACK a RabbitMQ automáticamente
         * cuando onMessage() retorna sin excepción.
         * Si onMessage() lanza una excepción, WildFly envía NACK y el
         * mensaje puede ser re-entregado (equivale a basic_nack(requeue=True)).
         */
        @ActivationConfigProperty(
            propertyName  = "acknowledgeMode",
            propertyValue = "Auto-acknowledge"
        ),
        /*
         * maxSession=1: equivale a prefetch_count=1 en pika.
         * Procesa un mensaje a la vez antes de solicitar el siguiente.
         * Aumentar para mayor throughput si el SMTP lo soporta.
         */
        @ActivationConfigProperty(
            propertyName  = "maxSession",
            propertyValue = "1"
        )
    }
)
@Log
public class EmailConsumerMDB implements MessageListener {

    /**
     * Servicio para consultar datos del estudiante en student_db.
     * Inyectado por CDI (@Inject) — WildFly gestiona el ciclo de vida.
     */
    @Inject
    private EstudianteService estudianteService;

    /**
     * Servicio para enviar emails vía SMTP.
     * Encapsula la configuración de JavaMail (servidor, puerto, credenciales).
     */
    @Inject
    private EmailService emailService;

    // ──────────────────────────────────────────────────────────────
    //  onMessage — invocado por WildFly por cada mensaje en la cola
    // ──────────────────────────────────────────────────────────────

    /**
     * Procesa un evento de evaluación recibido de RabbitMQ.
     *
     * <p>WildFly llama este método en un hilo del pool del MDB.
     * Si el método retorna normalmente → ACK automático.
     * Si lanza una excepción → NACK y posible re-entrega.</p>
     *
     * @param message mensaje JMS recibido (TextMessage con JSON)
     */
    @Override
    public void onMessage(Message message) {
        try {
            // Extraer el cuerpo del mensaje (TextMessage con JSON)
            if (!(message instanceof TextMessage textMessage)) {
                log.warning("[EMAIL MDB] Mensaje recibido no es TextMessage, ignorando");
                return;
            }

            String body = textMessage.getText();
            log.info("[EMAIL MDB] Procesando: " + body);

            // Parsear el JSON con JSON-P (Jakarta JSON Processing)
            JsonObject evento;
            try (var reader = Json.createReader(new StringReader(body))) {
                evento = reader.readObject();
            }

            String studentId = evento.getString("studentId");
            String examId    = evento.getString("examId");
            double puntaje   = evento.getJsonNumber("puntaje").doubleValue();

            // Obtener nombre y email del estudiante desde student_db
            EstudianteService.EstudianteInfo info = estudianteService.obtenerInfo(studentId);

            if (info == null) {
                log.warning("[EMAIL MDB] Estudiante " + studentId + " no encontrado. Email no enviado.");
                // Retornamos sin relanzar: el mensaje se ACKea (no tiene sentido reintentar)
                return;
            }

            // Enviar el email
            emailService.enviarResultado(
                info.email(),
                info.nombre(),
                examId,
                puntaje
            );

            log.info("[EMAIL MDB] Email enviado a " + info.email());
            // WildFly envía ACK automáticamente al retornar aquí

        } catch (Exception e) {
            log.log(Level.SEVERE, "[EMAIL MDB] Error procesando mensaje: " + e.getMessage(), e);
            /*
             * Re-lanzar como EJBException para que WildFly envíe NACK
             * y el mensaje sea re-entregado o movido a la dead-letter queue.
             * NO relanzar si el error es de datos (estudiante no encontrado)
             * porque causaría un bucle infinito de re-entregas.
             */
            throw new EJBException("Error procesando evento de evaluación", e);
        }
    }
}
