package org.codehaus.mojo.properties;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ReadJSONObjectAsFlattenedPropertiesMojoTest {

    private MavenProject projectStub;
    private ReadJSONObjectAsFlattenedPropertiesMojo readPropertiesMojo;
    private Settings settings;
    private List<Server> servers;
    private Server server;
    private File[] localPaths;
    private Model model;

    @Before
    public void setUp() {
        model = new Model();
        model.setProperties(new Properties());

        projectStub = new MavenProject(model);
        settings = new Settings();
        server = new Server();
        server.setId("id");
        server.setUsername("username");
        server.setPassword("password");
        servers = new ArrayList<>();
        servers.add(server);
        settings.setServers(servers);
        readPropertiesMojo = new ReadJSONObjectAsFlattenedPropertiesMojo();
        readPropertiesMojo.setProject(projectStub);
    }

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    @After
    public void tearDown() throws Exception {}

    @Test
    public void testExecute() throws MojoExecutionException, MojoFailureException {
        localPaths = new File[1];
        localPaths[0] = new File("src/test/resources/enflatten.tfstate");
        readPropertiesMojo.setLocalPaths(localPaths);
        readPropertiesMojo.execute();
    }
}
