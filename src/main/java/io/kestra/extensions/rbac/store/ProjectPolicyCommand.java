package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Explicit offline policy maintenance. Runtime authorization does not use this class or schema 1. */
public final class ProjectPolicyCommand {
    private ProjectPolicyCommand() {}
    public static void main(String[] arguments) {
        try { run(arguments); }
        catch(Exception failure) {
            System.err.println("Project policy command refused: "+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    static void run(String[] args)throws Exception {
        if(args.length==2 && "validate".equals(args[0])) {
            var parser=new ProjectPolicyParser();var policy=parser.parse(file(args[1],1024*1024));
            System.out.println("Valid schemaVersion=2 projects="+policy.applicationProjects().size());return;
        }
        if(args.length==0)throw new IllegalArgumentException("validate|initialize|preview-migration|migrate");
        String actor=environment("RBAC_POLICY_OPERATOR_SUBJECT");
        DataSource source=dataSource();var store=new ProjectPolicyStore(source,new ProjectPolicyParser());
        if(args.length==2 && "initialize".equals(args[0])) {
            byte[] target=file(args[1],1024*1024);new ProjectPolicyParser().parse(target);
            store.initializeSchema();var snapshot=store.initialize(target,actor);
            System.out.println("Initialized schemaVersion=2 revision="+snapshot.revision());return;
        }
        if(args.length==4 && "preview-migration".equals(args[0])) {
            // This branch reads the legacy database and writes a new review file. It never creates tables.
            var old=new JdbcPolicyStore(source,new PolicyDocumentValidator()).readCurrent();
            var service=new ProjectMigrationReview();
            var review=service.preview(old.revision(),old.canonicalJson().getBytes(StandardCharsets.UTF_8),
                file(args[1],1024*1024),file(args[2],1024*1024),actor);
            Path destination=Path.of(args[3]);
            Files.writeString(destination,service.envelope(review)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            System.out.println("Review created; no database change. approval-sha256="+review.approvalHash());return;
        }
        if(args.length==4 && "migrate".equals(args[0])) {
            var service=new ProjectMigrationReview();var review=service.readReview(file(args[2],ProjectMigrationReview.MAX_REPORT_BYTES));
            byte[] target=file(args[1],1024*1024);
            if(!review.approvalHash().equals(args[3]))throw new IllegalArgumentException("Explicit review hash required");
            var snapshot=store.migrateApproved(review,args[3],target,actor);
            System.out.println("Migrated schemaVersion=2 revision="+snapshot.revision()+" sourceRevision="+review.sourceRevision());return;
        }
        throw new IllegalArgumentException("Usage: validate|initialize <target>; preview-migration <target> <role-decisions> <new-review>; migrate <target> <review> <approved-hash>");
    }
    private static byte[] file(String name,int maximum)throws Exception {
        Path path=Path.of(name);if(Files.size(path)>maximum)throw new IllegalArgumentException("File exceeds limit");
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException("File exceeds limit");return bytes;}
    }
    private static String environment(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Required environment: "+name);return value;
    }
    private static DataSource dataSource() {
        String url=environment("RBAC_POLICY_JDBC_URL"),user=environment("RBAC_POLICY_JDBC_USER"),secret=environment("RBAC_POLICY_JDBC_PASSWORD");
        return new DataSource() {
            public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,secret);}
            public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Credential override not supported");}
            public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter writer){}
            public int getLoginTimeout(){return 0;}public void setLoginTimeout(int seconds){}
            public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
            public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Unsupported");}public boolean isWrapperFor(Class<?> type){return false;}
        };
    }
}
