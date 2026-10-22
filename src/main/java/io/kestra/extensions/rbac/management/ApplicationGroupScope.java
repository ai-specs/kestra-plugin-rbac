package io.kestra.extensions.rbac.management;

public record ApplicationGroupScope(String applicationGroupId,String key,String name,String description,long revision) {
    public ApplicationGroupScope {
        if(applicationGroupId==null || !applicationGroupId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
            || key==null || !key.matches("[a-z][a-z0-9-]{0,63}") || name==null || name.isBlank()
            || description==null || revision<=0)throw new IllegalArgumentException("Invalid application group scope");
    }
}
