package com.biblioteca.fnprestamos;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.models.CloudEvent;
import com.azure.core.models.CloudEventDataFormat;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;
import com.biblioteca.fnprestamos.model.Prestamo;
import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Funcion serverless del dominio Prestamos.
 *
 * Ademas del CRUD REST tradicional, actua como PRODUCTOR de eventos:
 * cada vez que se crea un prestamo, publica un evento "PrestamoCreado"
 * al topic de Azure Event Grid. Otras funciones (subscribers) reaccionan
 * a ese evento de forma asincrona y desacoplada.
 */
public class PrestamoFunction {

    // Almacen en memoria - este modulo es la "fuente de verdad" de los prestamos
    private static final Map<Long, Prestamo> prestamos = new HashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();

    // Cliente de Event Grid (lazy: se crea solo cuando se necesita publicar el primer evento)
    private static EventGridPublisherClient<CloudEvent> eventGridClient;

    // Datos dummy iniciales
    static {
        Prestamo p1 = new Prestamo(idCounter.getAndIncrement(), "Juan Perez", "Don Quijote", "2026-03-20", null, "ACTIVO");
        Prestamo p2 = new Prestamo(idCounter.getAndIncrement(), "Maria Lopez", "Cien Anos de Soledad", "2026-03-15", "2026-03-25", "DEVUELTO");
        prestamos.put(p1.getId(), p1);
        prestamos.put(p2.getId(), p2);
    }

    /**
     * Construye y cachea el cliente de Azure Event Grid.
     * Lee endpoint y key desde variables de entorno (configuradas en Azure
     * Application Settings) para no exponer credenciales en el codigo.
     */
    private static synchronized EventGridPublisherClient<CloudEvent> getEventGridClient() {
        if (eventGridClient == null) {
            String endpoint = System.getenv("EVENT_GRID_ENDPOINT");
            String key = System.getenv("EVENT_GRID_KEY");
            if (endpoint == null || key == null) {
                throw new IllegalStateException(
                        "EVENT_GRID_ENDPOINT y EVENT_GRID_KEY deben estar configurados");
            }
            eventGridClient = new EventGridPublisherClientBuilder()
                    .endpoint(endpoint)
                    .credential(new AzureKeyCredential(key))
                    .buildCloudEventPublisherClient();   // formato CloudEvents v1.0
        }
        return eventGridClient;
    }

    /**
     * PUBLICA EL EVENTO al Event Grid Topic.
     *
     * Construye un CloudEvent (estandar abierto) con:
     *   - source:  quien publica el evento  -> "biblioteca/fn-prestamos"
     *   - type:    nombre del evento         -> "PrestamoCreado"
     *   - subject: identificador del recurso -> "biblioteca/prestamos/{id}"
     *   - data:    el prestamo completo en JSON
     *
     * El evento queda disponible para todos los subscribers en paralelo
     * (fan-out): fn-notificaciones y fn-auditoria. Esta funcion NO sabe
     * quienes son los consumidores, lo cual es la esencia de EDA.
     */
    private static void publishPrestamoCreado(Prestamo prestamo, ExecutionContext context) {
        try {
            CloudEvent event = new CloudEvent(
                    "biblioteca/fn-prestamos",                        // source
                    "PrestamoCreado",                                  // type
                    BinaryData.fromObject(prestamo),                   // data (payload)
                    CloudEventDataFormat.JSON,
                    "application/json"
            ).setSubject("biblioteca/prestamos/" + prestamo.getId());

            getEventGridClient().sendEvent(event);                     // <- envio asincrono
            context.getLogger().info("Evento PrestamoCreado publicado a Event Grid para prestamo id=" + prestamo.getId());
        } catch (Exception e) {
            // Si falla la publicacion no rompemos el CRUD: el prestamo ya esta guardado.
            context.getLogger().warning("No se pudo publicar evento a Event Grid: " + e.getMessage());
        }
    }

    @FunctionName("getPrestamos")
    public HttpResponseMessage get(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "prestamos/{id=null}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("GET /api/prestamos" + (id != null ? "/" + id : ""));

        if (id == null || id.equals("null")) {
            List<Prestamo> lista = new ArrayList<>(prestamos.values());
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(lista))
                    .build();
        } else {
            Long prestamoId = Long.parseLong(id);
            Prestamo prestamo = prestamos.get(prestamoId);
            if (prestamo == null) {
                return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("{\"error\": \"Prestamo no encontrado\"}")
                        .build();
            }
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(prestamo))
                    .build();
        }
    }

    @FunctionName("createPrestamo")
    public HttpResponseMessage create(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "prestamos")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/prestamos");

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Prestamo prestamo = gson.fromJson(body, Prestamo.class);
        prestamo.setId(idCounter.getAndIncrement());
        if (prestamo.getEstado() == null) {
            prestamo.setEstado("ACTIVO");
        }
        prestamos.put(prestamo.getId(), prestamo);     // 1) guarda en memoria

        // 2) Publica el evento "PrestamoCreado" al Event Grid Topic.
        //    Esto dispara el flujo asincrono: el evento llega en paralelo
        //    a fn-notificaciones (simula email) y fn-auditoria (registra log).
        publishPrestamoCreado(prestamo, context);

        return request.createResponseBuilder(HttpStatus.CREATED)
                .header("Content-Type", "application/json")
                .body(gson.toJson(prestamo))
                .build();
    }

    @FunctionName("updatePrestamo")
    public HttpResponseMessage update(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.PUT},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "prestamos/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("PUT /api/prestamos/" + id);

        Long prestamoId = Long.parseLong(id);
        if (!prestamos.containsKey(prestamoId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Prestamo no encontrado\"}")
                    .build();
        }

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Prestamo prestamo = gson.fromJson(body, Prestamo.class);
        prestamo.setId(prestamoId);
        prestamos.put(prestamoId, prestamo);

        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(prestamo))
                .build();
    }

    @FunctionName("deletePrestamo")
    public HttpResponseMessage delete(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.DELETE},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "prestamos/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("DELETE /api/prestamos/" + id);

        Long prestamoId = Long.parseLong(id);
        if (!prestamos.containsKey(prestamoId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Prestamo no encontrado\"}")
                    .build();
        }

        prestamos.remove(prestamoId);

        return request.createResponseBuilder(HttpStatus.NO_CONTENT).build();
    }
}
