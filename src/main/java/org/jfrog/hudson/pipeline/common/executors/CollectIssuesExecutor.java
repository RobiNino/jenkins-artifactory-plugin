package org.jfrog.hudson.pipeline.common.executors;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import org.jfrog.build.api.Issues;
import org.jfrog.build.api.IssuesCollectionConfig;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.issuesCollection.IssuesCollector;
import org.jfrog.hudson.pipeline.common.Utils;
import org.jfrog.hudson.pipeline.common.types.ArtifactoryServer;
import org.jfrog.hudson.pipeline.common.types.buildInfo.BuildInfoAccessor;
import org.jfrog.hudson.pipeline.common.types.buildInfo.TrackedIssues;
import org.jfrog.hudson.util.JenkinsBuildInfoLog;

import java.io.File;
import java.io.IOException;

import static org.jfrog.build.api.IssuesCollectionConfig.ISSUES_COLLECTION_ERROR_PREFIX;

public class CollectIssuesExecutor implements Executor {
    private transient Run build;
    private transient TaskListener listener;
    private transient FilePath ws;
    private String buildName;
    private String config;
    private TrackedIssues trackedIssues;
    private ArtifactoryServer pipelineServer;

    // In declarative, build name is expected to be passed as an argument (in scripted taken from the object).
    public CollectIssuesExecutor(Run build, TaskListener listener, FilePath ws, String buildName,
                                 String config, TrackedIssues trackedIssues, ArtifactoryServer pipelineServer) {
        this.build = build;
        this.listener = listener;
        this.ws = ws;
        this.buildName = buildName;
        this.config = config;
        this.trackedIssues = trackedIssues;
        this.pipelineServer = pipelineServer;
    }

    public void execute() throws IOException, InterruptedException {
        IssuesCollector collector = new IssuesCollector();
        IssuesCollectionConfig parsedConfig = collector.parseConfig(config);

        ArtifactoryBuildInfoClient client = getBuildInfoClient(pipelineServer, build, listener);
        String previousRevision = collector.getPreviousVcsRevision(client, buildName);

        FilePath dotGitPath = Utils.getDotGitPath(ws);
        if (dotGitPath == null) {
            throw new IOException(ISSUES_COLLECTION_ERROR_PREFIX + "Could not find .git");
        }
        Issues oldIssues = trackedIssues.getIssues();
        Issues newIssues = dotGitPath.act(new MasterToSlaveFileCallable<Issues>() {
            public Issues invoke(File f, VirtualChannel channel) throws InterruptedException, IOException {
                return collector.doCollect(f, new JenkinsBuildInfoLog(listener), parsedConfig, previousRevision);
            }
        });

        newIssues.append(oldIssues);
        trackedIssues.setIssues(newIssues);
    }

    private ArtifactoryBuildInfoClient getBuildInfoClient(ArtifactoryServer pipelineServer, Run build, TaskListener listener) {
        org.jfrog.hudson.ArtifactoryServer server = Utils.prepareArtifactoryServer(null, pipelineServer);
        return new BuildInfoAccessor(null).createArtifactoryClient(server, build, listener);
    }
}
