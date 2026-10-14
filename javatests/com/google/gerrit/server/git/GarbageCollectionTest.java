// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gerrit.common.data.GarbageCollectionResult;
import com.google.gerrit.common.data.GarbageCollectionResult.GcError;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.GcConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.plugincontext.PluginContext.PluginMetrics;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.project.ProjectCache;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class GarbageCollectionTest {
  private static final Project.NameKey FOO = Project.nameKey("foo");
  private static final Project.NameKey BAR = Project.nameKey("bar");

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Mock private GcConfig gcConfig;
  @Mock private DelegateRepository wrapper;

  private SitePaths site;
  private Config cfg;

  @Before
  public void setup() throws Exception {
    site = new SitePaths(temporaryFolder.newFolder().toPath());
    site.resolve("git").toFile().mkdir();
    cfg = new Config();
    cfg.setString("gerrit", null, "basePath", "git");
  }

  @Test
  public void shouldCallGcOnDelegatedRepositoryWhenDelegateRepositoryIsPassed() throws IOException {
    // given
    GarbageCollection objectUnderTest = prepareObjectForTesting();

    // when
    objectUnderTest.run(ImmutableList.of(FOO), false, null);

    // then
    verify(wrapper).delegate();
  }

  @Test
  public void shouldIsolateErrorAndContinueWithRemainingRepositoriesWithoutDuplicateGcFinished()
      throws IOException {
    GarbageCollectionQueue gcQueue = spy(new GarbageCollectionQueue());
    LocalDiskRepositoryManager repoManager =
        new LocalDiskRepositoryManager(site, cfg) {
          @Override
          public Repository openRepository(NameKey name) throws RepositoryNotFoundException {
            if (FOO.equals(name)) {
              throw new AssertionError("simulated Error during GC of " + name);
            }
            // Simulate a concurrent thread re-scheduling FOO after FOO finished GC.
            assertThat(gcQueue.addAll(ImmutableList.of(FOO))).containsExactly(FOO);
            Repository opened = super.openRepository(name);
            when(wrapper.delegate()).thenReturn(opened);
            when(wrapper.getConfig()).thenReturn(opened.getConfig());
            return wrapper;
          }
        };
    try (Repository fooRepo = repoManager.createRepository(FOO);
        Repository barRepo = repoManager.createRepository(BAR)) {
      assertThat(fooRepo).isNotNull();
      assertThat(barRepo).isNotNull();
    }
    GarbageCollection objectUnderTest =
        new GarbageCollection(
            repoManager,
            gcQueue,
            gcConfig,
            new PluginSetContext<>(new DynamicSet<>(), PluginMetrics.DISABLED_INSTANCE));
    StringWriter rawOutput = new StringWriter();
    PrintWriter bufferedWriter = new PrintWriter(new BufferedWriter(rawOutput, 8192));

    GarbageCollectionResult result =
        objectUnderTest.run(ImmutableList.of(FOO, BAR), false, bufferedWriter);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).hasSize(1);
    GcError error = result.getErrors().get(0);
    assertThat(error.getType()).isEqualTo(GcError.Type.GC_FAILED);
    assertThat(error.getProjectName()).isEqualTo(FOO);

    verify(wrapper).delegate();
    verify(gcQueue, times(1)).gcFinished(FOO);
    verify(gcQueue, times(1)).gcFinished(BAR);
    assertThat(gcQueue.addAll(ImmutableList.of(FOO, BAR))).containsExactly(BAR);
    assertThat(rawOutput.toString()).contains("collecting garbage for \"bar\":\n");
    assertThat(rawOutput.toString()).contains("done.\n\n");
  }

  @Test
  public void shouldDrainRemainingQueueWhenErrorEscapesLoopAndCatchErrorInRunner() {
    GarbageCollectionQueue gcQueue = spy(new GarbageCollectionQueue());
    LocalDiskRepositoryManager repoManager =
        new LocalDiskRepositoryManager(site, cfg) {
          @Override
          public Repository openRepository(NameKey name) {
            throw new AssertionError("simulated Error opening " + name);
          }
        };
    PrintWriter failingWriter =
        new PrintWriter(new StringWriter()) {
          @Override
          public void print(String s) {
            throw new AssertionError("simulated Error while logging GC failure");
          }
        };
    GarbageCollection objectUnderTest =
        new GarbageCollection(
            repoManager,
            gcQueue,
            gcConfig,
            new PluginSetContext<>(new DynamicSet<>(), PluginMetrics.DISABLED_INSTANCE));

    assertThrows(
        AssertionError.class,
        () -> objectUnderTest.run(ImmutableList.of(FOO, BAR), false, failingWriter));

    verify(gcQueue, times(1)).gcFinished(FOO);
    verify(gcQueue, times(1)).gcFinished(BAR);
    assertThat(gcQueue.addAll(ImmutableList.of(FOO, BAR))).containsExactly(FOO, BAR);

    GarbageCollection gc = mock(GarbageCollection.class);
    GarbageCollection.Factory gcFactory = mock(GarbageCollection.Factory.class);
    ProjectCache projectCache = mock(ProjectCache.class);
    when(projectCache.all()).thenReturn(ImmutableSortedSet.of(FOO));
    when(gcFactory.create()).thenReturn(gc);
    when(gc.run(ImmutableList.of(FOO))).thenThrow(new AssertionError("simulated fatal GC Error"));

    GarbageCollectionRunner runner = new GarbageCollectionRunner(gcFactory, projectCache);
    runner.run();
    verify(gc).run(ImmutableList.of(FOO));
  }

  private GarbageCollection prepareObjectForTesting() throws IOException {
    LocalDiskRepositoryManager repoManager = new DelegatedRepositoryManager(site, cfg, wrapper);
    try (Repository repo = repoManager.createRepository(FOO)) {
      assertThat(repo).isNotNull();
    }
    return new GarbageCollection(
        repoManager,
        new GarbageCollectionQueue(),
        gcConfig,
        new PluginSetContext<>(new DynamicSet<>(), PluginMetrics.DISABLED_INSTANCE));
  }

  private static final class DelegatedRepositoryManager extends LocalDiskRepositoryManager {
    private final DelegateRepository wrapper;

    private DelegatedRepositoryManager(SitePaths site, Config cfg, DelegateRepository wrapper) {
      super(site, cfg);
      this.wrapper = wrapper;
    }

    @Override
    public Repository openRepository(NameKey name) throws RepositoryNotFoundException {
      Repository opened = super.openRepository(name);
      when(wrapper.delegate()).thenReturn(opened);
      when(wrapper.getConfig()).thenReturn(opened.getConfig());
      return wrapper;
    }
  }
}
