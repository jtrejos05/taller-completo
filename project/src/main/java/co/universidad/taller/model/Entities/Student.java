package co.universidad.taller.model.Entities;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "students")
public class Student {

    @Id
    @Column(name = "student_id")
    private String id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String email;
}
