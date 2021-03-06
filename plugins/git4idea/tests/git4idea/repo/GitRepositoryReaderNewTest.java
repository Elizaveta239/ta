/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.repo;

import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.util.Condition;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitRemoteBranch;
import git4idea.test.GitSingleRepoTest;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import static git4idea.test.GitExecutor.git;
import static git4idea.test.GitExecutor.last;
import static git4idea.test.GitScenarios.commit;
import static git4idea.test.GitScenarios.conflict;
import static git4idea.test.GitTestUtil.makeCommit;

/**
 * {@link GitRepositoryReaderTest} reads information from the pre-created .git directory from a real project.
 * This one, on the other hand, operates on a live Git repository, putting it to various situations and checking the results.
 */
public class GitRepositoryReaderNewTest extends GitSingleRepoTest {

  // inspired by IDEA-93806
  public void test_rebase_with_conflicts_while_being_on_detached_HEAD() {
    conflict(myRepo, "feature");
    commit(myRepo);
    commit(myRepo);
    git("checkout HEAD^");
    git("rebase feature", true);

    GitBranchState state = readState();
    assertNull("Current branch can't be identified for this case", state.getCurrentBranch());
    assertEquals("State value is incorrect", Repository.State.REBASING, state.getState());
  }

  // inspired by IDEA-124052
  public void test_remote_reference_without_remote() {
    final String INVALID_REMOTE = "invalid-remote";
    final String INVALID_REMOTE_BRANCH = "master";
    git("update-ref refs/remotes/" + INVALID_REMOTE + "/" + INVALID_REMOTE_BRANCH + " HEAD");

    Collection<GitRemoteBranch> remoteBranches = readState().getRemoteBranches();
    assertTrue("Remote branch not found", ContainerUtil.exists(remoteBranches, new Condition<GitRemoteBranch>() {
      @Override
      public boolean value(GitRemoteBranch branch) {
        return branch.getNameForLocalOperations().equals(INVALID_REMOTE + "/" + INVALID_REMOTE_BRANCH);
      }
    }));
  }

  // inspired by IDEA-134286
  public void test_detached_HEAD() throws IOException {
    makeCommit("file.txt");
    git("checkout HEAD^");
    GitBranchState state = readState();
    assertEquals("Detached HEAD is not detected", GitRepository.State.DETACHED, state.getState());
    assertEquals("Detached HEAD hash is incorrect", last(), state.getCurrentRevision());
  }

  @NotNull
  private GitBranchState readState() {
    File gitDir = new File(myRepo.getRoot().getPath(), ".git");
    GitConfig config = GitConfig.read(myPlatformFacade, new File(gitDir, "config"));
    GitRepositoryReader reader = new GitRepositoryReader(gitDir);
    Collection<GitRemote> remotes = config.parseRemotes();
    return reader.readState(remotes);
  }

}
