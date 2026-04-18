package co.universidad.taller.service;

import co.universidad.taller.model.Entities.Student;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Stateless
public class EstudianteService {

    public record EstudianteInfo(String nombre, String email) {}

    @PersistenceContext(unitName = "StudentPU")
    private EntityManager em;

    public EstudianteInfo obtenerInfo(String studentId) {
        Student student = em.find(Student.class, studentId);
        if (student == null) return null;
        return new EstudianteInfo(student.getNombre(), student.getEmail());
    }
}
