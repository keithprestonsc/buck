/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import java.io.IOException;

import javax.annotation.Nullable;

public class TestRepositoryBuilder {
  private Optional<String> name;
  private ProjectFilesystem filesystem;
  private KnownBuildRuleTypes buildRuleTypes;
  private BuckConfig buckConfig;
  private AndroidDirectoryResolver androidDirectoryResolver;
  @Nullable
  private ProjectBuildFileParserFactory parserFactory;

  public TestRepositoryBuilder() throws InterruptedException, IOException {
    name = Optional.absent();
    filesystem = new FakeProjectFilesystem();
    buildRuleTypes = DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(filesystem);
    buckConfig = new FakeBuckConfig();
    androidDirectoryResolver = new FakeAndroidDirectoryResolver();
  }

  public TestRepositoryBuilder setName(String name) {
    this.name = Optional.of(name);
    return this;
  }

  public TestRepositoryBuilder setFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    return this;
  }

  public TestRepositoryBuilder setBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    return this;
  }

  public TestRepositoryBuilder setAndroidDirectoryResolver(AndroidDirectoryResolver resolver) {
    this.androidDirectoryResolver = resolver;
    return this;
  }

  public TestRepositoryBuilder setBuildFileParserFactory(ProjectBuildFileParserFactory factory) {
    this.parserFactory = factory;
    return this;
  }

  public Repository build() {
    if (parserFactory == null) {
      return new Repository(
          name,
          filesystem,
          buckConfig,
          buildRuleTypes,
          androidDirectoryResolver);
    }

    return new Repository(
        name,
        filesystem,
        buckConfig,
        buildRuleTypes,
        androidDirectoryResolver) {
      @Override
      public ProjectBuildFileParserFactory createBuildFileParserFactory(
          String pythonInterpreter,
          boolean useWatchmanGlob,
          Watchman watchman) {
        return parserFactory;
      }
    };

  }
}
