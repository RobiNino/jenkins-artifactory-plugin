package org.jfrog.hudson.generic;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.dependency.UserBuildDependency;
import org.jfrog.build.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.client.ArtifactoryDependenciesClient;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.hudson.ArtifactoryBuilder;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.BuildInfoAwareConfigurator;
import org.jfrog.hudson.BuildInfoResultAction;
import org.jfrog.hudson.DeployerOverrider;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.action.ActionableHelper;
import org.jfrog.hudson.release.UnifiedPromoteBuildAction;
import org.jfrog.hudson.util.Credentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Freestyle Generic configurator
 *
 * @author Shay Yaakov
 */
public class ArtifactoryGenericConfigurator extends BuildWrapper implements DeployerOverrider,
        BuildInfoAwareConfigurator {

    private final ServerDetails details;
    private final Credentials overridingDeployerCredentials;
    private final String deployPattern;
    private final String resolvePattern;
    private final String matrixParams;

    private final boolean deployBuildInfo;
    /**
     * Include environment variables in the generated build info
     */
    private final boolean includeEnvVars;
    private final boolean discardOldBuilds;
    private final boolean discardBuildArtifacts;
    private final boolean keepArchivedArtifacts;
    private transient List<Dependency> publishedDependencies;
    private transient List<UserBuildDependency> buildDependencies;

    @DataBoundConstructor
    public ArtifactoryGenericConfigurator(ServerDetails details, Credentials overridingDeployerCredentials,
            String deployPattern, String resolvePattern, String matrixParams, boolean deployBuildInfo,
            boolean includeEnvVars, boolean discardOldBuilds, boolean discardBuildArtifacts,
            boolean keepArchivedArtifacts) {
        this.details = details;
        this.overridingDeployerCredentials = overridingDeployerCredentials;
        this.deployPattern = deployPattern;
        this.resolvePattern = resolvePattern;
        this.matrixParams = matrixParams;
        this.deployBuildInfo = deployBuildInfo;
        this.includeEnvVars = includeEnvVars;
        this.discardOldBuilds = discardOldBuilds;
        this.discardBuildArtifacts = discardBuildArtifacts;
        this.keepArchivedArtifacts = keepArchivedArtifacts;
    }

    public String getArtifactoryName() {
        return details != null ? details.artifactoryName : null;
    }

    public boolean isOverridingDefaultDeployer() {
        return getOverridingDeployerCredentials() != null;
    }

    public String getRepositoryKey() {
        return details.repositoryKey;
    }

    public Credentials getOverridingDeployerCredentials() {
        return overridingDeployerCredentials;
    }

    public String getDeployPattern() {
        return deployPattern;
    }

    public String getResolvePattern() {
        return resolvePattern;
    }

    public String getMatrixParams() {
        return matrixParams;
    }

    public boolean isIncludeEnvVars() {
        return includeEnvVars;
    }

    public boolean isRunChecks() {
        // There is no use of license checks in a generic build
        return false;
    }

    public String getViolationRecipients() {
        return null;
    }

    public boolean isIncludePublishArtifacts() {
        return false;
    }

    public String getScopes() {
        return null;
    }

    public boolean isLicenseAutoDiscovery() {
        return false;
    }

    public boolean isDiscardOldBuilds() {
        return discardOldBuilds;
    }

    public boolean isDiscardBuildArtifacts() {
        return discardBuildArtifacts;
    }

    public boolean isKeepArchivedArtifacts() {
        return keepArchivedArtifacts;
    }

    public ArtifactoryServer getArtifactoryServer() {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(getArtifactoryName())) {
                return server;
            }
        }
        return null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project) {
        return ActionableHelper.getArtifactoryProjectAction(details.artifactoryName, project);
    }

    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        final String artifactoryServerName = getArtifactoryName();
        if (StringUtils.isBlank(artifactoryServerName)) {
            return super.setUp(build, launcher, listener);
        }
        final ArtifactoryServer artifactoryServer = getArtifactoryServer();
        if (artifactoryServer == null) {
            listener.getLogger().format("No Artifactory server configured for %s. " +
                    "Please check your configuration.", artifactoryServerName).println();
            build.setResult(Result.FAILURE);
            throw new IllegalArgumentException("No Artifactory server configured for " + artifactoryServerName);
        }

        Credentials preferredDeployer;
        ArtifactoryServer server = getArtifactoryServer();
        if (isOverridingDefaultDeployer()) {
            preferredDeployer = getOverridingDeployerCredentials();
        } else {
            preferredDeployer = server.getResolvingCredentials();
        }
        ArtifactoryDependenciesClient dependenciesClient = server.createArtifactoryDependenciesClient(
                preferredDeployer.getUsername(), preferredDeployer.getPassword(), Hudson.getInstance().proxy,
                listener);
        try {
            GenericArtifactsResolver artifactsResolver = new GenericArtifactsResolver(build,
                    ArtifactoryGenericConfigurator.this, listener, dependenciesClient);
            publishedDependencies = artifactsResolver.retrievePublishedDependencies();
            buildDependencies = artifactsResolver.retrieveBuildDependencies();

            return createEnvironmentOnSuccessfulSetup();
        } catch (Exception e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } finally {
            dependenciesClient.shutdown();
        }

        return null;
    }

    private Environment createEnvironmentOnSuccessfulSetup() {
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                Result result = build.getResult();
                if (result != null && result.isWorseThan(Result.SUCCESS)) {
                    return true;    // build failed. Don't publish
                }

                Credentials preferredDeployer;
                ArtifactoryServer server = getArtifactoryServer();
                if (isOverridingDefaultDeployer()) {
                    preferredDeployer = getOverridingDeployerCredentials();
                } else {
                    preferredDeployer = server.getResolvingCredentials();
                }
                ArtifactoryBuildInfoClient client = server.createArtifactoryClient(preferredDeployer.getUsername(),
                        preferredDeployer.getPassword());
                try {
                    GenericArtifactsDeployer artifactsDeployer = new GenericArtifactsDeployer(build,
                            ArtifactoryGenericConfigurator.this, listener, client);
                    artifactsDeployer.deploy();

                    Set<DeployDetails> deployedArtifacts = artifactsDeployer.getDeployedArtifacts();
                    if (deployBuildInfo) {
                        new GenericBuildInfoDeployer(ArtifactoryGenericConfigurator.this, client, build,
                                listener, deployedArtifacts, buildDependencies, publishedDependencies).deploy();
                        // add the result action (prefer always the same index)
                        build.getActions().add(0, new BuildInfoResultAction(getArtifactoryName(), build));
                        build.getActions().add(new UnifiedPromoteBuildAction<ArtifactoryGenericConfigurator>(build,
                                ArtifactoryGenericConfigurator.this));
                    }

                    if (!keepArchivedArtifacts) {
                        // remove the local artifacts directory created for remote agents
                        File artifactsDir = new File(build.getRootDir(), GenericArtifactsDeployer.LOCAL_ARTIFACTS_DIR);
                        if (artifactsDir.exists()) {
                            Util.deleteRecursive(artifactsDir);
                        }
                    }

                    return true;
                } catch (Exception e) {
                    e.printStackTrace(listener.error(e.getMessage()));
                } finally {
                    client.shutdown();
                }

                // failed
                build.setResult(Result.FAILURE);
                return true;
            }
        };
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(ArtifactoryGenericConfigurator.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return item.getClass().isAssignableFrom(FreeStyleProject.class);
        }

        @Override
        public String getDisplayName() {
            return "Generic-Artifactory Integration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this, "generic");
            save();
            return true;
        }

        /**
         * Returns the list of {@link org.jfrog.hudson.ArtifactoryServer} configured.
         *
         * @return can be empty but never null.
         */
        public List<ArtifactoryServer> getArtifactoryServers() {
            ArtifactoryBuilder.DescriptorImpl descriptor = (ArtifactoryBuilder.DescriptorImpl)
                    Hudson.getInstance().getDescriptor(ArtifactoryBuilder.class);
            return descriptor.getArtifactoryServers();
        }
    }
}