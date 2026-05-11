package com.biblioteca.fnlibros.model;

/**
 * Entidad Libro del catalogo de la biblioteca.
 *
 * El campo "disponibilidad" representa cuantas copias del libro hay
 * actualmente prestables. Se decrementa de forma asincrona cada vez que
 * fn-prestamos publica un evento "PrestamoCreado" (ver LibroFunction.onPrestamoCreado).
 */
public class Libro {

    private Long id;
    private String titulo;
    private String autor;
    private Integer disponibilidad;

    public Libro() {
    }

    public Libro(Long id, String titulo, String autor, Integer disponibilidad) {
        this.id = id;
        this.titulo = titulo;
        this.autor = autor;
        this.disponibilidad = disponibilidad;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getAutor() {
        return autor;
    }

    public void setAutor(String autor) {
        this.autor = autor;
    }

    public Integer getDisponibilidad() {
        return disponibilidad;
    }

    public void setDisponibilidad(Integer disponibilidad) {
        this.disponibilidad = disponibilidad;
    }
}
