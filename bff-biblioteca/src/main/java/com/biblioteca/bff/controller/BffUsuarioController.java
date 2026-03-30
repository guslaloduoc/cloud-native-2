package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/usuarios")
public class BffUsuarioController {

    private final RestTemplate restTemplate;

    @Value("${faas.usuarios.url}")
    private String usuariosUrl;

    public BffUsuarioController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // GET - Listar todos los usuarios
    @GetMapping
    public ResponseEntity<String> listarTodos() {
        String url = usuariosUrl + "/usuarios";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // GET - Buscar usuario por ID
    @GetMapping("/{id}")
    public ResponseEntity<String> buscarPorId(@PathVariable Long id) {
        String url = usuariosUrl + "/usuarios/" + id;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // POST - Crear usuario
    @PostMapping
    public ResponseEntity<String> crear(@RequestBody String body) {
        String url = usuariosUrl + "/usuarios";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // PUT - Actualizar usuario
    @PutMapping("/{id}")
    public ResponseEntity<String> actualizar(@PathVariable Long id, @RequestBody String body) {
        String url = usuariosUrl + "/usuarios/" + id;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // DELETE - Eliminar usuario
    @DeleteMapping("/{id}")
    public ResponseEntity<String> eliminar(@PathVariable Long id) {
        String url = usuariosUrl + "/usuarios/" + id;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
