package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/prestamos")
public class BffPrestamoController {

    private final RestTemplate restTemplate;

    @Value("${faas.prestamos.url}")
    private String prestamosUrl;

    public BffPrestamoController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // GET - Listar todos los prestamos
    @GetMapping
    public ResponseEntity<String> listarTodos() {
        String url = prestamosUrl + "/prestamos";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // GET - Buscar prestamo por ID
    @GetMapping("/{id}")
    public ResponseEntity<String> buscarPorId(@PathVariable Long id) {
        String url = prestamosUrl + "/prestamos/" + id;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // POST - Crear prestamo
    @PostMapping
    public ResponseEntity<String> crear(@RequestBody String body) {
        String url = prestamosUrl + "/prestamos";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // PUT - Actualizar prestamo
    @PutMapping("/{id}")
    public ResponseEntity<String> actualizar(@PathVariable Long id, @RequestBody String body) {
        String url = prestamosUrl + "/prestamos/" + id;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // DELETE - Eliminar prestamo
    @DeleteMapping("/{id}")
    public ResponseEntity<String> eliminar(@PathVariable Long id) {
        String url = prestamosUrl + "/prestamos/" + id;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
