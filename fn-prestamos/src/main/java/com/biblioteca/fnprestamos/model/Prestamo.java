package com.biblioteca.fnprestamos.model;

public class Prestamo {

    private Long id;
    private String usuarioNombre;
    private String libroTitulo;
    private String fechaPrestamo;
    private String fechaDevolucion;
    private String estado;

    public Prestamo() {
    }

    public Prestamo(Long id, String usuarioNombre, String libroTitulo, String fechaPrestamo, String fechaDevolucion, String estado) {
        this.id = id;
        this.usuarioNombre = usuarioNombre;
        this.libroTitulo = libroTitulo;
        this.fechaPrestamo = fechaPrestamo;
        this.fechaDevolucion = fechaDevolucion;
        this.estado = estado;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsuarioNombre() {
        return usuarioNombre;
    }

    public void setUsuarioNombre(String usuarioNombre) {
        this.usuarioNombre = usuarioNombre;
    }

    public String getLibroTitulo() {
        return libroTitulo;
    }

    public void setLibroTitulo(String libroTitulo) {
        this.libroTitulo = libroTitulo;
    }

    public String getFechaPrestamo() {
        return fechaPrestamo;
    }

    public void setFechaPrestamo(String fechaPrestamo) {
        this.fechaPrestamo = fechaPrestamo;
    }

    public String getFechaDevolucion() {
        return fechaDevolucion;
    }

    public void setFechaDevolucion(String fechaDevolucion) {
        this.fechaDevolucion = fechaDevolucion;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }
}
