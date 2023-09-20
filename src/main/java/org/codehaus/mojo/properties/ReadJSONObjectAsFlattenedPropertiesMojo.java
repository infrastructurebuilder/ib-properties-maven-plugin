package org.codehaus.mojo.properties;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

import com.github.wnameless.json.flattener.JsonFlattener;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.json.JSONObject;

/**
 * Copied from the read-project-properties goal
 */
@Mojo(name = "read-json-as-properties", defaultPhase = LifecyclePhase.NONE, requiresProject = true, threadSafe = true)
public class ReadJSONObjectAsFlattenedPropertiesMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    private Settings settings;

    /*
     * It is possible to set defaults for everything if you have lots of remote
     * states
     */
    @Parameter
    private ArtifactRepoRemoteState artifactRepoRemoteStateDefaults = null;

    /* LATER ITEMS OVERRIDE EARLIER ITEMS */
    @Parameter
    private ArtifactRepoRemoteState artifactRepoRemoteStates[] = new ArtifactRepoRemoteState[0];

    /**
     * The properties files that will be used when reading properties.
     */
    @Parameter
    private File[] localPaths = new File[0];

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

    /*
     * It is possible to set defaults for everything if you have lots of remote
     * states
     */
    @Parameter
    private S3RemoteState s3RemoteStateDefaults = null;
    /**
     * The URLs that will be used when reading properties. These may be non-standard URLs of the form
     * <code>classpath:com/company/resource.properties</code>. Note that the type is not <code>URL</code> for this
     * reason and therefore will be explicitly checked by this Mojo.
     */
    @Parameter
    private S3RemoteState[] s3Urls = new S3RemoteState[0];

    /**
     * Default scope for test access.
     *
     * @param urls The URLs to set for tests.
     */
    public void setS3Urls(S3RemoteState[] urls) {
        if (urls == null) {
            this.s3Urls = null;
        } else {
            this.s3Urls = new S3RemoteState[urls.length];
            System.arraycopy(urls, 0, this.s3Urls, 0, urls.length);
        }
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
        checkParameters();

        loadFiles();

        loadUrls(s3RemoteStateDefaults);

        loadArtifactRepoResources(artifactRepoRemoteStateDefaults);

        resolveProperties();
    }

    private void checkParameters() throws MojoExecutionException {
        int count = 0;
        if (localPaths.length > 0) {
            count++;
        }
        if (s3Urls.length > 0) {
            count++;
        }
        if (artifactRepoRemoteStates.length > 0) {
            count++;
        }
        if (count != 1) {
            throw new MojoExecutionException(
                    "Set localPaths, S3 URLs, or ArtifactRepoResources but not multiples - otherwise "
                            + "no order of precedence can be guaranteed");
        }
    }

    private void loadFiles() throws MojoExecutionException {
        for (int i = 0; i < localPaths.length; i++) {
            load(new LocalPathResource(localPaths[i]));
        }
    }

    private void loadArtifactRepoResources(ArtifactRepoRemoteState defaults) throws MojoExecutionException {
        for (int i = 0; i < artifactRepoRemoteStates.length; i++) {
            load(new ArtifactRepoResource(artifactRepoRemoteStates[i], defaults, settings));
        }
    }

    private void loadUrls(S3RemoteState defaults) throws MojoExecutionException {
        for (int i = 0; i < s3Urls.length; i++) {
            load(new S3UrlResource(s3Urls[i], defaults, settings));
        }
    }

    private void load(TFStateResource resource) throws MojoExecutionException {
        if (resource.canBeOpened()) {
            loadProperties(resource);
        } else {
            missing(resource);
        }
    }

    private void loadProperties(TFStateResource resource) throws MojoExecutionException {
        try {
            getLog().debug("Loading properties from " + resource);

            final Properties stream = resource.getJSONObjectProperties();

            Properties projectProperties = project.getProperties();
            for (String key : stream.stringPropertyNames()) {
                projectProperties.put(keyPrefix != null ? keyPrefix + key : key, stream.getProperty(key));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading TFState from " + resource, e);
        }
    }

    private void missing(TFStateResource resource) throws MojoExecutionException {
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

    private abstract static class TFStateResource {
        private JSONObject stream;
        private Properties properties = new Properties();

        public abstract boolean canBeOpened();

        protected abstract String openJSONString() throws IOException;

        public Properties getJSONObjectProperties() throws IOException {
            if (stream == null) {
                stream = new JSONObject(openJSONString());
            }
            String tt = JsonFlattener.flatten(stream.toString());

            JSONObject jj = new JSONObject(tt);

            SortedSet<String> s = new TreeSet<>(jj.keySet());
            for (String key : s) {
                Object v = jj.get(key);
                properties.setProperty(key, v.toString());
            }

            return properties;
        }
    }

    private static class LocalPathResource extends TFStateResource {
        private final File file;

        LocalPathResource(File file) {
            this.file = file;
        }

        public boolean canBeOpened() {
            return file.exists();
        }

        protected String openJSONString() throws IOException {
            return new String(Files.readAllBytes(file.toPath()));
        }

        public String toString() {
            return "File: " + file;
        }
    }

    private static class ArtifactRepoResource extends TFStateResource {
        private ArtifactRepoRemoteState target;

        ArtifactRepoResource(ArtifactRepoRemoteState rs, ArtifactRepoRemoteState defaults, Settings settings)
                throws MojoExecutionException {
            this.target = Objects.requireNonNull(rs).validate(defaults, settings);
        }

        @Override
        public boolean canBeOpened() {
            throw new RuntimeException("HELP HELP I'M BEING REPRESED");
        }

        @Override
        protected String openJSONString() throws IOException {
            return target.readContents();
        }
    }

    private static class S3UrlResource extends TFStateResource {
        private final S3RemoteState rs;

        S3UrlResource(S3RemoteState rs, S3RemoteState defaults, Settings settings) throws MojoExecutionException {
            this.rs = Objects.requireNonNull(rs, "s3 remote state must not be null")
                    .validate(defaults, settings);
        }

        public boolean canBeOpened() {
            try {
                openJSONString();
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        protected String openJSONString() throws IOException {
            return rs.readContents();
        }

        public String toString() {
            return rs.toString();
        }
    }

    public static final String readStream(InputStream conn) throws IOException {
        StringBuffer sb = new StringBuffer();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn))) {
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                sb.append(inputLine);
            }
        }
        try {
            JSONObject o = new JSONObject(sb.toString());
        } catch (Throwable t) {
            throw new IOException("Cannot create JSONObject from " + sb.toString(), t);
        }
        return sb.toString();
    }
}
