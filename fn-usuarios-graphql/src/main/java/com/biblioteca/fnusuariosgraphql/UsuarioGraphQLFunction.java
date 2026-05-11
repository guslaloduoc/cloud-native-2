package com.biblioteca.fnusuariosgraphql;

import com.biblioteca.fnusuariosgraphql.model.Usuario;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;

/**
 * GraphQL para Usuarios. NO mantiene estado propio: delega a fn-usuarios REST
 * (única fuente de verdad para usuarios) y a fn-prestamos REST (única fuente
 * de verdad para préstamos) para resolver consultas cross-domain.
 *
 * Aporta valor sobre REST: filtros, paginación, agregaciones y la unión
 * de un usuario con sus préstamos en una sola query.
 */
public class UsuarioGraphQLFunction {

    private static final Gson gson = new Gson();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String FN_USUARIOS_URL = envOrDefault("FN_USUARIOS_URL", "http://localhost:7071/api");
    private static final String FN_PRESTAMOS_URL = envOrDefault("FN_PRESTAMOS_URL", "http://localhost:7072/api");

    private static final GraphQL graphQL = buildGraphQL();

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static GraphQL buildGraphQL() {
        String schema = ""
                + "type Usuario {\n"
                + "  id: ID!\n"
                + "  nombre: String!\n"
                + "  email: String!\n"
                + "  telefono: String!\n"
                + "  prestamos: [Prestamo]\n"
                + "  cantidadPrestamos: Int\n"
                + "  prestamosActivos: Int\n"
                + "}\n"
                + "\n"
                + "type Prestamo {\n"
                + "  id: ID!\n"
                + "  usuarioNombre: String!\n"
                + "  libroTitulo: String!\n"
                + "  fechaPrestamo: String!\n"
                + "  fechaDevolucion: String\n"
                + "  estado: String!\n"
                + "}\n"
                + "\n"
                + "type EstadisticasUsuarios {\n"
                + "  total: Int!\n"
                + "  dominiosUnicos: Int!\n"
                + "  conPrestamosActivos: Int!\n"
                + "  sinPrestamos: Int!\n"
                + "}\n"
                + "\n"
                + "type Query {\n"
                + "  usuarios: [Usuario]\n"
                + "  usuario(id: ID!): Usuario\n"
                + "  buscarUsuarios(nombre: String, dominioEmail: String): [Usuario]\n"
                + "  usuariosPaginados(page: Int = 0, size: Int = 10): [Usuario]\n"
                + "  estadisticasUsuarios: EstadisticasUsuarios\n"
                + "}\n"
                + "\n"
                + "type Mutation {\n"
                + "  crearUsuario(nombre: String!, email: String!, telefono: String!): Usuario\n"
                + "  actualizarUsuario(id: ID!, nombre: String!, email: String!, telefono: String!): Usuario\n"
                + "  eliminarUsuario(id: ID!): Boolean\n"
                + "}\n";

        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(schema);

        RuntimeWiring wiring = newRuntimeWiring()
                .type("Query", b -> b
                        .dataFetcher("usuarios", env -> restGetAllUsuarios())
                        .dataFetcher("usuario", env -> restGetUsuarioById(Long.parseLong(env.getArgument("id"))))
                        .dataFetcher("buscarUsuarios", env -> {
                            String nombre = env.getArgument("nombre");
                            String dominio = env.getArgument("dominioEmail");
                            return restGetAllUsuarios().stream()
                                    .filter(u -> nombre == null
                                            || u.getNombre().toLowerCase().contains(nombre.toLowerCase()))
                                    .filter(u -> dominio == null
                                            || u.getEmail().toLowerCase().contains(dominio.toLowerCase()))
                                    .collect(Collectors.toList());
                        })
                        .dataFetcher("usuariosPaginados", env -> {
                            int page = env.getArgumentOrDefault("page", 0);
                            int size = env.getArgumentOrDefault("size", 10);
                            List<Usuario> all = restGetAllUsuarios();
                            int from = Math.min(page * size, all.size());
                            int to = Math.min(from + size, all.size());
                            return all.subList(from, to);
                        })
                        .dataFetcher("estadisticasUsuarios", env -> {
                            List<Usuario> users = restGetAllUsuarios();
                            List<Map<String, Object>> prestamos = restGetAllPrestamos();

                            Set<String> conActivo = prestamos.stream()
                                    .filter(p -> "ACTIVO".equals(p.get("estado")))
                                    .map(p -> (String) p.get("usuarioNombre"))
                                    .collect(Collectors.toSet());
                            Set<String> conAlgunPrestamo = prestamos.stream()
                                    .map(p -> (String) p.get("usuarioNombre"))
                                    .collect(Collectors.toSet());

                            long conActivos = users.stream()
                                    .filter(u -> conActivo.contains(u.getNombre()))
                                    .count();
                            long sinPrestamos = users.stream()
                                    .filter(u -> !conAlgunPrestamo.contains(u.getNombre()))
                                    .count();
                            long dominios = users.stream()
                                    .map(UsuarioGraphQLFunction::dominioEmail)
                                    .filter(s -> !s.isEmpty())
                                    .distinct()
                                    .count();

                            Map<String, Object> stats = new LinkedHashMap<>();
                            stats.put("total", users.size());
                            stats.put("dominiosUnicos", (int) dominios);
                            stats.put("conPrestamosActivos", (int) conActivos);
                            stats.put("sinPrestamos", (int) sinPrestamos);
                            return stats;
                        })
                )
                .type("Usuario", b -> b
                        .dataFetcher("prestamos", env -> {
                            Usuario u = env.getSource();
                            return restGetAllPrestamos().stream()
                                    .filter(p -> u.getNombre().equals(p.get("usuarioNombre")))
                                    .collect(Collectors.toList());
                        })
                        .dataFetcher("cantidadPrestamos", env -> {
                            Usuario u = env.getSource();
                            return (int) restGetAllPrestamos().stream()
                                    .filter(p -> u.getNombre().equals(p.get("usuarioNombre")))
                                    .count();
                        })
                        .dataFetcher("prestamosActivos", env -> {
                            Usuario u = env.getSource();
                            return (int) restGetAllPrestamos().stream()
                                    .filter(p -> u.getNombre().equals(p.get("usuarioNombre")))
                                    .filter(p -> "ACTIVO".equals(p.get("estado")))
                                    .count();
                        })
                )
                .type("Mutation", b -> b
                        .dataFetcher("crearUsuario", env -> restPostUsuario(usuarioBody(env)))
                        .dataFetcher("actualizarUsuario", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            return restPutUsuario(id, usuarioBody(env));
                        })
                        .dataFetcher("eliminarUsuario", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            return restDeleteUsuario(id);
                        })
                )
                .build();

        GraphQLSchema graphQLSchema = new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
        return GraphQL.newGraphQL(graphQLSchema).build();
    }

    private static String dominioEmail(Usuario u) {
        String e = u.getEmail() == null ? "" : u.getEmail();
        int at = e.indexOf('@');
        return at >= 0 ? e.substring(at + 1) : "";
    }

    private static Map<String, Object> usuarioBody(graphql.schema.DataFetchingEnvironment env) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nombre", env.getArgument("nombre"));
        body.put("email", env.getArgument("email"));
        body.put("telefono", env.getArgument("telefono"));
        return body;
    }

    @FunctionName("usuariosGraphQL")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/graphql - Usuarios GraphQL -> usuarios=" + FN_USUARIOS_URL
                + " prestamos=" + FN_PRESTAMOS_URL);

        String body = request.getBody().orElse(null);
        if (body == null || body.isEmpty()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body("{\"error\": \"Body es requerido con la query GraphQL\"}")
                    .build();
        }

        // Parseo defensivo: rechazamos con 400 los bodies que no son JSON
        // valido o no contienen el campo 'query'. Sin esto un body como
        // "{}" o "{\"variables\":{}}" hacia crashear el endpoint con NPE
        // (criterios 3 y 4 de la pauta: integracion REST+GraphQL robusta).
        JsonObject jsonBody;
        try {
            jsonBody = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body("{\"error\": \"Body JSON invalido\"}")
                    .build();
        }
        if (!jsonBody.has("query") || jsonBody.get("query").isJsonNull()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body("{\"error\": \"El campo 'query' es requerido\"}")
                    .build();
        }
        String query = jsonBody.get("query").getAsString();

        Map<String, Object> variables = new HashMap<>();
        if (jsonBody.has("variables") && !jsonBody.get("variables").isJsonNull()) {
            variables = gson.fromJson(jsonBody.get("variables"), Map.class);
        }

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .build();

        ExecutionResult executionResult = graphQL.execute(executionInput);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", executionResult.getData());
        if (!executionResult.getErrors().isEmpty()) {
            result.put("errors", executionResult.getErrors());
        }

        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(gson.toJson(result))
                .build();
    }

    // ===== HTTP helpers contra fn-usuarios REST =====

    private static List<Usuario> restGetAllUsuarios() {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
        if (resp.statusCode() != 200) return Collections.emptyList();
        Type t = new TypeToken<List<Usuario>>() {}.getType();
        List<Usuario> list = gson.fromJson(resp.body(), t);
        return list != null ? list : Collections.emptyList();
    }

    private static Usuario restGetUsuarioById(Long id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios/" + id))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
        if (resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Usuario.class);
    }

    private static Usuario restPostUsuario(Map<String, Object> body) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build());
        if (resp.statusCode() != 201 && resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Usuario.class);
    }

    private static Usuario restPutUsuario(Long id, Map<String, Object> body) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios/" + id))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build());
        if (resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Usuario.class);
    }

    private static Boolean restDeleteUsuario(Long id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios/" + id))
                .timeout(Duration.ofSeconds(15))
                .DELETE()
                .build());
        return resp.statusCode() == 204 || resp.statusCode() == 200;
    }

    // ===== HTTP helper cross-domain contra fn-prestamos REST =====

    private static List<Map<String, Object>> restGetAllPrestamos() {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
        if (resp.statusCode() != 200) return Collections.emptyList();
        Type t = new TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> list = gson.fromJson(resp.body(), t);
        return list != null ? list : Collections.emptyList();
    }

    private static HttpResponse<String> send(HttpRequest req) {
        try {
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Error HTTP: " + e.getMessage(), e);
        }
    }
}
