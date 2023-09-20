package org.codehaus.mojo.properties;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.net.URLConnection;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import static java.util.Objects.requireNonNull;

/**
 * An ArtifactRepoRemoteState maps to an
 * <a href="https://www.terraform.io/docs/language/settings/backends/artifactory.html">artifactory" backend</a> for
 * terraform
 *
 * @author mykelalvis
 *
 *         This will perform a download (wget/curl-ish action) against the joined values to fetch the tfstate file
 *
 */
public class ArtifactRepoRemoteState {
    private String id = "default";

    /**
     * id maps to server id for username/password
     */
    private String serverId;
    /**
     * BASE Url of the artifact repo (Artifactory or Nexus)
     */
    private String url;
    /**
     * Repo name
     */
    private String repo;
    /**
     * Subpath within repo of tfstate file
     */
    private String subpath;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public String getSubpath() {
        return subpath;
    }

    public void setSubpath(String subpath) {
        this.subpath = subpath;
    }

    private Server server;

    private String localUrl;

    /**
     *
     * @return contents as a valid JSON String
     */
    public String readContents() throws IOException {
        requireNonNull(serverId, "serverId is required");
        URL u = new URL(localUrl);
        Authenticator.setDefault(getAuthenticator());
        URLConnection conn = u.openConnection();

        try (InputStream ins = conn.getInputStream()) {
            return ReadJSONObjectAsFlattenedPropertiesMojo.readStream(ins);
        }
    }

    public ArtifactRepoRemoteState validate(ArtifactRepoRemoteState defaults, Settings settings)
            throws MojoExecutionException {
        if (defaults != null) {
            serverId = serverId == null ? defaults.serverId : serverId;
            repo = repo == null ? defaults.repo : repo;
            url = url == null ? defaults.url : url;
            subpath = subpath == null ? defaults.subpath : subpath;
        }
        if (serverId != null) {
            this.server = settings.getServer(serverId);
            if (this.server == null) {
                throw new MojoExecutionException("Server " + serverId + " not found in settings in " + id);
            }
        }
        this.localUrl = String.format(
                "%s/%s/%s",
                requireNonNull(url, "Base url must be supplied for " + serverId),
                requireNonNull(repo, "Repo must be supplied for " + serverId),
                requireNonNull(subpath, "Subpath must be supplied for " + serverId));
        return this;
    }

    private Authenticator getAuthenticator() {
        return new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        server.getUsername(), server.getPassword().toCharArray());
            }
        };
    }
}
