package com.biblioteca.fnprestamos;

import com.biblioteca.fnprestamos.model.Prestamo;
import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class PrestamoFunction {

    private static final Map<Long, Prestamo> prestamos = new HashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();

    // Datos dummy iniciales
    static {
        Prestamo p1 = new Prestamo(idCounter.getAndIncrement(), "Juan Perez", "Don Quijote", "2026-03-20", null, "ACTIVO");
        Prestamo p2 = new Prestamo(idCounter.getAndIncrement(), "Maria Lopez", "Cien Anos de Soledad", "2026-03-15", "2026-03-25", "DEVUELTO");
        prestamos.put(p1.getId(), p1);
        prestamos.put(p2.getId(), p2);
    }

    // GET /api/prestamos - Listar todos
    // GET /api/prestamos/{id} - Buscar por ID
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

    // POST /api/prestamos - Crear prestamo
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
        prestamos.put(prestamo.getId(), prestamo);

        return request.createResponseBuilder(HttpStatus.CREATED)
                .header("Content-Type", "application/json")
                .body(gson.toJson(prestamo))
                .build();
    }

    // PUT /api/prestamos/{id} - Actualizar prestamo
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

    // DELETE /api/prestamos/{id} - Eliminar prestamo
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
