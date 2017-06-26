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

package com.facebook.buck.android;

import com.android.common.SdkConstants;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * A {@link com.facebook.buck.rules.BuildRule} that gathers shared objects generated by {@code
 * ndk_library} and {@code prebuilt_native_library} rules into a directory. It also hashes the
 * shared objects collected and stores this metadata in a text file, to be used later by {@link
 * ExopackageInstaller}.
 */
public class CopyNativeLibraries extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final ImmutableSet<SourcePath> nativeLibDirectories;
  @AddToRuleKey private final ImmutableSet<TargetCpuType> cpuFilters;
  @AddToRuleKey private final ImmutableSet<StrippedObjectDescription> stripLibRules;
  @AddToRuleKey private final ImmutableSet<StrippedObjectDescription> stripLibAssetRules;

  private final String moduleName;

  protected CopyNativeLibraries(
      BuildRuleParams buildRuleParams,
      ImmutableSet<SourcePath> nativeLibDirectories,
      ImmutableSet<StrippedObjectDescription> stripLibRules,
      ImmutableSet<StrippedObjectDescription> stripLibAssetRules,
      ImmutableSet<TargetCpuType> cpuFilters,
      String moduleName) {
    super(buildRuleParams);
    this.nativeLibDirectories = nativeLibDirectories;
    this.stripLibRules = stripLibRules;
    this.stripLibAssetRules = stripLibAssetRules;
    this.cpuFilters = cpuFilters;
    this.moduleName = moduleName;
    Preconditions.checkArgument(
        !nativeLibDirectories.isEmpty()
            || !stripLibRules.isEmpty()
            || !stripLibAssetRules.isEmpty(),
        "There should be at least one native library to copy.");
  }

  public Path getPathToNativeLibsDir() {
    return getBinPath().resolve("libs");
  }

  public Path getPathToNativeLibsAssetsDir() {
    return getBinPath().resolve("assetLibs");
  }

  /**
   * Returns the path that is the immediate parent of {@link #getPathToNativeLibsAssetsDir()} and
   * {@link #getPathToNativeLibsDir()}.
   */
  public Path getPathToAllLibsDir() {
    return getBinPath();
  }

  public SourcePath getSourcePathToAllLibsDir() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToAllLibsDir());
  }

  public Path getPathToMetadataTxt() {
    return getBinPath().resolve("metadata.txt");
  }

  public SourcePath getSourcePathToMetadataTxt() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToMetadataTxt());
  }

  private Path getBinPath() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__native_" + moduleName + "_%s__");
  }

  @VisibleForTesting
  ImmutableSet<SourcePath> getNativeLibDirectories() {
    return nativeLibDirectories;
  }

  @VisibleForTesting
  ImmutableSet<StrippedObjectDescription> getStrippedObjectDescriptions() {
    return ImmutableSet.<StrippedObjectDescription>builder()
        .addAll(stripLibRules)
        .addAll(stripLibAssetRules)
        .build();
  }

  private void addStepsForCopyingStrippedNativeLibrariesOrAssets(
      BuildContext context,
      ProjectFilesystem filesystem,
      ImmutableSet<StrippedObjectDescription> strippedNativeLibrariesOrAssets,
      Path destinationRootDir,
      ImmutableList.Builder<Step> steps) {
    for (StrippedObjectDescription strippedObject : strippedNativeLibrariesOrAssets) {
      Optional<String> abiDirectoryComponent =
          getAbiDirectoryComponent(strippedObject.getTargetCpuType());
      Preconditions.checkState(abiDirectoryComponent.isPresent());

      Path destination =
          destinationRootDir
              .resolve(abiDirectoryComponent.get())
              .resolve(strippedObject.getStrippedObjectName());

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  destination.getParent())));
      steps.add(
          CopyStep.forFile(
              filesystem,
              context.getSourcePathResolver().getAbsolutePath(strippedObject.getSourcePath()),
              destination));
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getBinPath())));

    final Path pathToNativeLibs = getPathToNativeLibsDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToNativeLibs)));

    final Path pathToNativeLibsAssets = getPathToNativeLibsAssetsDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToNativeLibsAssets)));

    for (SourcePath nativeLibDir : nativeLibDirectories.asList().reverse()) {
      copyNativeLibrary(
          context,
          getProjectFilesystem(),
          context.getSourcePathResolver().getAbsolutePath(nativeLibDir),
          pathToNativeLibs,
          cpuFilters,
          steps);
    }

    addStepsForCopyingStrippedNativeLibrariesOrAssets(
        context, getProjectFilesystem(), stripLibRules, pathToNativeLibs, steps);

    addStepsForCopyingStrippedNativeLibrariesOrAssets(
        context, getProjectFilesystem(), stripLibAssetRules, pathToNativeLibsAssets, steps);

    final Path pathToMetadataTxt = getPathToMetadataTxt();
    steps.add(
        createMetadataStep(getProjectFilesystem(), getPathToMetadataTxt(), getPathToAllLibsDir()));

    buildableContext.recordArtifact(pathToNativeLibs);
    buildableContext.recordArtifact(pathToNativeLibsAssets);
    buildableContext.recordArtifact(pathToMetadataTxt);

    return steps.build();
  }

  public static Step createMetadataStep(
      ProjectFilesystem filesystem, Path pathToMetadataTxt, Path pathToAllLibsDir) {
    return new AbstractExecutionStep("hash_native_libs") {
      @Override
      public StepExecutionResult execute(ExecutionContext context)
          throws IOException, InterruptedException {
        ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
        try {
          for (Path nativeLib : filesystem.getFilesUnderPath(pathToAllLibsDir)) {
            Sha1HashCode filesha1 = filesystem.computeSha1(nativeLib);
            Path relativePath = pathToAllLibsDir.relativize(nativeLib);
            metadataLines.add(String.format("%s %s", relativePath, filesha1));
          }
          filesystem.writeLinesToPath(metadataLines.build(), pathToMetadataTxt);
        } catch (IOException e) {
          context.logError(e, "There was an error hashing native libraries.");
          return StepExecutionResult.ERROR;
        }
        return StepExecutionResult.SUCCESS;
      }
    };
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  public static void copyNativeLibrary(
      BuildContext context,
      final ProjectFilesystem filesystem,
      Path sourceDir,
      final Path destinationDir,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableList.Builder<Step> steps) {

    if (cpuFilters.isEmpty()) {
      steps.add(
          CopyStep.forDirectory(
              filesystem, sourceDir, destinationDir, CopyStep.DirectoryMode.CONTENTS_ONLY));
    } else {
      for (TargetCpuType cpuType : cpuFilters) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        final Path libSourceDir = sourceDir.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDir.resolve(abiDirectoryComponent.get());

        final MkdirStep mkDirStep =
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), filesystem, libDestinationDir));
        final CopyStep copyStep =
            CopyStep.forDirectory(
                filesystem, libSourceDir, libDestinationDir, CopyStep.DirectoryMode.CONTENTS_ONLY);
        steps.add(
            new Step() {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                // TODO(simons): Using a projectfilesystem here is almost definitely wrong.
                // This is because each library may come from different build rules, which may be in
                // different cells --- this check works by coincidence.
                if (!filesystem.exists(libSourceDir)) {
                  return StepExecutionResult.SUCCESS;
                }
                if (mkDirStep.execute(context).isSuccess()
                    && copyStep.execute(context).isSuccess()) {
                  return StepExecutionResult.SUCCESS;
                }
                return StepExecutionResult.ERROR;
              }

              @Override
              public String getShortName() {
                return "copy_native_libraries";
              }

              @Override
              public String getDescription(ExecutionContext context) {
                ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
                stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
                stringBuilder.add(mkDirStep.getDescription(context));
                stringBuilder.add(copyStep.getDescription(context));
                return Joiner.on(" && ").join(stringBuilder.build());
              }
            });
      }
    }

    // Rename native files named like "*-disguised-exe" to "lib*.so" so they will be unpacked
    // by the Android package installer.  Then they can be executed like normal binaries
    // on the device.
    steps.add(
        new AbstractExecutionStep("rename_native_executables") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            final ImmutableSet.Builder<Path> executablesBuilder = ImmutableSet.builder();
            try {
              filesystem.walkRelativeFileTree(
                  destinationDir,
                  new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      if (file.toString().endsWith("-disguised-exe")) {
                        executablesBuilder.add(file);
                      }
                      return FileVisitResult.CONTINUE;
                    }
                  });
              for (Path exePath : executablesBuilder.build()) {
                Path fakeSoPath =
                    Paths.get(
                        MorePaths.pathWithUnixSeparators(exePath)
                            .replaceAll("/([^/]+)-disguised-exe$", "/lib$1.so"));
                filesystem.move(exePath, fakeSoPath);
              }
            } catch (IOException e) {
              context.logError(e, "Renaming native executables failed.");
              return StepExecutionResult.ERROR;
            }
            return StepExecutionResult.SUCCESS;
          }
        });
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the respective ABI
   * subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'. This looks at the cpu
   * filter and returns the correct subdirectory. If cpu filter is not present or not supported,
   * returns Optional.empty();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    switch (cpuType) {
      case ARM:
        return Optional.of(SdkConstants.ABI_ARMEABI);
      case ARMV7:
        return Optional.of(SdkConstants.ABI_ARMEABI_V7A);
      case ARM64:
        return Optional.of(SdkConstants.ABI_ARM64_V8A);
      case X86:
        return Optional.of(SdkConstants.ABI_INTEL_ATOM);
      case X86_64:
        return Optional.of(SdkConstants.ABI_INTEL_ATOM64);
      case MIPS:
        return Optional.of(SdkConstants.ABI_MIPS);
      default:
        return Optional.empty();
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractStrippedObjectDescription implements RuleKeyAppendable {
    public abstract SourcePath getSourcePath();

    public abstract String getStrippedObjectName();

    public abstract TargetCpuType getTargetCpuType();

    public abstract APKModule getApkModule();

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("sourcePath", getSourcePath())
          .setReflectively("strippedObjectName", getStrippedObjectName())
          .setReflectively("targetCpuType", getTargetCpuType());
    }
  }
}
