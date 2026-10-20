package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import io.micronaut.context.BeanProvider;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.management.ApplicationDirectoryAccess.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedApplicationDirectoryAccessTest {
    private static final String ISSUER="https://id.example.invalid";
    private final ApplicationDirectoryV1 directory=mock(ApplicationDirectoryV1.class);
    @SuppressWarnings("unchecked") private final BeanProvider<ApplicationDirectoryV1> provider=mock(BeanProvider.class);
    private final PublishedApplicationDirectoryAccess access=new PublishedApplicationDirectoryAccess(provider);
    private final ObjectMapper mapper=new ObjectMapper();
    private ApplicationDirectoryV1.Project project(long revision) {return new ApplicationDirectoryV1.Project("p","main",ISSUER,"P",true,revision,97);}
    private void setup() {when(provider.isUnique()).thenReturn(true);when(provider.get()).thenReturn(directory);when(directory.project("p")).thenReturn(Optional.of(project(3)));}
    private Request request(Resource resource,Operation operation,String key,Long revision,String body)throws Exception {
        return new Request(resource,operation,ISSUER,"p",key,1,100,revision,3,body==null?null:mapper.readTree(body));
    }
    @Test void clientDetailFindsExactOpaqueValueAndPreservesAllMemberFields()throws Exception {
        setup();var wanted=new ApplicationDirectoryV1.ClientMembership("p","opaque/client",true,Set.of("aud"),Set.of("scope"),true,11);
        when(directory.clients("p",1,100)).thenReturn(new ApplicationDirectoryV1.Page<>(List.of(wanted),1,1,100));
        assertEquals(wanted,access.invoke(request(Resource.CLIENT,Operation.FIND,"opaque/client",null,null)));
        verify(directory,atLeast(3)).project("p");verify(directory).clients("p",1,100);
        verify(directory,never()).updateClient(anyString(),anyString(),anyLong(),any());
    }
    @Test void clientDetailRejectsSameTotalDirectoryDriftAndNeverMutates()throws Exception {
        setup();when(directory.project("p")).thenReturn(Optional.of(project(3)),Optional.of(project(3)),Optional.of(project(4)));
        var member=new ApplicationDirectoryV1.ClientMembership("p","client",true,Set.of("aud"),Set.of(),false,1);
        when(directory.clients("p",1,100)).thenReturn(new ApplicationDirectoryV1.Page<>(List.of(member),1,1,100));
        var failure=assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.CLIENT,Operation.FIND,"client",null,null)));
        assertEquals(409,failure.status());assertEquals("directory_changed",failure.code());
        verify(directory,never()).removeClient(anyString(),anyString(),anyLong());
    }
    @Test void lastAdministratorAndMutationConflictRetainTheirPublicMeaning()throws Exception {
        setup();doThrow(new ApplicationDirectoryV1.DirectoryException(ApplicationDirectoryV1.Failure.LAST_ADMIN)).when(directory).revokeAssignment("p",ISSUER,"user",7);
        assertEquals(409,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.ASSIGNMENT,Operation.DELETE,"user",7L,null))).status());
        doThrow(new ApplicationDirectoryV1.DirectoryException(ApplicationDirectoryV1.Failure.CONFLICT)).when(directory).removeClient("p","client",8);
        assertEquals(412,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.CLIENT,Operation.DELETE,"client",8L,null))).status());
    }
    @Test void sharedScopeWriteForwardsProjectRevisionAndCompleteIntents()throws Exception {
        setup();String body="""
            {"scope":"reader","name":"Reader","description":"Complete","active":true,"resourceIntents":[
            {"vocabularyVersion":"kestra-resources-v1","resourceType":"FLOW","namespaces":{"kind":"RESTRICTED","exact":["a"],"subtrees":[]},
            "resourceIds":{"kind":"RESTRICTED","ids":["f"]},"actions":["READ","LIST"]}],"managementIntents":[]}
            """;
        var expected=new ApplicationDirectoryV1.ScopeDefinition("p","reader","Reader","Complete",true,List.of(),List.of(),1);
        when(directory.createScope(eq("p"),eq(3L),any())).thenReturn(expected);
        assertEquals(expected,access.invoke(request(Resource.SCOPE,Operation.CREATE,null,3L,body)));
        var argument=org.mockito.ArgumentCaptor.forClass(ApplicationDirectoryV1.ScopeInput.class);
        verify(directory).createScope(eq("p"),eq(3L),argument.capture());
        assertEquals("reader",argument.getValue().scope());
        assertEquals(Set.of("a"),argument.getValue().resourceIntents().getFirst().namespaces().exact());
        assertEquals(Set.of("f"),argument.getValue().resourceIntents().getFirst().resourceIds().ids());
        assertEquals(Set.of("READ","LIST"),argument.getValue().resourceIntents().getFirst().actions());
    }
    @Test void invalidNestedScopeFieldsCannotReachDirectoryMutation()throws Exception {
        setup();String body="""
            {"scope":"reader","name":"Reader","description":"Read","active":true,"resourceIntents":[
            {"vocabularyVersion":"unknown","resourceType":"FLOW","namespaces":{"kind":"ALL","exact":[],"subtrees":[]},
            "resourceIds":{"kind":"ALL","ids":[]},"actions":["READ"]}],"managementIntents":[]}
            """;
        assertEquals(400,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.SCOPE,Operation.CREATE,null,3L,body))).status());
        assertEquals(400,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.SCOPE,Operation.CREATE,null,3L,body.replace("unknown","kestra-resources-v1")))).status());
        verify(directory,never()).createScope(anyString(),anyLong(),any());
    }
    @Test void issuerMismatchAndMissingProviderFailBeforeMutation()throws Exception {
        setup();when(directory.project("p")).thenReturn(Optional.of(new ApplicationDirectoryV1.Project("p","main","https://other.invalid","P",true,3,97)));
        assertEquals(403,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.CLIENT,Operation.DELETE,"c",3L,null))).status());
        when(provider.isUnique()).thenReturn(false);
        assertEquals(503,assertThrows(DirectoryFailure.class,()->access.invoke(request(Resource.CLIENT,Operation.DELETE,"c",3L,null))).status());
        verify(directory,never()).removeClient(anyString(),anyString(),anyLong());
    }
}
