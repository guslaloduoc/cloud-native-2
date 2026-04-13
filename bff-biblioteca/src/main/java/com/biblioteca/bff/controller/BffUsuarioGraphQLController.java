package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/usuarios-graphql")
public class BffUsuarioGraphQLController {

    private final RestTemplate restTemplate;

    @Value("${faas.usuarios.graphql.url}")
    private String usuariosGraphqlUrl;

    public BffUsuarioGraphQLController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // POST - Proxy hacia la Azure Function GraphQL de Usuarios
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
