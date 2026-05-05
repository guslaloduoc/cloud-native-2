package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * Controller del BFF que ORQUESTA las consultas GraphQL del dominio Usuarios.
 *
 * Mismo patron que BffPrestamoGraphQLController: actua como proxy
 * transparente del cuerpo GraphQL. La logica enriquecida (filtros,
 * paginacion, joins con prestamos) vive en fn-usuarios-graphql.
 */
@RestController
@RequestMapping("/api/usuarios-graphql")
public class BffUsuarioGraphQLController {

    private final RestTemplate restTemplate;

    @Value("${faas.usuarios.graphql.url}")
    private String usuariosGraphqlUrl;

    public BffUsuarioGraphQLController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Reenvia el query/mutation GraphQL a fn-usuarios-graphql. */
    @PostMapping
    public ResponseEntity<String> graphql(@RequestBody String body) {
        String url = usuariosGraphqlUrl + "/graphql";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
