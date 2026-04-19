# Taller — Transacciones Distribuidas y Desacoplamiento con Patrón Saga

## Descripción General

Este proyecto implementa una solución de **transacciones distribuidas** usando el patrón **Saga Orchestration** para gestionar evaluaciones académicas. El sistema permite registrar exámenes en dos bases de datos independientes manteniendo la integridad de los datos, y notifica automáticamente a los estudiantes por correo electrónico al finalizar la evaluación.

---

## Arquitectura

El sistema está compuesto por 4 servicios Docker:

### Servicios

| Servicio | Tecnología | Puerto | Descripción |
|----------|-----------|--------|-------------|
| `wildfly` | WildFly 30 / Jakarta EE 10 | 8080, 9990 | Servidor de aplicaciones principal |
| `exam_db` | PostgreSQL 15 | 5432 | Base de datos de exámenes y submissions |
| `student_db` | PostgreSQL 15 | 5433 | Base de datos de estudiantes y notas |
| `rabbitmq` | RabbitMQ 3 | 5672, 15672 | Broker de mensajería para eventos |

### Diagrama de Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                        Docker Network                        │
│                                                             │
│  ┌──────────┐    ┌──────────┐    ┌──────────────────────┐  │
│  │ exam_db  │    │student_db│    │       WildFly 30      │  │
│  │PostgreSQL│    │PostgreSQL│    │                      │  │
│  │  :5432   │    │  :5432   │    │  SagaOrchestrator    │  │
│  └────┬─────┘    └────┬─────┘    │  EvaluacionResource  │  │
│       │               │          │  EmailConsumerMDB    │  │
│       └───────────────┴──────────│  EmailService        │  │
│                                  └──────────┬───────────┘  │
│  ┌──────────────────────────────────────────┘              │
│  │                                                          │
│  │  ┌──────────┐                                           │
│  └─►│ RabbitMQ │◄── Mensajes de evaluación                │
│     │  :5672   │                                           │
│     └──────────┘                                           │
└─────────────────────────────────────────────────────────────┘
```

---

## Patrón Saga — Flujo de Transacción Distribuida

El sistema implementa el patrón **Saga Orchestration** para mantener la integridad entre dos bases de datos sin usar transacciones distribuidas XA (2PC).

### Flujo Normal (Happy Path)

```
Cliente HTTP
    │
    ▼
EvaluacionResource (POST /api/evaluacion)
    │
    ▼
SagaOrchestrator
    │
    ├─► PASO 1: Guardar ExamSubmission en exam_db ──► COMMIT local
    │
    ├─► PASO 2: Guardar Grade en student_db ────────► COMMIT local
    │
    └─► PASO 3: Publicar evento en RabbitMQ/Artemis ─► Async
                        │
                        ▼
               EmailConsumerMDB (MDB)
                        │
                        ▼
               EmailService ──► Gmail SMTP ──► Correo del estudiante
```

### Flujo de Compensación (Rollback Distribuido)

Si algún paso falla, la Saga ejecuta compensaciones en orden inverso:

```
FALLO en PASO 2
    │
    ├─► Compensar PASO 1: DELETE ExamSubmission de exam_db
    │
    └─► Registrar estado COMPENSATED en saga_executions
```

---

## Componentes del Código

### Capa de Presentación
- **`EvaluacionResource.java`** — Endpoint REST `POST /api/evaluacion` y `GET /api/evaluacion/health`

### Capa de Negocio
- **`SagaOrchestrator.java`** — Orquestador de la Saga. Coordina los 3 pasos y las compensaciones
- **`EmailConsumerMDB.java`** — Message-Driven Bean que consume la cola `EvaluationResults` y activa el envío de emails
- **`EmailService.java`** — Servicio de envío de correos via Jakarta Mail / Gmail SMTP

### Modelos
- **`ExamSubmission.java`** — Entidad JPA para `exam_db.exam_submissions`
- **`Grade.java`** — Entidad JPA para `student_db.grades`
- **`Student.java`** — Entidad JPA para `student_db.students`
- **`SagaExecution.java`** — Log de auditoría en `exam_db.saga_executions`
- **`EvaluacionPayload.java`** — DTO de entrada del endpoint REST

### Configuración
- **`persistence.xml`** — Define las dos unidades de persistencia: `ExamPU` y `StudentPU`
- **`configure.cli`** — Script CLI de WildFly para configurar datasources, mail session y cola JMS
- **`Dockerfile.wildfly`** — Build multi-etapa: compila el WAR con Maven y construye la imagen WildFly
- **`docker-compose.yml`** — Orquestación de los 4 contenedores

---

## Requisitos Previos

- Docker Desktop instalado y corriendo
- Cuenta de Gmail con verificación en 2 pasos activada
- Contraseña de aplicación de Gmail generada en https://myaccount.google.com/apppasswords

---

## Configuración Inicial

### 1. Clonar el repositorio

```bash
git clone <url-del-repositorio>
cd taller-completo/project
```

### 2. Configurar la contraseña de Gmail

Edita el archivo `wildfly-config/configure.cli` y actualiza estas dos líneas en la sección de mail:

```
/subsystem=mail/mail-session=default/server=smtp:write-attribute(name=username, value=TU_CORREO@gmail.com)
/subsystem=mail/mail-session=default/server=smtp:write-attribute(name=password, value=TU_CONTRASEÑA_DE_APLICACION)
```

También actualiza el remitente en `src/main/java/co/universidad/taller/service/EmailService.java`:

```java
msg.setFrom(new InternetAddress("TU_CORREO@gmail.com"));
```

---

## Cómo Correrlo

### Levantar todos los servicios

```bash
docker compose up --build
```

### Primera vez (limpieza total)

```bash
docker compose down -v
docker compose build --no-cache
docker compose up
```

### Detener los servicios

```bash
docker compose down
```

---

## Cómo Probarlo

### 1. Verificar que el WAR está desplegado

Abre http://localhost:9990 en el navegador.
- Usuario: `admin`
- Contraseña: `admin123`

Ve a **Deployments** y verifica que `taller-distribuido.war` aparece como **OK**.

### 2. Health Check

```bash
curl http://localhost:8080/taller-distribuido/api/evaluacion/health
```

Respuesta esperada:
```json
{"status":"UP","service":"saga-orchestrator"}
```

### 3. Registrar una evaluación

```bash
curl -X POST http://localhost:8080/taller-distribuido/api/evaluacion \
  -H "Content-Type: application/json" \
  -d '{
    "studentId": "S001",
    "examId": "EXAM-001",
    "respuestas": [],
    "puntaje": 85.0
  }'
