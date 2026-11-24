package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

public final class GrantJson {
    private GrantJson() {}

    public static final class Serializer extends JsonSerializer<ApplicationGroupPolicy.Grant> {
        @Override
        public void serialize(ApplicationGroupPolicy.Grant grant, JsonGenerator output, SerializerProvider provider) throws IOException {
            output.writeStartObject();
            output.writeStringField("scope", grant.scope());
            output.writeStringField("tenant", grant.tenant());
            if (grant.namespace() == null) output.writeNullField("namespace");
            else output.writeStringField("namespace", new NamespaceExpression(grant.namespace(), grant.descendants()).render());
            output.writeStringField("resource", grant.resource());
            output.writeObjectField("actions", grant.actions());
            output.writeObjectField("ids", grant.ids());
            output.writeBooleanField("allIds", grant.allIds());
            output.writeEndObject();
        }
    }

    public static final class Deserializer extends JsonDeserializer<ApplicationGroupPolicy.Grant> {
        @Override
        public ApplicationGroupPolicy.Grant deserialize(JsonParser input, DeserializationContext context) throws IOException {
            return ApplicationGroupPolicyParser.parseGrant(context.readTree(input));
        }
    }
}
