package io.jenkins.plugins.git.forensics.reference;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.plugins.git.GitSCM;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;

import javax.annotation.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Called on Checkout of a Git Repository in Jenkins. This Class determines the Commits since the last Build
 * and writes them into a GitCommit RunAction to be accessed later.
 *
 * @author Arne Schöntag
 */
@Extension
public class GitCommitListener extends SCMListener {

    @Override
    public void onCheckout(Run<?,?> build, SCM scm, FilePath workspace, TaskListener listener, @CheckForNull File changelogFile, @CheckForNull SCMRevisionState pollingBaseline) throws Exception {
        if (!(scm instanceof GitSCM)) {
            return;
        }
        /* Looking for the latest Reversion (Commit) on the previous build.
         * If the previous build has no commits (f.e. manual triggered) then it will looked further
         * in the past until one is found or there is no previous build.
         */
        String latestReversionOfPreviousCommit = null;
        Run previous = build.getPreviousBuild();
        while (previous != null && latestReversionOfPreviousCommit == null) {
            GitCommit gitCommit = previous.getAction(GitCommit.class);
            if (gitCommit.getGitCommitLog().getReversions().isEmpty()) {
                previous = previous.getPreviousBuild();
            } else {
                latestReversionOfPreviousCommit = gitCommit.getGitCommitLog().getReversions().get(0);
            }
        }

        // Build Repo
        GitSCM gitSCM = (GitSCM) scm;
        EnvVars environment = build.getEnvironment(listener);
        GitClient gitClient = gitSCM.createClient(listener, environment, build, workspace);

        // Save new commits
        GitCommit gitCommit = gitClient.withRepository(new GitCommitCall(build, latestReversionOfPreviousCommit));
        build.addAction(gitCommit);
    }

    /**
     * Writes the Commits since last build into a GitCommit object.
     */
    static class GitCommitCall implements RepositoryCallback<GitCommit> {

        private static final long serialVersionUID = -5980402198857923793L;
        private transient final Run<?,?> build;
        private final String latestReversionOfPreviousCommit;

        public GitCommitCall(Run<?, ?> build, String latestReversionOfPreviousCommit) {
            this.build = build;
            this.latestReversionOfPreviousCommit = latestReversionOfPreviousCommit;
        }

        @Override
        public GitCommit invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
            GitCommit result = new GitCommit(build);
            List<String> newCommits = new ArrayList<>();
            Git git = new Git(repo);

            try {
                // Determine new commits to log since last build
                RevWalk walk = new RevWalk(repo);
                ObjectId head = repo.resolve(Constants.HEAD);
                ObjectId headCommit = walk.parseCommit(head);
                LogCommand logCommand = git.log().add(headCommit);
                Iterable<RevCommit> commits = null;
                commits = logCommand.call();
                Iterator<RevCommit> iterator = commits.iterator();
                RevCommit next;
                while (iterator.hasNext()) {
                    next = iterator.next();
                    String commitId = next.getId().toString();
                    // If latestReversionOfPreviousCommit is null, all commits will be added.
                    if (commitId.equals(latestReversionOfPreviousCommit)) {
                        break;
                    }
                    newCommits.add(commitId);
                }
            } catch (GitAPIException e) {
                e.printStackTrace();
            }

            result.getGitCommitLog().getReversions().addAll(newCommits);
            return result;
        }
    }

}
