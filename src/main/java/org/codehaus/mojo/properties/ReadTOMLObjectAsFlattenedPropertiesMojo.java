package org.codehaus.mojo.properties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.json.JSONObject;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

/**
 * Copied from the read-project-properties goal
 */
@Mojo(name = "read-toml-as-properties", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true)
public class ReadTOMLObjectAsFlattenedPropertiesMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /* LATER ITEMS OVERRIDE EARLIER ITEMS */
    /**
     * The properties files that will be used when reading properties.
     */
    @Parameter
    private File[] localPaths = new File[0];

    @Parameter
    private boolean reformatAWSCreds = false;

    @Parameter(defaultValue = "false", property = "properties.skip")
    private boolean skip;

    @Parameter(defaultValue = "false", property = "properties.toml.skip")
    private boolean skipTOML;

    public void setReformatAWSCreds(boolean reformatAWSCreds) {
        this.reformatAWSCreds = reformatAWSCreds;
    }

    /**
     * @param localPaths The files to set for tests.
     */
    public void setLocalPaths(File[] localPaths) {
        if (localPaths == null) {
            this.localPaths = new File[0];
        } else {
            this.localPaths = new File[localPaths.length];
            System.arraycopy(localPaths, 0, this.localPaths, 0, localPaths.length);
        }
    }

    MavenProject getProject() {
        return project;
    }

    /**
     * If the plugin should be quiet if any of the files was not found
     */
    @Parameter(defaultValue = "false")
    private boolean quiet;

    /**
     * Prefix that will be added before name of each property.
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
        if (skip || skipTOML) {
            getLog().info("Skipping...");
            return;
        }
        checkParameters();

        loadFiles();

        resolveProperties();
    }

    private void checkParameters() throws MojoExecutionException {
        int count = 0;
        if (localPaths.length > 0) {
            count++;
        }
        if (count != 1) {
            throw new MojoExecutionException("Set localPaths");
        }
    }

    private void loadFiles() throws MojoExecutionException {
        for (int i = 0; i < localPaths.length; i++) {
            load(new LocalTOMLResource(localPaths[i], reformatAWSCreds));
        }
    }

    private void load(TOMLResource resource) throws MojoExecutionException {
        if (resource.canBeOpened()) {
            loadProperties(resource);
        } else {
            missing(resource);
        }
    }

    private void loadProperties(TOMLResource resource) throws MojoExecutionException {
        try {
            getLog().debug("Loading properties from " + resource);

            final Properties stream = resource.getTOMLObjectProperties();

            Properties projectProperties = project.getProperties();
            for (String key : stream.stringPropertyNames()) {
                projectProperties.put(keyPrefix != null ? keyPrefix + key : key, stream.getProperty(key));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading TFState from " + resource, e);
        }
    }

    private void missing(TOMLResource resource) throws MojoExecutionException {
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
        getLog().info("Properties length is " + projectProperties.size());
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

    private abstract static class TOMLResource {
        private JSONObject stream;
        private Properties properties = new Properties();
        private TomlParseResult result;

        public abstract boolean canBeOpened();

        protected abstract String openTOMLString() throws IOException;

        public Properties getTOMLObjectProperties() throws IOException {

            if (stream == null) {
                String t = openTOMLString();
                result = Toml.parse(t);
                if (result.errors().size() > 0) {
                    for (TomlParseError e : result.errors()) {
                        System.err.println(e.toString());
                    }
                    throw new IOException("Errors exist in TOML source " + toString());
                }
            }
            SortedSet<String> s = new TreeSet<>(result.dottedKeySet());
            for (String key : s) {
                Object v = result.get(key);
                properties.setProperty(key, v.toString());
            }

            return properties;
        }
    }

    private static class LocalTOMLResource extends TOMLResource {
        private final File file;
        private boolean reformat;

        LocalTOMLResource(File file, boolean reformat) {
            this.file = file;
            this.reformat = reformat;
        }

        public boolean canBeOpened() {
            return file.exists();
        }

        protected String openTOMLString() throws IOException {
            String s = new String(Files.readAllBytes(file.toPath()));
            if (!reformat) {
                return s;
            }
            StringBuffer sb = new StringBuffer();
            for (String l : Arrays.asList(s.split("\n"))) {
                if (l.contains("=")) {
                    String w = l.trim();
                    String[] x = w.split("=");
                    if (x.length == 2) {
                        /* Quote those strings */
                        sb.append(String.format("%s = \"%s\"\n", x[0].trim(), x[1].trim()));
                    } else {
                        sb.append(l);
                        sb.append("\n");
                    }
                } else {
                    sb.append(l);
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        public String toString() {
            return "File: " + file;
        }
    }
}
