import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─────────────────────────────────────────────
//  Métricas personalizadas
// ─────────────────────────────────────────────
const sagaExitosas   = new Counter('saga_exitosas');
const sagaFallidas   = new Counter('saga_fallidas');
const tasaError      = new Rate('tasa_error');
const tiempoRespuesta = new Trend('tiempo_respuesta_ms', true);

// ─────────────────────────────────────────────
//  Configuración de la prueba
//  Fases: rampa subida → carga sostenida → pico → bajada
// ─────────────────────────────────────────────
export const options = {
  stages: [
    { duration: '30s', target: 5  }, // Calentamiento: sube a 5 usuarios
    { duration: '1m',  target: 5  }, // Carga base: mantiene 5 usuarios
    { duration: '30s', target: 20 }, // Sube a 20 usuarios
    { duration: '1m',  target: 20 }, // Carga media: mantiene 20 usuarios
    { duration: '30s', target: 50 }, // Pico: sube a 50 usuarios
    { duration: '1m',  target: 50 }, // Carga alta: mantiene 50 usuarios
    { duration: '30s', target: 0  }, // Enfriamiento: baja a 0
  ],
  thresholds: {
    // El 95% de requests debe responder en menos de 3 segundos
    'http_req_duration': ['p(95)<3000'],
    // La tasa de error no debe superar el 10%
    'tasa_error': ['rate<0.10'],
    // Al menos el 90% de requests debe ser exitoso
    'http_req_failed': ['rate<0.10'],
  },
};

// ─────────────────────────────────────────────
//  Estudiantes disponibles en la BD
// ─────────────────────────────────────────────
const ESTUDIANTES = ['S001', 'S002', 'S003'];

// ─────────────────────────────────────────────
//  Función principal — ejecutada por cada usuario virtual
//  __VU  = ID del usuario virtual (1, 2, 3...)
//  __ITER = iteración del usuario (0, 1, 2...)
// ─────────────────────────────────────────────
export default function () {

  // Seleccionar estudiante rotando entre los disponibles
  const studentId = ESTUDIANTES[(__VU - 1) % ESTUDIANTES.length];

  // examId único por usuario + iteración para evitar duplicados
  const examId = `EXAM-STRESS-VU${__VU}-IT${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    studentId:  studentId,
    examId:     examId,
    respuestas: [
      { preguntaId: '1', respuesta: 'A' },
      { preguntaId: '2', respuesta: 'C' },
    ],
    puntaje: Math.floor(Math.random() * 40) + 60, // puntaje entre 60 y 100
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  };

  const inicio = Date.now();
  const res = http.post(
  'http://localhost:8080/taller-distribuido/api/evaluacion',
    payload,
    params
  );
  console.log(`STATUS: ${res.status} | URL: ${res.url} | BODY: ${res.body.substring(0, 200)}`);
  const duracion = Date.now() - inicio;

  // ── Registrar métricas ──
  tiempoRespuesta.add(duracion);

  const exitoso = check(res, {
    'HTTP 200 OK':         (r) => r.status === 200,
    'respuesta tiene ok':  (r) => r.body && r.body.includes('"status":"ok"'),
    'tiempo < 3 segundos': (r) => r.timings.duration < 3000,
  });

  if (exitoso) {
    sagaExitosas.add(1);
    tasaError.add(false);
  } else {
    sagaFallidas.add(1);
    tasaError.add(true);
    console.log(`[FALLO] VU=${__VU} ITER=${__ITER} status=${res.status} body=${res.body}`);
  }

  // Pausa entre requests (simula usuario real)
  sleep(Math.random() * 2 + 0.5); // entre 0.5s y 2.5s
}

// ─────────────────────────────────────────────
//  Resumen al finalizar
// ─────────────────────────────────────────────
export function handleSummary(data) {
  const total    = (data.metrics.iterations?.values?.count) || 0;
  const exitosas = (data.metrics.saga_exitosas?.values?.count) || 0;
  const fallidas = (data.metrics.saga_fallidas?.values?.count) || 0;
  const p95      = data.metrics.http_req_duration?.values?.['p(95)']?.toFixed(0) || 'N/A';
  const p99      = data.metrics.http_req_duration?.values?.['p(99)']?.toFixed(0) || 'N/A';
  const avg      = data.metrics.http_req_duration?.values?.avg?.toFixed(0) || 'N/A';
  const rps      = data.metrics.http_reqs?.values?.rate?.toFixed(2) || 'N/A';

  const resumen = `
╔══════════════════════════════════════════════════════╗
║         REPORTE DE PRUEBA DE ESTRÉS — SAGA           ║
╠══════════════════════════════════════════════════════╣
║  Total de requests:       ${String(total).padEnd(26)}║
║  Sagas exitosas:          ${String(exitosas).padEnd(26)}║
║  Sagas fallidas:          ${String(fallidas).padEnd(26)}║
╠══════════════════════════════════════════════════════╣
║  Requests por segundo:    ${String(rps).padEnd(26)}║
║  Tiempo promedio:         ${String(avg + ' ms').padEnd(26)}║
║  Percentil 95 (p95):      ${String(p95 + ' ms').padEnd(26)}║
║  Percentil 99 (p99):      ${String(p99 + ' ms').padEnd(26)}║
╚══════════════════════════════════════════════════════╝
`;

  console.log(resumen);

  return {
    'stdout': resumen,
    'reporte-stress.json': JSON.stringify(data, null, 2),
  };
}
