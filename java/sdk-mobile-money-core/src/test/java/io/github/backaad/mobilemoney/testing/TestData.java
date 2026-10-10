package io.github.backaad.mobilemoney.testing;

import io.github.backaad.mobilemoney.internal.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/** Reads the reference data in the repository folder {@code testdata/}, shared with the Dart tests. */
public final class TestData {

    private TestData() {
    }

    public static JsonNode read(String fileName) {
        String dir = System.getProperty("testdata.dir");
        if (dir == null) {
            throw new IllegalStateException("System property testdata.dir is not set (see the core pom)");
        }
        try {
            return Json.parse(Files.readAllBytes(Path.of(dir, fileName)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
