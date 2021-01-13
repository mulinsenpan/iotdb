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

package org.apache.iotdb.db.qp.physical.sys;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;

/**
 * It's used by cluster to wrap log to plan
 */
public class LogPlan extends PhysicalPlan {

  private ByteBuffer log;

  public LogPlan() {
    super(false);
  }

  public LogPlan(ByteBuffer log) {
    super(false);
    this.log = log;
  }

  public ByteBuffer getLog() {
    return log;
  }

  public void setLog(ByteBuffer log) {
    this.log = log;
  }

  @Override
  public List<PartialPath> getPaths() {
    return Collections.emptyList();
  }

  @Override
  public void serialize(DataOutputStream stream) throws IOException {
    stream.writeByte((byte) PhysicalPlanType.CLUSTER_LOG.ordinal());
    stream.writeInt(log.array().length);
    stream.write(log.array());
  }

  @Override
  public void serialize(ByteBuffer buffer) {
    int len = buffer.getInt();
    log = ByteBuffer.wrap(buffer.array(), buffer.position(), len);
  }
}