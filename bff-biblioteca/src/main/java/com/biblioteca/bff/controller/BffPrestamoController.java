package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * Controller del BFF que ORQUESTA las llamadas REST al dominio Prestamos.
 *
 * El BFF (Backend For Frontend) es el unico punto de entrada del sistema:
 * el cliente solo conoce esta URL (https://bff-biblioteca.azurewebsites.net)
 * y nunca habla directamente con las Azure Functions. El BFF reenvia cada
 * peticion HTTP a la funcion serverless correspondiente usando RestTemplate.
 *
 * La URL de la funcion se inyecta desde application.properties / variable
 * de entorno FAAS_PRESTAMOS_URL, lo que permite cambiar de ambiente
 * (local, Docker, Azure) sin tocar codigo.
 */
@RestController
@RequestMapping("/api/prestamos")
public class BffPrestamoController {

    private final RestTemplate restTemplate;

    // URL base de fn-prestamos (Azure Function REST), inyectada por Spring
    @Value("${faas.prestamos.url}")
    private String prestamosUrl;

    public BffPrestamoController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // GET /api/prestamos -> reenvia a fn-prestamos REST
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

    // POST /api/prestamos -> reenvia a fn-prestamos REST.
    // OJO: este endpoint inicia la cadena de eventos: cuando fn-prestamos
    // crea el registro, publica "PrestamoCreado" al Event Grid, lo que
    // dispara fn-notificaciones y fn-auditoria en paralelo.
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
