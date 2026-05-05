package com.biblioteca.fnauditoria;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscriber 2 del Event Grid - Auditoria.
 *
 * Activada por Azure Event Grid al recibir el evento "PrestamoCreado".
 * Su responsabilidad es registrar un log de auditoria persistente con
 * todos los metadatos del evento (type, source, subject, time, payload).
 *
 * Junto con fn-notificaciones demuestra el patron FAN-OUT: el mismo
 * evento llega a las dos funciones simultaneamente, cada una con un
 * proposito distinto. El productor (fn-prestamos) no las conoce y no
 * tendria que cambiar si manana se agrega un tercer subscriber.
 */
public class AuditoriaFunction {

    private static final Gson gson = new Gson();
    // Almacen en memoria del log de auditoria (visible via GET /auditoria)
    private static final List<Map<String, Object>> registrosAuditoria = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicLong idCounter = new AtomicLong(1);

    /**
     * EVENT GRID TRIGGER (subscriber).
     * Mismo mecanismo que fn-notificaciones: la anotacion @EventGridTrigger
     * indica al runtime que esta funcion se ejecuta automaticamente cuando
     * Event Grid entrega un evento del subscription "sub-auditoria".
     */
    @FunctionName("auditarPrestamoCreado")
    public void auditarPrestamoCreado(
            @EventGridTrigger(name = "event") String eventJson,
            final ExecutionContext context) {

        context.getLogger().info("[fn-auditoria] Evento recibido: " + eventJson);

        try {
            // 1) Parsear el CloudEvent (estructura estandar v1.0)
            JsonObject root = JsonParser.parseString(eventJson).getAsJsonObject();
            String eventType = root.has("type") ? root.get("type").getAsString()
                    : (root.has("eventType") ? root.get("eventType").getAsString() : "Unknown");
            String source = root.has("source") ? root.get("source").getAsString() : "desconocido";
            String subject = root.has("subject") ? root.get("subject").getAsString() : "";
            String time = root.has("time") ? root.get("time").getAsString() : OffsetDateTime.now().toString();

            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data")
                    : new JsonObject();

            // 2) Construir el registro de auditoria con TODOS los metadatos del evento
            Map<String, Object> registro = new LinkedHashMap<>();
            registro.put("id", idCounter.getAndIncrement());
            registro.put("eventType", eventType);
            registro.put("source", source);
            registro.put("subject", subject);
            registro.put("eventTime", time);
            registro.put("recibidoEn", OffsetDateTime.now().toString());
            registro.put("payload", gson.fromJson(data.toString(), Map.class));
            // 3) Persistir en memoria para que pueda consultarse por GET /auditoria
            registrosAuditoria.add(registro);

            context.getLogger().info("[fn-auditoria] Registro #" + registro.get("id")
                    + " almacenado para evento " + eventType);
        } catch (Exception e) {
            context.getLogger().warning("[fn-auditoria] Error procesando evento: " + e.getMessage());
        }
    }

    @FunctionName("listarAuditoria")
    public HttpResponseMessage listar(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "auditoria")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("GET /api/auditoria (total=" + registrosAuditoria.size() + ")");
        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(registrosAuditoria))
                .build();
    }
}
