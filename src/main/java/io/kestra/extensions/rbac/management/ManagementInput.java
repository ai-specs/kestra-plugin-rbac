package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/** Wire validation, before any directory or policy mutation. Identifiers remain opaque. */
final class ManagementInput {
    static final int MAX_BYTES = 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    static final class Failure extends RuntimeException {
        final int status;
        final String code;
        Failure(int status, String code) { super(code); this.status=status; this.code=code; }
    }
    static long revision(String match) {
        if (match == null) throw new Failure(428,"revision_required");
        if (!match.matches("\"(?:0|[1-9][0-9]*)\"")) throw invalid();
        try { return Long.parseLong(match.substring(1,match.length()-1)); }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    JsonNode body(byte[] bytes, Set<String> required, Set<String> optional) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        // Jackson accepts some byte encodings automatically; this public wire contract is UTF-8 only.
        String json=utf8(bytes);
        try (var parser=mapper.createParser(json)) {
            JsonNode value=mapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken()!=null) throw invalid();
            value.fieldNames().forEachRemaining(name -> {
                if (!required.contains(name) && !optional.contains(name) || value.get(name).isNull()) throw invalid();
            });
            for (String name:required) if (!value.has(name)) throw invalid();
            return value;
        } catch (java.io.IOException failure) { throw invalid(); }
    }
    JsonNode body(byte[] bytes, Set<String> fields) { return body(bytes,fields,Set.of()); }
    static String text(JsonNode node,String name) {
        if (!node.path(name).isTextual()) throw invalid();
        return node.get(name).textValue();
    }
    static boolean bool(JsonNode node,String name) {
        if (!node.path(name).isBoolean()) throw invalid();
        return node.get(name).booleanValue();
    }
    static String project(String value) {
        if (value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw invalid();
        return value;
    }
    static String subject(String value) {
        if (value==null || value.isBlank() || value.length()>1024 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    static String opaque(String key) {
        if (key==null || key.length()<2 || key.length()>8193 || !key.matches("~[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] decoded=Base64.getUrlDecoder().decode(key.substring(1));
            String value=utf8(decoded);
            if (value.isBlank() || value.length()>2048 || value.codePoints().anyMatch(Character::isISOControl)
                || !key.equals("~"+Base64.getUrlEncoder().withoutPadding().encodeToString(decoded))) throw invalid();
            return value;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    static void same(String path,JsonNode node,String field) { if (!path.equals(text(node,field))) throw invalid(); }
    static void page(int page,int size) { if (page<1 || size<1 || size>100) throw invalid(); }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw invalid(); }
    }
    private static Failure invalid() { return new Failure(400,"invalid_request"); }
}
