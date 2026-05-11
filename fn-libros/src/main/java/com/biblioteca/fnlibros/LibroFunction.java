package com.biblioteca.fnlibros;

import com.biblioteca.fnlibros.model.Libro;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Funcion serverless del dominio Libros.
 *
 * Tiene dos responsabilidades:
 *
 *   1) CRUD REST tradicional sobre el catalogo de libros (id, titulo,
 *      autor, disponibilidad). Endpoints en /api/libros.
 *
 *   2) SUSCRIPTOR de eventos: escucha "PrestamoCreado" publicado por
 *      fn-prestamos y decrementa automaticamente la disponibilidad del
 *      libro asociado. Esto implementa el requisito del enunciado:
 *      "al crear un prestamo se debe restar en 1 la disponibilidad
 *       general del libro".
 *
 * El acoplamiento entre fn-prestamos y fn-libros es eventual: fn-prestamos
 * no sabe que fn-libros existe; solo publica el evento al topic. Si se
 * apagara fn-libros, los prestamos seguirian funcionando (la cascada
 * de stock simplemente no se aplicaria, pudiendo reprocesar despues).
 */
public class LibroFunction {

    // Almacen en memoria - este modulo es la fuente de verdad del catalogo.
    // Usamos ConcurrentHashMap porque Azure Functions puede ejecutar
    // varias invocaciones concurrentes (HTTP + EventGrid trigger) sobre
    // el mismo proceso; un HashMap normal expondria race conditions al
    // decrementar disponibilidad por evento.
    private static final Map<Long, Libro> libros = new ConcurrentHashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();

    // Datos dummy iniciales: titulos coinciden con los prestamos dummy
    // de fn-prestamos para que la cascada de stock sea visible al instante.
    static {
        Libro l1 = new Libro(idCounter.getAndIncrement(), "Don Quijote", "Miguel de Cervantes", 5);
        Libro l2 = new Libro(idCounter.getAndIncrement(), "Cien Anos de Soledad", "Gabriel Garcia Marquez", 5);
        Libro l3 = new Libro(idCounter.getAndIncrement(), "La Sombra del Viento", "Carlos Ruiz Zafon", 3);
        libros.put(l1.getId(), l1);
        libros.put(l2.getId(), l2);
        libros.put(l3.getId(), l3);
    }

