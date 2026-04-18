-- ============================================================
--  init_exam_db.sql — Esquema inicial para exam_db
-- ============================================================

CREATE TABLE IF NOT EXISTS exam_submissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id   VARCHAR(50)    NOT NULL,
    exam_id      VARCHAR(100)   NOT NULL,
    respuestas   TEXT,
    puntaje      NUMERIC(5, 2),
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS saga_executions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id   VARCHAR(50),
    exam_id      VARCHAR(100),
    estado       VARCHAR(30)    NOT NULL,
    detalle      VARCHAR(1000),
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Datos de prueba
INSERT INTO exam_submissions (student_id, exam_id, respuestas, puntaje)
VALUES ('S001', 'EXAM-TEST-00', '[]', 0.0)
ON CONFLICT DO NOTHING;
