package io.kestra.extensions.rbac.store;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline legacy validation only. Production initialization is schema 2. */
public final class PolicyImportCommand {
    private PolicyImportCommand() {}
    public static void main(String[] arguments) {
        try { execute(arguments); }
        catch (Exception failure) {
            System.err.println("Policy command failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void execute(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("validate") || arguments[0].equals("initialize"))) {
            throw new IllegalArgumentException("Usage: PolicyImportCommand validate|initialize <policy.json>");
        }
        Path file = Path.of(arguments[1]);
        if (Files.size(file) > PolicyDocumentValidator.MAX_BYTES) throw new IllegalArgumentException("Policy exceeds 1MiB");
        byte[] document;
        try (var input = Files.newInputStream(file)) { document = input.readNBytes(PolicyDocumentValidator.MAX_BYTES + 1); }
        var validator = new PolicyDocumentValidator();
        var validated = validator.parse(document);
        if (arguments[0].equals("validate")) {
            System.out.println("Valid policy schemaVersion=1 roles=" + validated.document().roles().size());
            return;
        }
        throw new IllegalArgumentException("Schema 1 initialization is retired; use approved schema 2 maintenance");
    }
}
