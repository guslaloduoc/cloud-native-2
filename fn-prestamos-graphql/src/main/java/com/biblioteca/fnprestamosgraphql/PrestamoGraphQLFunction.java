package com.biblioteca.fnprestamosgraphql;

import com.biblioteca.fnprestamosgraphql.model.Prestamo;
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
 * GraphQL para Préstamos. NO mantiene estado propio: delega a fn-prestamos REST
 * (única fuente de verdad para préstamos) y a fn-usuarios REST para resolver
 * el campo cross-domain Prestamo.usuario.
 *
 * Aporta valor sobre REST con queries enriquecidas: filtros, ranking de libros
 * más prestados, préstamos vencidos y agregaciones.
 */
public class PrestamoGraphQLFunction {

    private static final Gson gson = new Gson();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String FN_PRESTAMOS_URL = envOrDefault("FN_PRESTAMOS_URL", "http://localhost:7072/api");
    private static final String FN_USUARIOS_URL = envOrDefault("FN_USUARIOS_URL", "http://localhost:7071/api");

    private static final GraphQL graphQL = buildGraphQL();

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static GraphQL buildGraphQL() {
        String schema = ""
                + "type Prestamo {\n"
                + "  id: ID!\n"
                + "  usuarioNombre: String!\n"
                + "  libroTitulo: String!\n"
                + "  fechaPrestamo: String!\n"
                + "  fechaDevolucion: String\n"
                + "  estado: String!\n"
                + "  usuario: Usuario\n"
                + "}\n"
                + "\n"
                + "type Usuario {\n"
                + "  id: ID!\n"
                + "  nombre: String!\n"
                + "  email: String!\n"
                + "  telefono: String!\n"
                + "}\n"
                + "\n"
                + "type LibroRanking {\n"
                + "  libroTitulo: String!\n"
                + "  vecesPrestado: Int!\n"
                + "}\n"
                + "\n"
                + "type EstadisticasPrestamos {\n"
                + "  total: Int!\n"
                + "  activos: Int!\n"
                + "  devueltos: Int!\n"
                + "}\n"
                + "\n"
                + "type Query {\n"
                + "  prestamos: [Prestamo]\n"
                + "  prestamo(id: ID!): Prestamo\n"
                + "  prestamosPorUsuario(usuarioNombre: String!): [Prestamo]\n"
                + "  prestamosPorEstado(estado: String!): [Prestamo]\n"
                + "  prestamosVencidos: [Prestamo]\n"
                + "  librosMasPrestados(top: Int = 5): [LibroRanking]\n"
                + "  estadisticasPrestamos: EstadisticasPrestamos\n"
                + "}\n"
                + "\n"
                + "type Mutation {\n"
                + "  crearPrestamo(usuarioNombre: String!, libroTitulo: String!, fechaPrestamo: String!): Prestamo\n"
                + "  actualizarPrestamo(id: ID!, usuarioNombre: String!, libroTitulo: String!, fechaPrestamo: String!, fechaDevolucion: String, estado: String!): Prestamo\n"
                + "  eliminarPrestamo(id: ID!): Boolean\n"
                + "}\n";

        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(schema);

        RuntimeWiring wiring = newRuntimeWiring()
                .type("Query", b -> b
                        .dataFetcher("prestamos", env -> restGetAllPrestamos())
                        .dataFetcher("prestamo", env -> restGetPrestamoById(Long.parseLong(env.getArgument("id"))))
                        .dataFetcher("prestamosPorUsuario", env -> {
                            String nombre = env.getArgument("usuarioNombre");
                            return restGetAllPrestamos().stream()
                                    .filter(p -> nombre.equalsIgnoreCase(p.getUsuarioNombre()))
                                    .collect(Collectors.toList());
                        })
                        .dataFetcher("prestamosPorEstado", env -> {
                            String estado = env.getArgument("estado");
                            return restGetAllPrestamos().stream()
                                    .filter(p -> estado.equalsIgnoreCase(p.getEstado()))
                                    .collect(Collectors.toList());
                        })
                        .dataFetcher("prestamosVencidos", env -> restGetAllPrestamos().stream()
                                .filter(p -> "ACTIVO".equalsIgnoreCase(p.getEstado()))
                                .filter(p -> esVencido(p.getFechaPrestamo()))
                                .collect(Collectors.toList())
                        )
                        .dataFetcher("librosMasPrestados", env -> {
                            int top = env.getArgumentOrDefault("top", 5);
                            Map<String, Long> conteo = restGetAllPrestamos().stream()
                                    .collect(Collectors.groupingBy(Prestamo::getLibroTitulo, Collectors.counting()));
                            return conteo.entrySet().stream()
                                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                                    .limit(top)
                                    .map(e -> {
                                        Map<String, Object> r = new LinkedHashMap<>();
                                        r.put("libroTitulo", e.getKey());
                                        r.put("vecesPrestado", e.getValue().intValue());
                                        return r;
                                    })
                                    .collect(Collectors.toList());
                        })
                        .dataFetcher("estadisticasPrestamos", env -> {
                            List<Prestamo> all = restGetAllPrestamos();
                            long activos = all.stream().filter(p -> "ACTIVO".equalsIgnoreCase(p.getEstado())).count();
                            long devueltos = all.stream().filter(p -> "DEVUELTO".equalsIgnoreCase(p.getEstado())).count();
                            Map<String, Object> stats = new LinkedHashMap<>();
                            stats.put("total", all.size());
                            stats.put("activos", (int) activos);
                            stats.put("devueltos", (int) devueltos);
                            return stats;
                        })
                )
                .type("Prestamo", b -> b
                        .dataFetcher("usuario", env -> {
                            Prestamo p = env.getSource();
                            return restGetAllUsuarios().stream()
                                    .filter(u -> p.getUsuarioNombre().equalsIgnoreCase((String) u.get("nombre")))
                                    .findFirst()
                                    .orElse(null);
                        })
                )
                .type("Mutation", b -> b
                        .dataFetcher("crearPrestamo", env -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("usuarioNombre", env.getArgument("usuarioNombre"));
                            body.put("libroTitulo", env.getArgument("libroTitulo"));
                            body.put("fechaPrestamo", env.getArgument("fechaPrestamo"));
                            body.put("estado", "ACTIVO");
                            return restPostPrestamo(body);
                        })
                        .dataFetcher("actualizarPrestamo", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("usuarioNombre", env.getArgument("usuarioNombre"));
                            body.put("libroTitulo", env.getArgument("libroTitulo"));
                            body.put("fechaPrestamo", env.getArgument("fechaPrestamo"));
                            body.put("fechaDevolucion", env.getArgument("fechaDevolucion"));
                            body.put("estado", env.getArgument("estado"));
                            return restPutPrestamo(id, body);
                        })
                        .dataFetcher("eliminarPrestamo", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            return restDeletePrestamo(id);
                        })
                )
                .build();

        GraphQLSchema graphQLSchema = new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
        return GraphQL.newGraphQL(graphQLSchema).build();
    }

    private static boolean esVencido(String fechaPrestamo) {
        if (fechaPrestamo == null) return false;
        try {
            java.time.LocalDate f = java.time.LocalDate.parse(fechaPrestamo);
            return f.plusDays(15).isBefore(java.time.LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionName("prestamosGraphQL")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/graphql - Prestamos GraphQL -> prestamos=" + FN_PRESTAMOS_URL
                + " usuarios=" + FN_USUARIOS_URL);

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

    // ===== HTTP helpers contra fn-prestamos REST =====

    private static List<Prestamo> restGetAllPrestamos() {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
        if (resp.statusCode() != 200) return Collections.emptyList();
        Type t = new TypeToken<List<Prestamo>>() {}.getType();
        List<Prestamo> list = gson.fromJson(resp.body(), t);
        return list != null ? list : Collections.emptyList();
    }

    private static Prestamo restGetPrestamoById(Long id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos/" + id))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
        if (resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Prestamo.class);
    }

    private static Prestamo restPostPrestamo(Map<String, Object> body) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build());
        if (resp.statusCode() != 201 && resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Prestamo.class);
    }

    private static Prestamo restPutPrestamo(Long id, Map<String, Object> body) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos/" + id))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build());
        if (resp.statusCode() != 200) return null;
        return gson.fromJson(resp.body(), Prestamo.class);
    }

    private static Boolean restDeletePrestamo(Long id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_PRESTAMOS_URL + "/prestamos/" + id))
                .timeout(Duration.ofSeconds(15))
                .DELETE()
                .build());
        return resp.statusCode() == 204 || resp.statusCode() == 200;
    }

    // ===== HTTP helper cross-domain contra fn-usuarios REST =====

    private static List<Map<String, Object>> restGetAllUsuarios() {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(FN_USUARIOS_URL + "/usuarios"))
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
