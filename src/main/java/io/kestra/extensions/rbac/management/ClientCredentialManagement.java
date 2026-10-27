package io.kestra.extensions.rbac.management;

import java.util.Objects;

public final class ClientCredentialManagement {
    public enum ClientType { PUBLIC, CONFIDENTIAL }
    public enum RotationMode { NONE, ONLINE, DEPLOYMENT_MAINTENANCE }
    public enum Action { READ, MANAGE }
    public record Target(String applicationGroupId,String clientId) {
        public Target {
            if(applicationGroupId==null || applicationGroupId.isBlank() || clientId==null || clientId.isBlank())
                throw new IllegalArgumentException("Credential target required");
        }
    }
    public record State(String clientId,ClientType clientType,boolean secretSet,String maskedHint,
                        long credentialRevision,RotationMode rotationMode) {
        public State {
            Objects.requireNonNull(clientId);Objects.requireNonNull(clientType);Objects.requireNonNull(rotationMode);
            if(clientId.isBlank() || credentialRevision<0
                || (!secretSet && maskedHint!=null && !maskedHint.isEmpty())
                || (clientType==ClientType.PUBLIC && (secretSet || rotationMode!=RotationMode.NONE))
                || (clientType==ClientType.CONFIDENTIAL && rotationMode==RotationMode.NONE))
                throw new IllegalArgumentException("Invalid credential state");
        }
    }
    public record Rotation(String clientId,long credentialRevision,String clientSecret) {
        public Rotation {
            if(clientId==null || clientId.isBlank() || credentialRevision<1 || clientSecret==null || clientSecret.isBlank())
                throw new IllegalArgumentException("Invalid credential receipt");
        }
        @Override public String toString() {return "Rotation[clientId="+clientId+", credentialRevision="+credentialRevision+"]";}
    }
    public interface Host {
        State state(Target target);
        Rotation rotate(Target target,long expectedRevision);
    }
    public interface Access {
        void authorize(Target target,Action action);
    }
    private final Host host;
    private final Access access;

    public ClientCredentialManagement(Host host,Access access) {
        this.host=Objects.requireNonNull(host);this.access=Objects.requireNonNull(access);
    }

    public State state(Target target) {
        access.authorize(target,Action.READ);
        return stateFor(target);
    }

    public Rotation rotate(Target target,long expectedRevision) {
        if(expectedRevision<0)throw new IllegalArgumentException("Strong credential revision required");
        access.authorize(target,Action.MANAGE);
        var state=stateFor(target);
        if(state.clientType()==ClientType.PUBLIC)throw new ApplicationDirectoryAccess.DirectoryFailure(400,"public_client_no_secret");
        if(state.rotationMode()==RotationMode.DEPLOYMENT_MAINTENANCE)
            throw new ApplicationDirectoryAccess.DirectoryFailure(409,"maintenance_required");
        if(state.credentialRevision()!=expectedRevision)
            throw new ApplicationDirectoryAccess.DirectoryFailure(412,"revision_conflict");
        var receipt=Objects.requireNonNull(host.rotate(target,expectedRevision));
        if(!target.clientId().equals(receipt.clientId()) || receipt.credentialRevision()<=expectedRevision)
            throw new IllegalStateException("Invalid credential receipt");
        return receipt;
    }

    private State stateFor(Target target) {
        var state=Objects.requireNonNull(host.state(target));
        if(!target.clientId().equals(state.clientId()))throw new IllegalStateException("Credential target mismatch");
        return state;
    }
}
