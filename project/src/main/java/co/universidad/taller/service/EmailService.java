package co.universidad.taller.service;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.java.Log;
import java.util.logging.Level;

@Stateless
@Log
public class EmailService {

    @Resource(lookup = "java:jboss/mail/Default")
    private Session mailSession;

    public void enviarResultado(String destinatario, String nombre,
                                String examId, double puntaje) {
        try {
            MimeMessage msg = new MimeMessage(mailSession);
            msg.setFrom(new InternetAddress("noreply@universidad.co"));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(destinatario));
            msg.setSubject("Resultado de tu evaluación: " + examId);
            msg.setText(String.format(
                "Hola %s,%n%nTu puntaje en el examen %s fue: %.2f%n%nSaludos,%nSistema de Evaluaciones",
                nombre, examId, puntaje
            ));
            Transport.send(msg);
            log.info("[EMAIL] Enviado a " + destinatario);
        } catch (MessagingException e) {
            log.log(Level.SEVERE, "[EMAIL] Fallo enviando a " + destinatario, e);
            throw new RuntimeException("Error enviando email: " + e.getMessage(), e);
        }
    }
}
