/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.plugins.gradle.service.project.data;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.*;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataManager;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataService;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * @author Vladislav.Soroka
 * @since 7/17/2014
 */
@Order(ExternalSystemConstants.BUILTIN_SERVICE_ORDER)
public class ExternalProjectDataService implements ProjectDataService<ExternalProject, Project> {
  private static final Logger LOG = Logger.getInstance(ExternalProjectDataService.class);

  @NotNull public static final Key<ExternalProject> KEY = Key.create(ExternalProject.class, ProjectKeys.TASK.getProcessingWeight() + 1);

  @NotNull private final Map<Pair<ProjectSystemId, File>, ExternalProject> myExternalRootProjects;

  @NotNull private ProjectDataManager myProjectDataManager;

  private static final TObjectHashingStrategy<Pair<ProjectSystemId, File>> HASHING_STRATEGY =
    new TObjectHashingStrategy<Pair<ProjectSystemId, File>>() {
      @Override
      public int computeHashCode(Pair<ProjectSystemId, File> object) {
        try {
          return object.first.hashCode() + FileUtil.pathHashCode(object.second.getCanonicalPath());
        }
        catch (IOException e) {
          LOG.warn("unable to get canonical file path", e);
          return object.first.hashCode() + FileUtil.fileHashCode(object.second);
        }
      }

      @Override
      public boolean equals(Pair<ProjectSystemId, File> o1, Pair<ProjectSystemId, File> o2) {
        try {
          return o1.first.equals(o2.first) && FileUtil.pathsEqual(o1.second.getCanonicalPath(), o2.second.getCanonicalPath());
        }
        catch (IOException e) {
          LOG.warn("unable to get canonical file path", e);
          return o1.first.equals(o2.first) && FileUtil.filesEqual(o1.second, o2.second);
        }
      }
    };


  public ExternalProjectDataService(@NotNull ProjectDataManager projectDataManager) {
    myProjectDataManager = projectDataManager;
    myExternalRootProjects = new ConcurrentFactoryMap<Pair<ProjectSystemId, File>, ExternalProject>() {

      @Override
      protected Map<Pair<ProjectSystemId, File>, ExternalProject> createMap() {
        return ContainerUtil.newConcurrentMap(HASHING_STRATEGY);
      }

      @Nullable
      @Override
      protected ExternalProject create(Pair<ProjectSystemId, File> key) {
        return new ExternalProjectSerializer().load(key.first, key.second);
      }

      @Override
      public ExternalProject put(Pair<ProjectSystemId, File> key, ExternalProject value) {
        new ExternalProjectSerializer().save(value);
        return super.put(key, value);
      }
    };
  }

  @NotNull
  @Override
  public Key<ExternalProject> getTargetDataKey() {
    return KEY;
  }

  public void importData(@NotNull final Collection<DataNode<ExternalProject>> toImport,
                         @NotNull final Project project,
                         final boolean synchronous) {
    if (toImport.size() != 1) {
      throw new IllegalArgumentException(
        String.format("Expected to get a single external project but got %d: %s", toImport.size(), toImport));
    }
    saveExternalProject(toImport.iterator().next().getData());
  }

  @Override
  public void removeData(@NotNull final Collection<? extends Project> modules, @NotNull Project project, boolean synchronous) {
  }

  @Nullable
  public ExternalProject getRootExternalProject(@NotNull ProjectSystemId systemId, @NotNull File projectRootDir) {
    return myExternalRootProjects.get(Pair.create(systemId, projectRootDir));
  }

  public void saveExternalProject(@NotNull ExternalProject externalProject) {
    myExternalRootProjects.put(
      Pair.create(new ProjectSystemId(externalProject.getExternalSystemId()), externalProject.getProjectDir()),
      new DefaultExternalProject(externalProject)
    );
  }

  @Nullable
  public ExternalProject findExternalProject(@NotNull ExternalProject parentProject, @NotNull Module module) {
    String externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module);
    return externalProjectId != null ? findExternalProject(parentProject, externalProjectId) : null;
  }

  @Nullable
  private static ExternalProject findExternalProject(@NotNull ExternalProject parentProject, @NotNull String externalProjectId) {
    if (parentProject.getQName().equals(externalProjectId)) return parentProject;
    if (parentProject.getChildProjects().containsKey(externalProjectId)) {
      return parentProject.getChildProjects().get(externalProjectId);
    }
    for (ExternalProject externalProject : parentProject.getChildProjects().values()) {
      final ExternalProject project = findExternalProject(externalProject, externalProjectId);
      if (project != null) return project;
    }
    return null;
  }
}
