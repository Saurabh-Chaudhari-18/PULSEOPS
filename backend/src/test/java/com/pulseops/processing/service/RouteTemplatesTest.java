package com.pulseops.processing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteTemplatesTest {

    @ParameterizedTest
    @CsvSource({
            "/orders/12345, /orders/{id}",
            "/orders/12345/items/9?expand=true#x, /orders/{id}/items/{id}",
            "/users/3f2b1c4e-9a8d-4e7f-b6a5-1c2d3e4f5a6b/profile, /users/{id}/profile",
            "/blobs/9f86d081884c7d659a2feaa0c55ad015, /blobs/{id}",
            "/carts/ab12cd34ef, /carts/{id}",
            "/api/v2/health, /api/v2/health",
            "/products/shoes, /products/shoes",
            "https://shop.example.com/orders/7, /orders/{id}",
            "//a//b/, /a/b"
    })
    void collapsesIdentifiersIntoTemplates(String path, String expected) {
        assertThat(RouteTemplates.fromPath(path)).isEqualTo(expected);
    }

    @Test
    void emptyPathIsRoot() {
        assertThat(RouteTemplates.fromPath("")).isEqualTo("/");
        assertThat(RouteTemplates.fromPath(null)).isEqualTo("/");
    }

    @Test
    void depthAndLengthAreCapped() {
        assertThat(RouteTemplates.fromPath("/a/b/c/d/e/f/g/h/i/j/k/l/m/n")).endsWith("/…");
        assertThat(RouteTemplates.fromPath("/" + "x".repeat(500))).hasSize(RouteTemplates.MAX_LENGTH);
    }

    @Test
    void clientTemplatesGetALeadingSlash() {
        assertThat(RouteTemplates.clean(" orders/{id} ")).isEqualTo("/orders/{id}");
    }
}
