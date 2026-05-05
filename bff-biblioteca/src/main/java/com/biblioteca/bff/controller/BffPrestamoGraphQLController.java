package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * Controller del BFF que ORQUESTA las consultas GraphQL del dominio Prestamos.
 *
 * Recibe el cuerpo GraphQL del cliente (queries enriquecidas, mutations) y
 * lo reenvia tal cual a fn-prestamos-graphql. La funcion GraphQL no tiene
 * estado propio: internamente delega al fn-prestamos REST y enriquece
 * con joins cross-domain hacia fn-usuarios REST.
 *
 * Se expone un solo endpoint POST /api/prestamos-graphql porque GraphQL
 * usa una unica URL para todas las operaciones.
 */
@RestController
@RequestMapping("/api/prestamos-graphql")
public class BffPrestamoGraphQLController {

    private final RestTemplate restTemplate;

    // URL base de fn-prestamos-graphql, inyectada desde env var
    @Value("${faas.prestamos.graphql.url}")
    private String prestamosGraphqlUrl;

    public BffPrestamoGraphQLController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Reenvia el body GraphQL completo a la Azure Function GraphQL.
     * El BFF no inspecciona el query, solo actua como proxy transparente.
     */
    @PostMapping
    public ResponseEntity<String> graphql(@RequestBody String body) {
        String url = prestamosGraphqlUrl + "/graphql";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
