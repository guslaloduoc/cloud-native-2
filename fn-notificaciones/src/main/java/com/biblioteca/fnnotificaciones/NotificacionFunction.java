package com.biblioteca.fnnotificaciones;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscriber 1 del Event Grid - Notificaciones.
 *
 * Esta funcion NO se invoca con HTTP. Es activada automaticamente por
 * Azure Event Grid cada vez que se publica un evento "PrestamoCreado"
 * en el topic biblioteca-eventos. Su responsabilidad es simular el
 * envio de un correo de confirmacion al usuario que tomo el prestamo.
 *
 * Forma parte del patron fan-out junto con fn-auditoria: ambas
 * subscribers reciben el mismo evento en paralelo y reaccionan de
 * forma independiente.
 */
public class NotificacionFunction {

    private static final Gson gson = new Gson();
    // Almacen en memoria de las notificaciones enviadas (visible via GET /notificaciones)
    private static final List<Map<String, Object>> notificaciones = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicLong idCounter = new AtomicLong(1);

    /**
     * EVENT GRID TRIGGER.
     *
     * La anotacion @EventGridTrigger le dice al runtime de Azure Functions
     * que esta funcion debe ejecutarse cada vez que llega un evento al
     * subscription configurado. El parametro eventJson es el CloudEvent
     * completo en JSON (incluye type, source, subject, time, data, etc.).
     *
     * No hay endpoint HTTP: la conexion entre Event Grid y esta funcion
     * se configura desde Azure (en sub-notificaciones).
     */
    @FunctionName("onPrestamoCreado")
    public void onPrestamoCreado(
            @EventGridTrigger(name = "event") String eventJson,
            final ExecutionContext context) {

        context.getLogger().info("[fn-notificaciones] Evento recibido: " + eventJson);

        try {
            // 1) Parsear el CloudEvent recibido y extraer el campo "data"
            JsonObject root = JsonParser.parseString(eventJson).getAsJsonObject();
            String eventType = root.has("type") ? root.get("type").getAsString()
                    : (root.has("eventType") ? root.get("eventType").getAsString() : "Unknown");

            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data")
                    : new JsonObject();

            // 2) Leer del payload los datos relevantes para construir el "email"
            String usuarioNombre = data.has("usuarioNombre") ? data.get("usuarioNombre").getAsString() : "desconocido";
            String libroTitulo = data.has("libroTitulo") ? data.get("libroTitulo").getAsString() : "desconocido";

            // 3) Simular el envio del correo (en produccion aqui iria SendGrid/SMTP)
            String mensaje = String.format("Email simulado a %s: confirmado el prestamo del libro '%s'",
                    usuarioNombre, libroTitulo);

            Map<String, Object> registro = new LinkedHashMap<>();
            registro.put("id", idCounter.getAndIncrement());
            registro.put("eventType", eventType);
            registro.put("usuarioNombre", usuarioNombre);
            registro.put("libroTitulo", libroTitulo);
            registro.put("mensaje", mensaje);
            registro.put("recibidoEn", OffsetDateTime.now().toString());
            notificaciones.add(registro);

            context.getLogger().info("[fn-notificaciones] " + mensaje);
        } catch (Exception e) {
            context.getLogger().warning("[fn-notificaciones] Error procesando evento: " + e.getMessage());
        }
    }

    @FunctionName("listarNotificaciones")
    public HttpResponseMessage listar(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "notificaciones")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("GET /api/notificaciones (total=" + notificaciones.size() + ")");
        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(notificaciones))
                .build();
    }
}
