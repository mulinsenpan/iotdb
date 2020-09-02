/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.client.sync;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.client.async.AsyncMetaClient;
import org.apache.iotdb.cluster.log.Snapshot;
import org.apache.iotdb.cluster.log.snapshot.SnapshotFactory;
import org.apache.iotdb.cluster.rpc.thrift.AddNodeResponse;
import org.apache.iotdb.cluster.rpc.thrift.CheckStatusResponse;
import org.apache.iotdb.cluster.rpc.thrift.ExecutNonQueryReq;
import org.apache.iotdb.cluster.rpc.thrift.GetAggrResultRequest;
import org.apache.iotdb.cluster.rpc.thrift.GroupByRequest;
import org.apache.iotdb.cluster.rpc.thrift.LastQueryRequest;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.PreviousFillRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSchemaRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSnapshotRequest;
import org.apache.iotdb.cluster.rpc.thrift.RaftService.AsyncClient;
import org.apache.iotdb.cluster.rpc.thrift.SingleSeriesQueryRequest;
import org.apache.iotdb.cluster.rpc.thrift.StartUpStatus;
import org.apache.iotdb.cluster.rpc.thrift.TNodeStatus;
import org.apache.iotdb.cluster.server.RaftServer;
import org.apache.iotdb.cluster.server.handlers.caller.GenericHandler;
import org.apache.iotdb.cluster.server.handlers.caller.GetChildNodeNextLevelPathHandler;
import org.apache.iotdb.cluster.server.handlers.caller.GetNodesListHandler;
import org.apache.iotdb.cluster.server.handlers.caller.GetTimeseriesSchemaHandler;
import org.apache.iotdb.cluster.server.handlers.caller.JoinClusterHandler;
import org.apache.iotdb.cluster.server.handlers.caller.PullMeasurementSchemaHandler;
import org.apache.iotdb.cluster.server.handlers.caller.PullSnapshotHandler;
import org.apache.iotdb.cluster.server.handlers.caller.PullTimeseriesSchemaHandler;
import org.apache.iotdb.cluster.server.handlers.forwarder.ForwardPlanHandler;
import org.apache.iotdb.cluster.utils.PlanSerializer;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowTimeSeriesPlan;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.utils.SerializeUtils;
import org.apache.iotdb.service.rpc.thrift.TSStatus;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.filter.TimeFilter;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.read.filter.factory.FilterFactory;
import org.apache.iotdb.tsfile.read.filter.operator.AndFilter;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.schema.TimeseriesSchema;
import org.apache.thrift.TException;

/**
 * SyncClientAdaptor convert the async of AsyncClient method call to a sync one by synchronizing on
 * an AtomicReference of the return value of an RPC, and wait for at most connectionTimeoutInMS
 * until the reference is set by the handler or the request timeouts.
 */
@SuppressWarnings("java:S2274") // enable timeout
public class SyncClientAdaptor {

  private SyncClientAdaptor() {
    // static class
  }

  public static Long removeNode(AsyncMetaClient asyncMetaClient, Node nodeToRemove)
      throws TException, InterruptedException {
    AtomicReference<Long> responseRef = new AtomicReference<>();
    GenericHandler<Long> handler = new GenericHandler<>(asyncMetaClient.getNode(), responseRef);
    synchronized (responseRef) {
      asyncMetaClient.removeNode(nodeToRemove, handler);
      responseRef.wait(RaftServer.getConnectionTimeoutInMS());
    }
    return responseRef.get();
  }

  public static Boolean matchTerm(AsyncClient client, Node target, long prevLogIndex,
      long prevLogTerm, Node header) throws TException, InterruptedException {
    AtomicReference<Boolean> resultRef = new AtomicReference<>(false);
    GenericHandler<Boolean> matchTermHandler = new GenericHandler<>(target, resultRef);

    synchronized (resultRef) {
      client.matchTerm(prevLogIndex, prevLogTerm, header, matchTermHandler);
      resultRef.wait(RaftServer.getConnectionTimeoutInMS());
    }
    return resultRef.get();
  }

