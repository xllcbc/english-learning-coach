package com.writelab.practice;

/**
 * Attribution returned by the backend so the UI never hardcodes licence text.
 */
public record ExampleSource(
        String source,
        String license
) {
}
