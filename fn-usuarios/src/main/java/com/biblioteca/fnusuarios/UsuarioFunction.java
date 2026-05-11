package com.biblioteca.fnusuarios;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.models.CloudEvent;
import com.azure.core.models.CloudEventDataFormat;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;
import com.biblioteca.fnusuarios.model.Usuario;
import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Funcion serverless del dominio Usuarios.
 *
 * Ademas del CRUD REST, actua como PRODUCTOR de eventos: cuando se
 * elimina un usuario publica un evento "UsuarioEliminado" al topic
 * de Azure Event Grid. Otras funciones (subscribers) reaccionan al
 * evento de forma asincrona; en particular fn-prestamos lo escucha
 * para eliminar en cascada los prestamos asociados al usuario.
 *
 * Mismo patron que fn-prestamos (productor de "PrestamoCreado").
 */
public class UsuarioFunction {

    // Almacen en memoria - fuente de verdad de usuarios.
    // ConcurrentHashMap por la misma razon que en fn-libros y fn-prestamos:
    // varias invocaciones concurrentes pueden tocar el mismo store.
    private static final Map<Long, Usuario> usuarios = new ConcurrentHashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();

    // Cliente de Event Grid (lazy: se crea solo cuando se necesita publicar)
    private static EventGridPublisherClient<CloudEvent> eventGridClient;

    // Datos dummy iniciales
    static {
        Usuario u1 = new Usuario(idCounter.getAndIncrement(), "Juan Perez", "juan@mail.com", "912345678");
        Usuario u2 = new Usuario(idCounter.getAndIncrement(), "Maria Lopez", "maria@mail.com", "987654321");
        usuarios.put(u1.getId(), u1);
        usuarios.put(u2.getId(), u2);
    }

    /**
     * Construye y cachea el cliente de Azure Event Grid.
     * Lee endpoint y key desde variables de entorno (Application Settings)
     * para no exponer credenciales en el codigo.
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
     * PUBLICA EL EVENTO "UsuarioEliminado" al Event Grid Topic.
     *
     * Estructura CloudEvent:
     *   - source:  "biblioteca/fn-usuarios"
     *   - type:    "UsuarioEliminado"
     *   - subject: "biblioteca/usuarios/{id}"
     *   - data:    snapshot del usuario eliminado (id + nombre + email + telefono)
     *
     * Esta funcion NO conoce a sus subscribers. fn-prestamos escucha el
     * evento via subscription "sub-usuarios-eliminados" y elimina en cascada
     * los prestamos del usuario. Cualquier otro modulo (auditoria, etc.)
     * podria suscribirse mas adelante sin tocar este codigo.
     */
    private static void publishUsuarioEliminado(Usuario usuario, ExecutionContext context) {
        try {
            CloudEvent event = new CloudEvent(
                    "biblioteca/fn-usuarios",                          // source
                    "UsuarioEliminado",                                // type
                    BinaryData.fromObject(usuario),                    // data
                    CloudEventDataFormat.JSON,
                    "application/json"
            ).setSubject("biblioteca/usuarios/" + usuario.getId());

            getEventGridClient().sendEvent(event);
            context.getLogger().info("Evento UsuarioEliminado publicado a Event Grid para usuario id=" + usuario.getId());
        } catch (Exception e) {
            // Si falla la publicacion no rompemos el CRUD: el usuario ya fue eliminado.
            // En produccion aqui iria una estrategia de outbox/retry.
            context.getLogger().warning("No se pudo publicar evento a Event Grid: " + e.getMessage());
        }
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
    // Ademas de borrar el registro local, publica un evento "UsuarioEliminado"
    // al Event Grid para que fn-prestamos elimine en cascada los prestamos
    // asociados (requisito del enunciado: "al eliminar un usuario se deben
    // eliminar tambien sus prestamos").
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

        // 1) Eliminar localmente y guardar snapshot para el evento
        Usuario eliminado = usuarios.remove(userId);

        // 2) Publicar evento "UsuarioEliminado" al Event Grid Topic.
        //    fn-prestamos lo consume y borra en cascada los prestamos del usuario.
        publishUsuarioEliminado(eliminado, context);

        return request.createResponseBuilder(HttpStatus.NO_CONTENT).build();
    }
}
