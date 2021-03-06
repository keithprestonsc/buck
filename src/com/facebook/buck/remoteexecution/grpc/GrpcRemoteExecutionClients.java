/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.TraceInfoProvider;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputDirectory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputFile;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.function.ThrowingConsumer;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** A RemoteExecution that sends jobs to a grpc-based remote execution service. */
public class GrpcRemoteExecutionClients implements RemoteExecutionClients {
  public static final Protocol PROTOCOL = new GrpcProtocol();
  private final ContentAddressedStorage storage;
  private final GrpcRemoteExecutionService executionService;
  private final ManagedChannel executionEngineChannel;
  private final ManagedChannel casChannel;

  /** A parsed read resource path. */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractParsedReadResource {
    String getInstanceName();

    Digest getDigest();
  }

  public GrpcRemoteExecutionClients(
      String instanceName,
      ManagedChannel executionEngineChannel,
      ManagedChannel casChannel,
      Optional<TraceInfoProvider> traceInfoProvider,
      BuckEventBus buckEventBus) {
    this.executionEngineChannel = executionEngineChannel;
    this.casChannel = casChannel;

    ByteStreamStub byteStreamStub = ByteStreamGrpc.newStub(casChannel);
    this.storage =
        createStorage(
            ContentAddressableStorageGrpc.newFutureStub(casChannel),
            byteStreamStub,
            instanceName,
            PROTOCOL,
            buckEventBus);
    ExecutionStub executionStub = ExecutionGrpc.newStub(executionEngineChannel);
    if (traceInfoProvider.isPresent()) {
      Metadata headers = new Metadata();
      headers.put(
          Metadata.Key.of("trace-id", Metadata.ASCII_STRING_MARSHALLER),
          traceInfoProvider.get().getTraceId());
      executionStub = MetadataUtils.attachHeaders(executionStub, headers);
    }
    this.executionService =
        new GrpcRemoteExecutionService(executionStub, byteStreamStub, instanceName);
  }

  private static String getReadResourceName(String instanceName, Protocol.Digest digest) {
    return String.format("%s/blobs/%s/%d", instanceName, digest.getHash(), digest.getSize());
  }

  /** Reads a ByteStream onto the arg consumer. */
  public static ListenableFuture<Void> readByteStream(
      String instanceName,
      Protocol.Digest digest,
      ByteStreamStub byteStreamStub,
      ThrowingConsumer<ByteString, IOException> dataConsumer) {
    String name = getReadResourceName(instanceName, digest);
    SettableFuture<Void> future = SettableFuture.create();
    byteStreamStub.read(
        ReadRequest.newBuilder().setResourceName(name).setReadLimit(0).setReadOffset(0).build(),
        new StreamObserver<ReadResponse>() {
          @Override
          public void onNext(ReadResponse value) {
            try {
              dataConsumer.accept(value.getData());
            } catch (IOException e) {
              onError(e);
            }
          }

          @Override
          public void onError(Throwable t) {
            future.setException(t);
          }

          @Override
          public void onCompleted() {
            future.set(null);
          }
        });
    return future;
  }

  @Override
  public RemoteExecutionService getRemoteExecutionService() {
    return executionService;
  }

  @Override
  public ContentAddressedStorage getContentAddressedStorage() {
    return storage;
  }

  @Override
  public Protocol getProtocol() {
    return PROTOCOL;
  }

  @Override
  public void close() throws IOException {
    closeChannel(casChannel);
    closeChannel(executionEngineChannel);
  }

  private static void closeChannel(ManagedChannel channel) throws IOException {
    channel.shutdown();
    try {
      channel.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private ContentAddressedStorage createStorage(
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Protocol protocol,
      BuckEventBus buckEventBus) {
    return new GrpcContentAddressableStorage(
        storageStub, byteStreamStub, instanceName, protocol, buckEventBus);
  }

  private static class GrpcRemoteExecutionService implements RemoteExecutionService {
    private final ExecutionStub executionStub;
    private final ByteStreamStub byteStreamStub;
    private final String instanceName;

    private GrpcRemoteExecutionService(
        ExecutionStub executionStub, ByteStreamStub byteStreamStub, String instanceName) {
      this.executionStub = executionStub;
      this.byteStreamStub = byteStreamStub;
      this.instanceName = instanceName;
    }

    @Override
    public ExecutionResult execute(Protocol.Digest actionDigest)
        throws IOException, InterruptedException {
      SettableFuture<Operation> future = SettableFuture.create();

      executionStub.execute(
          ExecuteRequest.newBuilder()
              .setInstanceName(instanceName)
              .setActionDigest(GrpcProtocol.get(actionDigest))
              .setSkipCacheLookup(false)
              .build(),
          new StreamObserver<Operation>() {
            @Nullable Operation op = null;

            @Override
            public void onNext(Operation value) {
              op = value;
            }

            @Override
            public void onError(Throwable t) {
              future.setException(t);
            }

            @Override
            public void onCompleted() {
              future.set(op);
            }
          });

      try {
        Operation operation = future.get();
        if (operation.hasError()) {
          throw new RuntimeException("Execution failed: " + operation.getError().getMessage());
        }

        if (!operation.hasResponse()) {
          throw new RuntimeException(
              "Invalid operation response: missing ExecutionResponse object");
        }

        ActionResult actionResult =
            operation.getResponse().unpack(ExecuteResponse.class).getResult();
        return new ExecutionResult() {
          @Override
          public List<OutputDirectory> getOutputDirectories() {
            return actionResult
                .getOutputDirectoriesList()
                .stream()
                .map(GrpcOutputDirectory::new)
                .collect(Collectors.toList());
          }

          @Override
          public List<OutputFile> getOutputFiles() {
            return actionResult
                .getOutputFilesList()
                .stream()
                .map(GrpcOutputFile::new)
                .collect(Collectors.toList());
          }

          @Override
          public int getExitCode() {
            return actionResult.getExitCode();
          }

          @Override
          public Optional<String> getStderr() {
            ByteString stderrRaw = actionResult.getStderrRaw();
            if (stderrRaw == null
                || (stderrRaw.isEmpty() && actionResult.getStderrDigest().getSizeBytes() > 0)) {
              System.err.println("Got stderr digest.");
              try {
                ByteString data = ByteString.EMPTY;
                readByteStream(
                        instanceName,
                        new GrpcDigest(actionResult.getStderrDigest()),
                        byteStreamStub,
                        data::concat)
                    .get();
                return Optional.of(data.toStringUtf8());
              } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
              }
            } else {
              System.err.println("Got raw stderr: " + stderrRaw.toStringUtf8());
              return Optional.of(stderrRaw.toStringUtf8());
            }
          }
        };
      } catch (ExecutionException e) {
        Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
        Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
        e.printStackTrace();
        throw new BuckUncheckedExecutionException(e.getCause());
      }
    }
  }
}
