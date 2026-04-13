package com.biblioteca.fnprestamosgraphql;

import com.biblioteca.fnprestamosgraphql.model.Prestamo;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;

public class PrestamoGraphQLFunction {

    private static final Map<Long, Prestamo> prestamos = new HashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();
    private static final GraphQL graphQL;

    // Datos dummy iniciales
    static {
        Prestamo p1 = new Prestamo(idCounter.getAndIncrement(), "Juan Perez", "Don Quijote", "2026-03-20", null, "ACTIVO");
        Prestamo p2 = new Prestamo(idCounter.getAndIncrement(), "Maria Lopez", "Cien Anos de Soledad", "2026-03-15", "2026-03-25", "DEVUELTO");
        prestamos.put(p1.getId(), p1);
        prestamos.put(p2.getId(), p2);
    }

    // Schema GraphQL
    static {
        String schema = ""
                + "type Prestamo {\n"
                + "  id: ID!\n"
                + "  usuarioNombre: String!\n"
                + "  libroTitulo: String!\n"
                + "  fechaPrestamo: String!\n"
                + "  fechaDevolucion: String\n"
                + "  estado: String!\n"
                + "}\n"
                + "\n"
                + "type Query {\n"
                + "  prestamos: [Prestamo]\n"
                + "  prestamo(id: ID!): Prestamo\n"
                + "}\n"
                + "\n"
                + "type Mutation {\n"
                + "  crearPrestamo(usuarioNombre: String!, libroTitulo: String!, fechaPrestamo: String!): Prestamo\n"
                + "  actualizarPrestamo(id: ID!, usuarioNombre: String!, libroTitulo: String!, fechaPrestamo: String!, fechaDevolucion: String, estado: String!): Prestamo\n"
                + "  eliminarPrestamo(id: ID!): Boolean\n"
                + "}\n";

        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeRegistry = schemaParser.parse(schema);

        RuntimeWiring runtimeWiring = newRuntimeWiring()
                .type("Query", builder -> builder
                        .dataFetcher("prestamos", env -> new ArrayList<>(prestamos.values()))
                        .dataFetcher("prestamo", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            return prestamos.get(id);
                        })
                )
                .type("Mutation", builder -> builder
                        .dataFetcher("crearPrestamo", env -> {
                            String usuarioNombre = env.getArgument("usuarioNombre");
                            String libroTitulo = env.getArgument("libroTitulo");
                            String fechaPrestamo = env.getArgument("fechaPrestamo");
                            Prestamo prestamo = new Prestamo(idCounter.getAndIncrement(), usuarioNombre, libroTitulo, fechaPrestamo, null, "ACTIVO");
                            prestamos.put(prestamo.getId(), prestamo);
                            return prestamo;
                        })
                        .dataFetcher("actualizarPrestamo", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            if (!prestamos.containsKey(id)) {
                                return null;
                            }
                            String usuarioNombre = env.getArgument("usuarioNombre");
                            String libroTitulo = env.getArgument("libroTitulo");
                            String fechaPrestamo = env.getArgument("fechaPrestamo");
                            String fechaDevolucion = env.getArgument("fechaDevolucion");
                            String estado = env.getArgument("estado");
                            Prestamo prestamo = new Prestamo(id, usuarioNombre, libroTitulo, fechaPrestamo, fechaDevolucion, estado);
                            prestamos.put(id, prestamo);
                            return prestamo;
                        })
                        .dataFetcher("eliminarPrestamo", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            if (!prestamos.containsKey(id)) {
                                return false;
                            }
                            prestamos.remove(id);
                            return true;
                        })
                )
                .build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
        graphQL = GraphQL.newGraphQL(graphQLSchema).build();
    }

    @FunctionName("prestamosGraphQL")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/graphql - Prestamos GraphQL");

        String body = request.getBody().orElse(null);
        if (body == null || body.isEmpty()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "application/json")
                    .body("{\"error\": \"Body es requerido con la query GraphQL\"}")
                    .build();
        }

        JsonObject jsonBody = JsonParser.parseString(body).getAsJsonObject();
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
}
