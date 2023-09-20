package org.codehaus.mojo.properties;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

import static java.util.stream.Collectors.joining;

/**
 * The read-project-properties goal reads property files and URLs and stores the properties as project properties. It
 * serves as an alternate to specifying properties in pom.xml. It is especially useful when making properties defined in
 * a runtime resource available at build time.
 *
 * @author <a href="mailto:zarars@gmail.com">Zarar Siddiqi</a>
 * @author <a href="mailto:Krystian.Nowak@gmail.com">Krystian Nowak</a>
 * @version $Id$
 */
@Mojo(name = "inject-servers", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true)
public class InjectServersAsPropertiesMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    private Settings settings;

    @Parameter(defaultValue = "false", property = "properties.skip")
    private boolean skip;

    @Parameter(defaultValue = "false", property = "properties.servers.skip")
    private boolean skipServers;

    @Component
    private SecDispatcher secDispatcher;

    /**
     * The Server ids that will be used when injecting properties.
     */
    @Parameter
    private List<String> servers = null;

    /**
     * @param servers The servers to set for tests.
     */
    public void setServers(List<String> servers) {

        if (servers == null && servers.size() == 0) {
            this.servers = null;
        } else {
            this.servers = new ArrayList<>(servers);
        }
    }

    /**
     * If the plugin should be quiet if any of the ids were not found
     */
    @Parameter(defaultValue = "false")
    private boolean quiet;

    /**
     * Prefix that will be added before name of each property.
     * IF YOU USE MORE THAN ONE SERVER, YOU SHOULD USE A PREFIX
     * Prefix is a String.format( call, so a really good choice would be "server.%s."
     * The param supplied to format is the server id
     * Can be useful for separating properties with same name from different files.
     */
    @Parameter
    private String keyPrefix = null;

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * Used for resolving property placeholders.
     */
    private final PropertyResolver resolver = new PropertyResolver();

    /** {@inheritDoc} */
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || skipServers) {
            getLog().info("Skipping...");
            return;
        }
        checkParameters();

        loadServers(); // loadFiles();

        // loadUrls();

        resolveProperties();
    }

    private void checkParameters() throws MojoExecutionException {}

    private void loadServers() throws MojoExecutionException {
        List<String> s = servers;
        if (servers == null) {
            getLog().info("Using all servers");
            s = new ArrayList<>();
            for (Server ss : settings.getServers()) {
                s.add(ss.getId());
            }
        }
        for (int i = 0; i < s.size(); ++i) {
            load(s.get(i));
        }
    }

    private void loadFiles() throws MojoExecutionException {
        //        for ( int i = 0; i < files.length; i++ )
        //        {
        //            load( new FileResource( files[i] ) );
        //        }
    }

    private void loadUrls() throws MojoExecutionException {
        //        for ( int i = 0; i < urls.length; i++ )
        //        {
        //            load( new UrlResource( urls[i] ) );
        //        }
    }

    private void load(String resource) throws MojoExecutionException {
        Server server = settings.getServer(resource);
        if (server != null) {
            loadProperties(server);
        } else {
            missing(resource);
        }
    }

    private void load(Resource resource) throws MojoExecutionException {
        if (resource.canBeOpened()) {
            loadProperties(resource);
        } else {
            missing(resource);
        }
    }

    private void loadProperties(Server resource) throws MojoExecutionException {
        String id = resource.getId();
        getLog().info("Loading properties from " + id);

        Properties properties = new Properties();
        try {
            setProperty(properties, key(id, "directoryPermissions"), resource.getDirectoryPermissions());
            setProperty(properties, key(id, "filePermissions"), resource.getFilePermissions());
            setProperty(properties, key(id, "id"), resource.getId());
            setProperty(properties, key(id, "passphrase"), resource.getPassphrase());
            setProperty(properties, key(id, "password"), resource.getPassword());
            String pk = resource.getPrivateKey();
            setProperty(properties, key(id, "privateKey"), pk);
            if (pk != null) {
                Path pkPath = Paths.get(pk);
                if (Files.isRegularFile(pkPath)) {
                    try {
                        List<String> pkLines = Files.readAllLines(pkPath);
                        setProperty(
                                properties,
                                key(id, "privateKeyJoined"),
                                pkLines.stream().collect(joining("\\n")));
                        setProperty(
                                properties,
                                key(id, "privateKeyJoinedNL"),
                                pkLines.stream().collect(joining("\n")));
                    } catch (IOException e) {
                        if (quiet) {
                            getLog().info("Quietly ignoring read error of " + pk);
                        } else {
                            throw new MojoExecutionException("Error reading " + pk, e);
                        }
                    }
                }
            }

            setProperty(properties, key(id, "username"), resource.getUsername());
            //            setProperty(properties,key("configuration"), resource.getConfiguration());
        } catch (SecDispatcherException e) {
            throw new MojoExecutionException("Failed through decrypt", e);
        }

        Properties projectProperties = project.getProperties();
        for (String key : properties.stringPropertyNames()) {
            projectProperties.put(key, properties.get(key));
        }
    }

    private void setProperty(Properties p, String key, String val) throws SecDispatcherException {
        if (val != null) {
            p.setProperty(key, secDispatcher.decrypt(val));
        }
    }

    private String key(String id, String key) {
        return keyPrefix == null ? key : String.format(keyPrefix, id) + key;
    }

    private void loadProperties(Resource resource) throws MojoExecutionException {
        try {
            getLog().debug("Loading properties from " + resource);

            final InputStream stream = resource.getInputStream();

            try {
                if (keyPrefix != null) {
                    Properties properties = new Properties();
                    properties.load(stream);
                    Properties projectProperties = project.getProperties();
                    for (String key : properties.stringPropertyNames()) {
                        projectProperties.put(keyPrefix + key, properties.get(key));
                    }
                } else {
                    project.getProperties().load(stream);
                }
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading properties from " + resource, e);
        }
    }

    private void missing(String resource) throws MojoExecutionException {
        if (quiet) {
            getLog().info("Quiet processing - ignoring properties cannot be loaded from " + resource);
        } else {
            throw new MojoExecutionException("Properties could not be loaded from " + resource);
        }
    }

    private void missing(Resource resource) throws MojoExecutionException {
        if (quiet) {
            getLog().info("Quiet processing - ignoring properties cannot be loaded from " + resource);
        } else {
            throw new MojoExecutionException("Properties could not be loaded from " + resource);
        }
    }

    private void resolveProperties() throws MojoExecutionException, MojoFailureException {
        Properties environment = loadSystemEnvironmentPropertiesWhenDefined();
        Properties projectProperties = project.getProperties();

        for (Enumeration<?> n = projectProperties.propertyNames(); n.hasMoreElements(); ) {
            String k = (String) n.nextElement();
            projectProperties.setProperty(k, getPropertyValue(k, projectProperties, environment));
        }
    }

    private Properties loadSystemEnvironmentPropertiesWhenDefined() throws MojoExecutionException {
        Properties projectProperties = project.getProperties();

        boolean useEnvVariables = false;
        for (Enumeration<?> n = projectProperties.propertyNames(); n.hasMoreElements(); ) {
            String k = (String) n.nextElement();
            String p = (String) projectProperties.get(k);
            if (p.indexOf("${env.") != -1) {
                useEnvVariables = true;
                break;
            }
        }
        Properties environment = null;
        if (useEnvVariables) {
            try {
                environment = getSystemEnvVars();
            } catch (IOException e) {
                throw new MojoExecutionException("Error getting system environment variables: ", e);
            }
        }
        return environment;
    }

    private String getPropertyValue(String k, Properties p, Properties environment) throws MojoFailureException {
        try {
            return resolver.getPropertyValue(k, p, environment);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
    }

    /**
     * Override-able for test purposes.
     *
     * @return The shell environment variables, can be empty but never <code>null</code>.
     * @throws IOException If the environment variables could not be queried from the shell.
     */
    Properties getSystemEnvVars() throws IOException {
        return CommandLineUtils.getSystemEnvVars();
    }

    /**
     * Default scope for test access.
     *
     * @param quiet Set to <code>true</code> if missing files can be skipped.
     */
    void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Default scope for test access.
     *
     * @param project The test project.
     */
    void setProject(MavenProject project) {
        this.project = project;
    }

    private abstract static class Resource {
        private InputStream stream;

        public abstract boolean canBeOpened();

        protected abstract InputStream openStream() throws IOException;

        public InputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = openStream();
            }
            return stream;
        }
    }

    private static class FileResource extends Resource {
        private final File file;

        FileResource(File file) {
            this.file = file;
        }

        public boolean canBeOpened() {
            return file.exists();
        }

        protected InputStream openStream() throws IOException {
            return new BufferedInputStream(new FileInputStream(file));
        }

        public String toString() {
            return "File: " + file;
        }
    }

    private static class UrlResource extends Resource {
        private static final String CLASSPATH_PREFIX = "classpath:";

        private static final String SLASH_PREFIX = "/";

        private final URL url;

        private boolean isMissingClasspathResouce = false;

        private String classpathUrl;

        UrlResource(String url) throws MojoExecutionException {
            if (url.startsWith(CLASSPATH_PREFIX)) {
                String resource = url.substring(CLASSPATH_PREFIX.length(), url.length());
                if (resource.startsWith(SLASH_PREFIX)) {
                    resource = resource.substring(1, resource.length());
                }
                this.url = getClass().getClassLoader().getResource(resource);
                if (this.url == null) {
                    isMissingClasspathResouce = true;
                    classpathUrl = url;
                }
            } else {
                try {
                    this.url = new URL(url);
                } catch (MalformedURLException e) {
                    throw new MojoExecutionException("Badly formed URL " + url + " - " + e.getMessage());
                }
            }
        }

        public boolean canBeOpened() {
            if (isMissingClasspathResouce) {
                return false;
            }
            try {
                openStream();
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        protected InputStream openStream() throws IOException {
            return new BufferedInputStream(url.openStream());
        }

        public String toString() {
            if (!isMissingClasspathResouce) {
                return "URL " + url.toString();
            }
            return classpathUrl;
        }
    }
}