```

Respuesta exitosa:
```json
{
  "status": "ok",
  "submissionId": "uuid-del-examen",
  "gradeId": "uuid-de-la-nota"
}
```

Los estudiantes disponibles son `S001`, `S002` y `S003`. Cada combinación `studentId + examId` solo puede usarse una vez.

### 4. Verificar los datos en las bases de datos

```bash
# Exámenes guardados en exam_db
docker exec -it exam_db psql -U user -d exam_db -c "SELECT * FROM exam_submissions;"

# Notas guardadas en student_db
docker exec -it student_db psql -U user -d student_db -c "SELECT * FROM grades;"

# Log de auditoría de la Saga
docker exec -it exam_db psql -U user -d exam_db -c "SELECT * FROM saga_executions;"
```

### 5. Verificar el envío de email

```bash
# Windows CMD
docker logs wildfly 2>&1 | findstr /i "EMAIL"

# Linux/Mac
docker logs wildfly 2>&1 | grep -i "EMAIL"
```

Líneas esperadas en los logs:
```
[EMAIL MDB] Procesando: {...}
[EMAIL] Enviado a maria@universidad.co
[EMAIL MDB] Email enviado a maria@universidad.co
```

### 6. Probar la compensación (rollback distribuido)

Envía el mismo `studentId + examId` dos veces. El segundo intento debe activar la compensación:

```bash
# Primera vez — exitoso
curl -X POST http://localhost:8080/taller-distribuido/api/evaluacion \
  -H "Content-Type: application/json" \
  -d '{"studentId":"S001","examId":"EXAM-COMP","respuestas":[],"puntaje":80.0}'

# Segunda vez — activa compensación
curl -X POST http://localhost:8080/taller-distribuido/api/evaluacion \
  -H "Content-Type: application/json" \
  -d '{"studentId":"S001","examId":"EXAM-COMP","respuestas":[],"puntaje":80.0}'
```

### 7. RabbitMQ Management Console

Abre http://localhost:15672
- Usuario: `guest`
- Contraseña: `guest`

Ve a la pestaña **Queues** para ver el estado de la cola `evaluation.results`.

---

## Interfaces Web

| URL | Descripción | Credenciales |
|-----|-------------|-------------|
| http://localhost:8080/taller-distribuido | Aplicación principal | — |
| http://localhost:9990 | WildFly Admin Console | admin / admin123 |
| http://localhost:15672 | RabbitMQ Management | guest / guest |

---

## Decisiones de Diseño

### ¿Por qué Saga y no transacciones distribuidas XA (2PC)?

El protocolo Two-Phase Commit requiere que todas las bases de datos participantes soporten coordinación distribuida, introduce latencia significativa y puede generar bloqueos si el coordinador falla. La Saga resuelve esto con transacciones locales independientes y compensaciones explícitas, siendo más resiliente y escalable.

### ¿Por qué `NOT_SUPPORTED` en el orquestador?

El orquestador usa `@TransactionAttribute(NOT_SUPPORTED)` para desactivar JTA global. Cada paso usa `REQUIRES_NEW` para crear su propia transacción local que se commitea o revierte de forma independiente, sin coordinación distribuida.

### ¿Por qué mensajería asíncrona para el email?

El email se procesa de forma asíncrona a través de ActiveMQ Artemis (embebido en WildFly). Esto desacopla el tiempo de respuesta HTTP del tiempo de entrega del correo — el cliente recibe `200 OK` inmediatamente, y el email llega en segundos sin bloquear la respuesta.
