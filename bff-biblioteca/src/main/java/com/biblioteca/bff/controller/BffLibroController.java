package com.biblioteca.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * Controller del BFF que ORQUESTA las llamadas REST al dominio Libros.
 *
 * Mismo patron que BffPrestamoController y BffUsuarioController: el
 * cliente solo conoce el BFF, este reenvia las peticiones a la Azure
 * Function fn-libros usando RestTemplate. La URL se inyecta desde
 * application.properties / variable de entorno FAAS_LIBROS_URL.
 *
 * Nota arquitectonica: aunque fn-libros decrementa stock automaticamente
 * via evento PrestamoCreado, este controller permite el CRUD manual del
 * catalogo (alta, baja, modificacion, consulta de disponibilidad).
 */
@RestController
@RequestMapping("/api/libros")
public class BffLibroController {

    private final RestTemplate restTemplate;

    // URL base de fn-libros (Azure Function REST), externalizada por env var
    @Value("${faas.libros.url}")
    private String librosUrl;

    public BffLibroController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // GET - Listar todos los libros
    @GetMapping
    public ResponseEntity<String> listarTodos() {
        String url = librosUrl + "/libros";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // GET - Buscar libro por ID
    @GetMapping("/{id}")
    public ResponseEntity<String> buscarPorId(@PathVariable Long id) {
        String url = librosUrl + "/libros/" + id;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // POST - Crear libro
    @PostMapping
    public ResponseEntity<String> crear(@RequestBody String body) {
        String url = librosUrl + "/libros";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // PUT - Actualizar libro
    @PutMapping("/{id}")
    public ResponseEntity<String> actualizar(@PathVariable Long id, @RequestBody String body) {
        String url = librosUrl + "/libros/" + id;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }

    // DELETE - Eliminar libro
    @DeleteMapping("/{id}")
    public ResponseEntity<String> eliminar(@PathVariable Long id) {
        String url = librosUrl + "/libros/" + id;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
