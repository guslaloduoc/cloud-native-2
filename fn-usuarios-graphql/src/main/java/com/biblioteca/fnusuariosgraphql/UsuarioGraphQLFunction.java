package com.biblioteca.fnusuariosgraphql;

import com.biblioteca.fnusuariosgraphql.model.Usuario;
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

public class UsuarioGraphQLFunction {

    private static final Map<Long, Usuario> usuarios = new HashMap<>();
    private static final AtomicLong idCounter = new AtomicLong(1);
    private static final Gson gson = new Gson();
    private static final GraphQL graphQL;

    // Datos dummy iniciales
    static {
        Usuario u1 = new Usuario(idCounter.getAndIncrement(), "Juan Perez", "juan@mail.com", "912345678");
        Usuario u2 = new Usuario(idCounter.getAndIncrement(), "Maria Lopez", "maria@mail.com", "987654321");
        usuarios.put(u1.getId(), u1);
        usuarios.put(u2.getId(), u2);
    }

    // Schema GraphQL
    static {
        String schema = ""
                + "type Usuario {\n"
                + "  id: ID!\n"
                + "  nombre: String!\n"
                + "  email: String!\n"
                + "  telefono: String!\n"
                + "}\n"
                + "\n"
                + "type Query {\n"
                + "  usuarios: [Usuario]\n"
                + "  usuario(id: ID!): Usuario\n"
                + "}\n"
                + "\n"
                + "type Mutation {\n"
                + "  crearUsuario(nombre: String!, email: String!, telefono: String!): Usuario\n"
                + "  actualizarUsuario(id: ID!, nombre: String!, email: String!, telefono: String!): Usuario\n"
                + "  eliminarUsuario(id: ID!): Boolean\n"
                + "}\n";

        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeRegistry = schemaParser.parse(schema);

        RuntimeWiring runtimeWiring = newRuntimeWiring()
                .type("Query", builder -> builder
                        .dataFetcher("usuarios", env -> new ArrayList<>(usuarios.values()))
                        .dataFetcher("usuario", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            return usuarios.get(id);
                        })
                )
                .type("Mutation", builder -> builder
                        .dataFetcher("crearUsuario", env -> {
                            String nombre = env.getArgument("nombre");
                            String email = env.getArgument("email");
                            String telefono = env.getArgument("telefono");
                            Usuario usuario = new Usuario(idCounter.getAndIncrement(), nombre, email, telefono);
                            usuarios.put(usuario.getId(), usuario);
                            return usuario;
                        })
                        .dataFetcher("actualizarUsuario", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            if (!usuarios.containsKey(id)) {
                                return null;
                            }
                            String nombre = env.getArgument("nombre");
                            String email = env.getArgument("email");
                            String telefono = env.getArgument("telefono");
                            Usuario usuario = new Usuario(id, nombre, email, telefono);
                            usuarios.put(id, usuario);
                            return usuario;
                        })
                        .dataFetcher("eliminarUsuario", env -> {
                            Long id = Long.parseLong(env.getArgument("id"));
                            if (!usuarios.containsKey(id)) {
                                return false;
                            }
                            usuarios.remove(id);
                            return true;
                        })
                )
                .build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
        graphQL = GraphQL.newGraphQL(graphQLSchema).build();
    }

    @FunctionName("usuariosGraphQL")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("POST /api/graphql - Usuarios GraphQL");

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
