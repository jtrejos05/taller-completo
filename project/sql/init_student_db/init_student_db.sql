-- ============================================================
--  init_student_db.sql — Esquema inicial para student_db
-- ============================================================

CREATE TABLE IF NOT EXISTS students (
    student_id   VARCHAR(50)    PRIMARY KEY,
    nombre       VARCHAR(200)   NOT NULL,
    email        VARCHAR(200)   NOT NULL
);

CREATE TABLE IF NOT EXISTS grades (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id     VARCHAR(50)    NOT NULL REFERENCES students(student_id),
    exam_id        VARCHAR(100)   NOT NULL,
    nota           NUMERIC(5, 2),
    submission_id  UUID,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    UNIQUE (student_id, exam_id)
);

-- Datos de prueba (estudiante que usa el curl del README)
INSERT INTO students (student_id, nombre, email)
VALUES
    ('S001', 'Juan Estudiante',   'juan@universidad.co'),
    ('S002', 'Maria Estudiante',  'maria@universidad.co'),
    ('S003', 'Carlos Estudiante', 'carlos@universidad.co')
ON CONFLICT DO NOTHING;
