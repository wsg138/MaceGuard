package com.lincoln.maceguard.warzone.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StrictYaml {
    private static final String COUNT_WEIGHTS_PATH =
            "<root>.rotation.selection.count-weights";

    private StrictYaml() { }

    static ValidationResult<Map<String, Object>> load(Path file) {
        if (!Files.isRegularFile(file))
            return ValidationResult.invalid(List.of(file.getFileName() + " does not exist."));
        LoaderOptions options = options();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(value instanceof Map<?, ?> raw))
                return ValidationResult.invalid(List.of("Root YAML value must be a mapping."));
            return new ValidationResult<>(stringMap(raw, "<root>"), List.of(), List.of());
        } catch (YAMLException ex) {
            return ValidationResult.invalid(List.of(
                    "Malformed YAML or duplicate key: " + oneLine(ex.getMessage())));
        } catch (IOException ex) {
            return ValidationResult.invalid(List.of(
                    "Could not read " + file.getFileName() + ": " + ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ValidationResult.invalid(List.of(ex.getMessage()));
        }
    }

    static ValidationResult<Map<String, Object>> loadText(String text) {
        LoaderOptions options = options();
        try (Reader reader = new StringReader(text)) {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(value instanceof Map<?, ?> raw))
                return ValidationResult.invalid(List.of("Root YAML value must be a mapping."));
            return new ValidationResult<>(stringMap(raw, "<root>"), List.of(), List.of());
        } catch (YAMLException | IOException ex) {
            return ValidationResult.invalid(List.of(
                    "Malformed converted YAML: " + oneLine(ex.getMessage())));
        } catch (IllegalArgumentException ex) {
            return ValidationResult.invalid(List.of(ex.getMessage()));
        }
    }

    private static LoaderOptions options() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setWarnOnDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        return options;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = normalizedKey(entry.getKey(), path);
            Object value = entry.getValue();
            result.put(key, value instanceof Map<?, ?> nested
                    ? stringMap(nested, path + "." + key) : value);
        }
        return result;
    }

    private static String normalizedKey(Object rawKey, String path) {
        if (rawKey instanceof String key) return key;
        if (path.equals(COUNT_WEIGHTS_PATH) && rawKey instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() == Math.rint(number.doubleValue())) {
            return Long.toString(number.longValue());
        }
        throw new IllegalArgumentException(path + " contains a non-string key.");
    }

    private static String oneLine(String value) {
        return value == null ? "unknown YAML error"
                : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