  public static Long querySingleSeriesByTimestamp(AsyncDataClient client,
      SingleSeriesQueryRequest request)
      throws TException, InterruptedException {
    AtomicReference<Long> result = new AtomicReference<>();
    GenericHandler<Long> handler = new GenericHandler<>(client.getNode(), result);
    synchronized (result) {
      client.querySingleSeriesByTimestamp(request, handler);
      result.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return result.get();
  }

  public static Long querySingleSeries(AsyncDataClient client, SingleSeriesQueryRequest request,
      long timeOffset) throws TException, InterruptedException {
    AtomicReference<Long> result = new AtomicReference<>();
    GenericHandler<Long> handler = new GenericHandler<>(client.getNode(), result);
    Filter newFilter;
    // add timestamp to as a timeFilter to skip the data which has been read
    if (request.isSetTimeFilterBytes()) {
      Filter timeFilter = FilterFactory.deserialize(request.timeFilterBytes);
      newFilter = new AndFilter(timeFilter, TimeFilter.gt(timeOffset));
    } else {
      newFilter = TimeFilter.gt(timeOffset);
    }
    request.setTimeFilterBytes(SerializeUtils.serializeFilter(newFilter));

    synchronized (result) {
      client.querySingleSeries(request, handler);
      result.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return result.get();
  }

  public static List<String> getNodeList(AsyncDataClient client, Node header,
      String schemaPattern, int level) throws TException, InterruptedException {
    GetNodesListHandler handler = new GetNodesListHandler();
    AtomicReference<List<String>> response = new AtomicReference<>(null);
    handler.setResponse(response);
    handler.setContact(client.getNode());
    synchronized (response) {
      client.getNodeList(header, schemaPattern, level, handler);
      response.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return response.get();
  }

  public static Set<String> getNextChildren(AsyncDataClient client, Node header, String path)
      throws TException, InterruptedException {
    GetChildNodeNextLevelPathHandler handler = new GetChildNodeNextLevelPathHandler();
    AtomicReference<Set<String>> response = new AtomicReference<>(null);
    handler.setResponse(response);
    handler.setContact(client.getNode());
    synchronized (response) {
      client.getChildNodePathInNextLevel(header, path, handler);
      response.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return response.get();
  }

  public static ByteBuffer getAllMeasurementSchema(AsyncDataClient client,
      Node header, ShowTimeSeriesPlan plan)
      throws IOException, InterruptedException, TException {
    GetTimeseriesSchemaHandler handler = new GetTimeseriesSchemaHandler();
    AtomicReference<ByteBuffer> response = new AtomicReference<>(null);
    handler.setResponse(response);

    handler.setContact(client.getNode());
    synchronized (response) {
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
      plan.serialize(dataOutputStream);
      client.getAllMeasurementSchema(header, ByteBuffer.wrap(byteArrayOutputStream.toByteArray()),
          handler);
      response.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return response.get();
  }

  public static TNodeStatus queryNodeStatus(AsyncMetaClient client)
      throws TException, InterruptedException {
    AtomicReference<TNodeStatus> resultRef = new AtomicReference<>();
    GenericHandler<TNodeStatus> handler = new GenericHandler<>(client.getNode(), resultRef);
    synchronized (resultRef) {
      client.queryNodeStatus(handler);
      resultRef.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return resultRef.get();
  }

  public static CheckStatusResponse checkStatus(AsyncMetaClient client, StartUpStatus startUpStatus)
      throws TException, InterruptedException {
    AtomicReference<CheckStatusResponse> resultRef = new AtomicReference<>();
    GenericHandler<CheckStatusResponse> handler = new GenericHandler<>(client.getNode(), resultRef);
    synchronized (resultRef) {
      client.checkStatus(startUpStatus, handler);
      resultRef.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return resultRef.get();
  }

  public static AddNodeResponse addNode(AsyncMetaClient client, Node thisNode,
      StartUpStatus startUpStatus)
      throws TException, InterruptedException {
    JoinClusterHandler handler = new JoinClusterHandler();
    AtomicReference<AddNodeResponse> response = new AtomicReference<>(null);
    handler.setResponse(response);
    response.set(null);
    handler.setContact(client.getNode());
    synchronized (response) {
      client.addNode(thisNode, startUpStatus, handler);
      response.wait(60 * 1000L);
    }
    return response.get();
  }

  public static List<MeasurementSchema> pullMeasurementSchema(AsyncDataClient client,
      PullSchemaRequest pullSchemaRequest) throws TException, InterruptedException {
    AtomicReference<List<MeasurementSchema>> timeseriesSchemas = new AtomicReference<>();
    synchronized (timeseriesSchemas) {
      client.pullMeasurementSchema(pullSchemaRequest,
          new PullMeasurementSchemaHandler(client.getNode(), pullSchemaRequest.getPrefixPaths(),
              timeseriesSchemas));
      timeseriesSchemas.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return timeseriesSchemas.get();
  }

  public static List<TimeseriesSchema> pullTimeseriesSchema(AsyncDataClient client,
      PullSchemaRequest pullSchemaRequest) throws TException, InterruptedException {
    AtomicReference<List<TimeseriesSchema>> timeseriesSchemas = new AtomicReference<>();
    synchronized (timeseriesSchemas) {
      client.pullTimeSeriesSchema(pullSchemaRequest,
          new PullTimeseriesSchemaHandler(client.getNode(), pullSchemaRequest.getPrefixPaths(),
              timeseriesSchemas));
      timeseriesSchemas.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return timeseriesSchemas.get();
  }

  public static List<ByteBuffer> getAggrResult(AsyncDataClient client, GetAggrResultRequest request)
      throws TException, InterruptedException {
    AtomicReference<List<ByteBuffer>> resultReference = new AtomicReference<>();
    GenericHandler<List<ByteBuffer>> handler = new GenericHandler<>(client.getNode(),
        resultReference);
    synchronized (resultReference) {
      resultReference.set(null);
      client.getAggrResult(request, handler);
      resultReference.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return resultReference.get();
  }

  public static List<String> getUnregisteredMeasurements(AsyncDataClient client, Node header,
      List<String> seriesPaths) throws TException, InterruptedException {
    AtomicReference<List<String>> remoteResult = new AtomicReference<>();
    GenericHandler<List<String>> handler = new GenericHandler<>(client.getNode(), remoteResult);
    synchronized (remoteResult) {
      client.getUnregisteredTimeseries(header, seriesPaths, handler);
      remoteResult.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return remoteResult.get();
  }

  public static List<String> getAllPaths(AsyncDataClient client, Node header,
      List<String> pathsToQuery)
      throws InterruptedException, TException {
    AtomicReference<List<String>> remoteResult = new AtomicReference<>();
    GenericHandler<List<String>> handler = new GenericHandler<>(client.getNode(), remoteResult);
    synchronized (remoteResult) {
      client.getAllPaths(header, pathsToQuery, handler);
      remoteResult.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return remoteResult.get();
  }

  public static Integer getPathCount(AsyncDataClient client, Node header, List<String> pathsToQuery,
      int level)
      throws InterruptedException, TException {
    AtomicReference<Integer> remoteResult = new AtomicReference<>(null);
    GenericHandler<Integer> handler = new GenericHandler<>(client.getNode(), remoteResult);
    synchronized (remoteResult) {
      client.getPathCount(header, pathsToQuery, level, handler);
      remoteResult.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return remoteResult.get();
  }

  public static Set<String> getAllDevices(AsyncDataClient client, Node header,
      List<String> pathsToQuery)
      throws InterruptedException, TException {
    AtomicReference<Set<String>> remoteResult = new AtomicReference<>();
    GenericHandler<Set<String>> handler = new GenericHandler<>(client.getNode(), remoteResult);
    synchronized (remoteResult) {
      client.getAllDevices(header, pathsToQuery, handler);
      remoteResult.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return remoteResult.get();
  }

  public static Long getGroupByExecutor(AsyncDataClient client, GroupByRequest request)
      throws TException, InterruptedException {
    AtomicReference<Long> result = new AtomicReference<>();
    GenericHandler<Long> handler = new GenericHandler<>(client.getNode(), result);
    synchronized (result) {
      result.set(null);
      client.getGroupByExecutor(request, handler);
      result.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return result.get();
  }

  public static ByteBuffer previousFill(AsyncDataClient client, PreviousFillRequest request)
      throws TException, InterruptedException {
    AtomicReference<ByteBuffer> resultRef = new AtomicReference<>();
    GenericHandler<ByteBuffer> nodeHandler = new GenericHandler<>(client.getNode(), resultRef);
    synchronized (resultRef) {
      client.previousFill(request, nodeHandler);
      resultRef.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return resultRef.get();
  }

  public static TSStatus executeNonQuery(AsyncClient client, PhysicalPlan plan, Node header,
      Node receiver) throws IOException, TException, InterruptedException {

    AtomicReference<TSStatus> status = new AtomicReference<>();
    ExecutNonQueryReq req = new ExecutNonQueryReq();
    req.planBytes = ByteBuffer.wrap(PlanSerializer.instance.serialize(plan));
    if (header != null) {
      req.setHeader(header);
    }

    synchronized (status) {
      client.executeNonQueryPlan(req, new ForwardPlanHandler(status, plan, receiver));
      status.wait(RaftServer.getWriteOperationTimeoutMS());
    }

    return status.get();
  }

  public static ByteBuffer readFile(AsyncDataClient client, String remotePath, long offset,
      int fetchSize)
      throws InterruptedException, TException {
    AtomicReference<ByteBuffer> result = new AtomicReference<>();
    GenericHandler<ByteBuffer> handler = new GenericHandler<>(client.getNode(), result);
    synchronized (result) {
      client.readFile(remotePath, offset, fetchSize, handler);
      result.wait(RaftServer.getWriteOperationTimeoutMS());
    }
    return result.get();
  }

  public static List<ByteBuffer> getGroupByResult(AsyncDataClient client, Node header,
      long executorId
      , long curStartTime, long curEndTime) throws InterruptedException, TException {
    AtomicReference<List<ByteBuffer>> fetchResult = new AtomicReference<>();
    GenericHandler<List<ByteBuffer>> handler = new GenericHandler<>(client.getNode(), fetchResult);
    synchronized (fetchResult) {
      fetchResult.set(null);
      client.getGroupByResult(header, executorId, curStartTime, curEndTime, handler);
      fetchResult.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return fetchResult.get();
  }

  public static Map<Integer, Snapshot> pullSnapshot(AsyncDataClient client,
      PullSnapshotRequest request, List<Integer> slots, SnapshotFactory<Snapshot> factory)
      throws TException, InterruptedException {
    AtomicReference<Map<Integer, Snapshot>> snapshotRef = new AtomicReference<>();
    synchronized (snapshotRef) {
      client.pullSnapshot(request, new PullSnapshotHandler<>(snapshotRef,
          client.getNode(), slots, factory));
      snapshotRef.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return snapshotRef.get();
  }

  public static ByteBuffer last(AsyncDataClient client, Path seriesPath,
      TSDataType dataType, QueryContext context, Set<String> deviceMeasurements, Node header)
      throws TException, InterruptedException {
    AtomicReference<ByteBuffer> result = new AtomicReference<>();
    GenericHandler<ByteBuffer> handler = new GenericHandler<>(client.getNode(), result);
    LastQueryRequest request = new LastQueryRequest(seriesPath.getFullPath(), dataType.ordinal(),
        context.getQueryId(), deviceMeasurements, header, client.getNode());
    synchronized (result) {
      client.last(request, handler);
      result.wait(RaftServer.getReadOperationTimeoutMS());
    }
    return result.get();
  }

  public static boolean onSnapshotApplied(AsyncDataClient client, Node header, List<Integer> slots)
      throws TException, InterruptedException {
    AtomicReference<Boolean> result = new AtomicReference<>(false);
    GenericHandler<Boolean> handler = new GenericHandler<>(client.getNode(), result);
    synchronized (result) {
      client.onSnapshotApplied(header, slots, handler);
      result.wait(RaftServer.getWriteOperationTimeoutMS());
    }
    return result.get();
  }
}