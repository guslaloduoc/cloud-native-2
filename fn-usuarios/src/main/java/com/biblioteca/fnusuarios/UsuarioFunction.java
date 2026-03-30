package com.biblioteca.fnusuarios;

import com.biblioteca.fnusuarios.model.Usuario;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class UsuarioFunction {

    private static final Map<Long, Usuario> usuarios = new HashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();

    // Datos dummy iniciales
    static {
        Usuario u1 = new Usuario(idCounter.getAndIncrement(), "Juan Perez", "juan@mail.com", "912345678");
        Usuario u2 = new Usuario(idCounter.getAndIncrement(), "Maria Lopez", "maria@mail.com", "987654321");
        usuarios.put(u1.getId(), u1);
        usuarios.put(u2.getId(), u2);
    }

    // GET /api/usuarios - Listar todos
    // GET /api/usuarios/{id} - Buscar por ID
    @FunctionName("getUsuarios")
    public HttpResponseMessage get(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "usuarios/{id=null}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("GET /api/usuarios" + (id != null ? "/" + id : ""));

        if (id == null || id.equals("null")) {
            // Listar todos
            List<Usuario> lista = new ArrayList<>(usuarios.values());
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(lista))
                    .build();
        } else {
            // Buscar por ID
            Long userId = Long.parseLong(id);
            Usuario usuario = usuarios.get(userId);
            if (usuario == null) {
                return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("{\"error\": \"Usuario no encontrado\"}")
                        .build();
            }
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(usuario))
                    .build();
        }
    }

    // POST /api/usuarios - Crear usuario
    @FunctionName("createUsuario")
    public HttpResponseMessage create(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "usuarios")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/usuarios");

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Usuario usuario = gson.fromJson(body, Usuario.class);
        usuario.setId(idCounter.getAndIncrement());
        usuarios.put(usuario.getId(), usuario);

        return request.createResponseBuilder(HttpStatus.CREATED)
                .header("Content-Type", "application/json")
                .body(gson.toJson(usuario))
                .build();
    }

    // PUT /api/usuarios/{id} - Actualizar usuario
    @FunctionName("updateUsuario")
    public HttpResponseMessage update(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.PUT},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "usuarios/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("PUT /api/usuarios/" + id);

        Long userId = Long.parseLong(id);
        if (!usuarios.containsKey(userId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Usuario no encontrado\"}")
                    .build();
        }

        String body = request.getBody().orElse(null);
        if (body == null) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"Body es requerido\"}")
                    .build();
        }

        Usuario usuario = gson.fromJson(body, Usuario.class);
        usuario.setId(userId);
        usuarios.put(userId, usuario);

        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(usuario))
                .build();
    }

    // DELETE /api/usuarios/{id} - Eliminar usuario
    @FunctionName("deleteUsuario")
    public HttpResponseMessage delete(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.DELETE},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "usuarios/{id}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id,
            final ExecutionContext context) {

        context.getLogger().info("DELETE /api/usuarios/" + id);

        Long userId = Long.parseLong(id);
        if (!usuarios.containsKey(userId)) {
            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Usuario no encontrado\"}")
                    .build();
        }

        usuarios.remove(userId);

        return request.createResponseBuilder(HttpStatus.NO_CONTENT).build();
    }
}
