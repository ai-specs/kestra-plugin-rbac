package io.kestra.extensions.rbac.management;

import java.util.Objects;

public final class TemporaryAccountCommands {
    public record Create(String username,String name,String email) {
        public Create {
            if(username==null || username.isBlank())throw new IllegalArgumentException("Username required");
            name=name==null?"":name;email=email==null?"":email;
        }
    }
    public record Created(String subject,long revision,String temporaryPassword) {
        public Created {
            if(subject==null || subject.isBlank() || revision<0 || temporaryPassword==null || temporaryPassword.isBlank())
                throw new IllegalArgumentException("Invalid temporary account receipt");
        }
        @Override public String toString() {return "Created[subject="+subject+", revision="+revision+"]";}
    }
    public interface Host {
        Created createTemporary(Create input);
        void delete(String subject,long expectedRevision);
    }
    public interface Access {
        void requireIdentityManage();
    }
    private final Host host;
    private final Access access;

    public TemporaryAccountCommands(Host host,Access access) {
        this.host=Objects.requireNonNull(host);this.access=Objects.requireNonNull(access);
    }

    public Created create(Create input) {
        Objects.requireNonNull(input);
        access.requireIdentityManage();
        var receipt=Objects.requireNonNull(host.createTemporary(input));
        if(!input.username().equals(receipt.subject()))throw new IllegalStateException("Account target mismatch");
        return receipt;
    }

    public void delete(String subject,long expectedRevision) {
        if(subject==null || subject.isBlank() || expectedRevision<0)throw new IllegalArgumentException("Account revision required");
        access.requireIdentityManage();
        host.delete(subject,expectedRevision);
    }
}
