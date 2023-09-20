package org.codehaus.mojo.properties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.cli.CommandLineUtils;

import static java.util.stream.Collectors.joining;

/**
 * Copied from the read-project-properties goal
 */
@Mojo(name = "read-file-as-property", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true)
public class ReadFileContentsAsPropertyMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    private Settings settings;

    @Parameter
    private Map<String, File> localPaths = new HashMap<>();

    /**
     * @param localPaths The files to set for tests.
     */
    public void setLocalPaths(Map<String, File> localPaths) {
        this.localPaths = new HashMap<>();
        if (localPaths != null) {
            this.localPaths.putAll(localPaths);
        }
    }

    /**
     * If the plugin should be quiet if any of the files was not found
     */
    @Parameter(defaultValue = "false")
    private boolean quiet;

    /**
     * Joins lines with literal string '\n' (two character \ and n) so that the result can be parsed lated
     *
     */
    @Parameter(defaultValue = "false")
    private boolean joinWithNL = false;

    /**
     * Used for resolving property placeholders.
     */
    private final PropertyResolver resolver = new PropertyResolver();

    /** {@inheritDoc} */
    public void execute() throws MojoExecutionException, MojoFailureException {

        loadFiles();

        resolveProperties();
    }

    private void loadFiles() throws MojoExecutionException {
        for (Entry<String, File> x : localPaths.entrySet()) {
            load(x.getKey(), new LocalPathResource(x.getValue()));
        }
    }

    private void load(String key, LocalPathResource resource) throws MojoExecutionException {
        if (resource.canBeOpened()) {
            loadProperties(key, resource);
        } else {
            missing(resource);
        }
    }

    private void loadProperties(String key, LocalPathResource resource) throws MojoExecutionException {
        try {
            getLog().debug("Loading properties from " + resource);
            Properties projectProperties = project.getProperties();
            projectProperties.setProperty(
                    Objects.requireNonNull(key),
                    resource.readAllLines().stream().collect(joining(joinWithNL ? "\\n" : "\n")));
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading TFState from " + resource, e);
        }
    }

    private void missing(LocalPathResource resource) throws MojoExecutionException {
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

    /**
     * Set Settings
     *
     * @param settings Maven Settings
     */
    void setSettings(Settings settings) {
        this.settings = settings;
    }

    public static final class LocalPathResource {
        private final Path file;

        public LocalPathResource(File file) {
            this.file = file.toPath();
        }

        public boolean canBeOpened() {
            return Files.isRegularFile(this.file) && Files.isReadable(this.file);
        }

        protected List<String> readAllLines() throws IOException {
            return Files.readAllLines(this.file);
        }

        public String toString() {
            return "File: " + file;
        }
    }
}