    // GET /api/libros          - Listar todos
    // GET /api/libros/{id}     - Buscar por ID
    @FunctionName("getLibros")
    public HttpResponseMessage get(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "libros/{id=null}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("GET /api/libros" + (id != null ? "/" + id : ""));

        if (id == null || id.equals("null")) {
            List<Libro> lista = new ArrayList<>(libros.values());
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(lista))
                    .build();
        } else {
            Long libroId = Long.parseLong(id);
            Libro libro = libros.get(libroId);
            if (libro == null) {
                return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("{\"error\": \"Libro no encontrado\"}")
                        .build();
            }
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(libro))
                    .build();
        }
    }

    // POST /api/libros - Crear libro
    @FunctionName("createLibro")
    public HttpResponseMessage create(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "libros")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/libros");

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Libro libro = gson.fromJson(body, Libro.class);
        libro.setId(idCounter.getAndIncrement());
        if (libro.getDisponibilidad() == null) {
            libro.setDisponibilidad(0);
        }
        libros.put(libro.getId(), libro);

        return request.createResponseBuilder(HttpStatus.CREATED)
                .header("Content-Type", "application/json")
                .body(gson.toJson(libro))
                .build();
    }

    // PUT /api/libros/{id} - Actualizar libro
    @FunctionName("updateLibro")
    public HttpResponseMessage update(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.PUT},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "libros/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("PUT /api/libros/" + id);

        Long libroId = Long.parseLong(id);
        if (!libros.containsKey(libroId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Libro no encontrado\"}")
                    .build();
        }

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Libro libro = gson.fromJson(body, Libro.class);
        libro.setId(libroId);
        libros.put(libroId, libro);

        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(libro))
                .build();
    }

    // DELETE /api/libros/{id} - Eliminar libro
    @FunctionName("deleteLibro")
    public HttpResponseMessage delete(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.DELETE},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "libros/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("DELETE /api/libros/" + id);

        Long libroId = Long.parseLong(id);
        if (!libros.containsKey(libroId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Libro no encontrado\"}")
                    .build();
        }

        libros.remove(libroId);

        return request.createResponseBuilder(HttpStatus.NO_CONTENT).build();
    }

    /**
     * EVENT GRID TRIGGER - Suscriptor del evento "PrestamoCreado".
     *
     * Activada por Azure Event Grid cuando fn-prestamos publica
     * "PrestamoCreado" al topic biblioteca-eventos. La conexion se
     * configura mediante el subscription "sub-libros-stock" filtrado
     * por type=PrestamoCreado.
     *
     * Implementa el requisito del enunciado: "al crear un prestamo se
     * debe restar en 1 la disponibilidad general del libro".
     *
     * Estrategia de match: por libroTitulo (campo presente en el modelo
     * Prestamo y en el catalogo de libros). Si el libro no existe en el
     * catalogo solo se loguea, no se rompe el flujo.
     */
    @FunctionName("onPrestamoCreado")
    public void onPrestamoCreado(
            @EventGridTrigger(name = "event") String eventJson,
            final ExecutionContext context) {

        context.getLogger().info("[fn-libros] Evento recibido: " + eventJson);

        try {
            // 1) Parsear el CloudEvent
            JsonObject root = JsonParser.parseString(eventJson).getAsJsonObject();
            String eventType = root.has("type") ? root.get("type").getAsString()
                    : (root.has("eventType") ? root.get("eventType").getAsString() : "Unknown");

            // Defensa: si llega otro tipo de evento al subscription, ignorar
            if (!"PrestamoCreado".equals(eventType)) {
                context.getLogger().info("[fn-libros] Evento ignorado (no es PrestamoCreado): " + eventType);
                return;
            }

            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data")
                    : new JsonObject();

            String libroTitulo = data.has("libroTitulo") ? data.get("libroTitulo").getAsString() : null;
            if (libroTitulo == null) {
                context.getLogger().warning("[fn-libros] PrestamoCreado sin campo 'libroTitulo'; no se decrementa stock");
                return;
            }

            // 2) Buscar el libro por titulo y decrementar disponibilidad.
            //    Match case-insensitive + trim para no fallar si el cliente
            //    envia el titulo con mayusculas/minusculas distintas o espacios
            //    al borde (robustez requerida por el criterio 5 de la pauta).
            final String tituloBuscado = libroTitulo.trim();
            Optional<Libro> opt = libros.values().stream()
                    .filter(l -> l.getTitulo() != null
                            && tituloBuscado.equalsIgnoreCase(l.getTitulo().trim()))
                    .findFirst();

            if (opt.isEmpty()) {
                context.getLogger().warning("[fn-libros] Libro '" + libroTitulo
                        + "' no esta en el catalogo; no se decrementa stock");
                return;
            }

            Libro libro = opt.get();
            int actual = libro.getDisponibilidad() == null ? 0 : libro.getDisponibilidad();
            if (actual <= 0) {
                context.getLogger().warning("[fn-libros] Libro '" + libroTitulo
                        + "' ya tiene disponibilidad 0; no se decrementa mas");
                return;
            }
            libro.setDisponibilidad(actual - 1);
            context.getLogger().info("[fn-libros] Disponibilidad de '" + libroTitulo
                    + "' decrementada: " + actual + " -> " + libro.getDisponibilidad());
        } catch (Exception e) {
            context.getLogger().warning("[fn-libros] Error procesando PrestamoCreado: " + e.getMessage());
        }
    }
}
