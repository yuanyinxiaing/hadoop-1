/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdds.scm.container;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerInfo;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.CREATE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent.CREATED;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE_DEFAULT;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE_GB;

/**
 * Tests the closeContainerEventHandler class.
 */
public class TestCloseContainerEventHandler {

  private static Configuration configuration;
  private static MockNodeManager nodeManager;
  private static ContainerMapping mapping;
  private static long size;
  private static File testDir;
  private static EventQueue eventQueue;

  @BeforeClass
  public static void setUp() throws Exception {
    configuration = SCMTestUtils.getConf();
    size = configuration
        .getLong(OZONE_SCM_CONTAINER_SIZE_GB, OZONE_SCM_CONTAINER_SIZE_DEFAULT)
        * 1024 * 1024 * 1024;
    testDir = GenericTestUtils
        .getTestDir(TestCloseContainerEventHandler.class.getSimpleName());
    configuration
        .set(OzoneConfigKeys.OZONE_METADATA_DIRS, testDir.getAbsolutePath());
    nodeManager = new MockNodeManager(true, 10);
    mapping = new ContainerMapping(configuration, nodeManager, 128);
    eventQueue = new EventQueue();
    eventQueue.addHandler(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT,
        new CloseContainerEventHandler(mapping));
  }

  @AfterClass
  public static void tearDown() throws Exception {
    if (mapping != null) {
      mapping.close();
    }
    FileUtil.fullyDelete(testDir);
  }

  @Test
  public void testIfCloseContainerEventHadnlerInvoked() {
    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer
        .captureLogs(CloseContainerEventHandler.LOG);
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT,
        new ContainerID(Math.abs(new Random().nextLong())));
    eventQueue.processAll(1000);
    Assert.assertTrue(logCapturer.getOutput()
        .contains("Close container Event triggered for container"));
  }

  @Test
  public void testCloseContainerEventWithInvalidContainer() {
    long id = Math.abs(new Random().nextLong());
    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer
        .captureLogs(CloseContainerEventHandler.LOG);
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT,
        new ContainerID(id));
    eventQueue.processAll(1000);
    Assert.assertTrue(logCapturer.getOutput()
        .contains("Container with id : " + id + " does not exist"));
  }

  @Test
  public void testCloseContainerEventWithValidContainers() throws IOException {

    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer
        .captureLogs(CloseContainerEventHandler.LOG);
    ContainerInfo info = mapping
        .allocateContainer(HddsProtos.ReplicationType.STAND_ALONE,
            HddsProtos.ReplicationFactor.ONE, "ozone");
    ContainerID id = new ContainerID(info.getContainerID());
    DatanodeDetails datanode = info.getPipeline().getLeader();
    int closeCount = nodeManager.getCommandCount(datanode);
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT, id);
    eventQueue.processAll(1000);
    // At this point of time, the allocated container is not in open
    // state, so firing close container event should not queue CLOSE
    // command in the Datanode
    Assert.assertEquals(0, nodeManager.getCommandCount(datanode));
    // Make sure the information is logged
    Assert.assertTrue(logCapturer.getOutput().contains(
        "container with id : " + id.getId()
            + " is in ALLOCATED state and need not be closed"));
    //Execute these state transitions so that we can close the container.
    mapping.updateContainerState(id.getId(), CREATE);
    mapping.updateContainerState(id.getId(), CREATED);
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT,
        new ContainerID(info.getContainerID()));
    eventQueue.processAll(1000);
    Assert.assertEquals(closeCount + 1, nodeManager.getCommandCount(datanode));
    Assert.assertEquals(HddsProtos.LifeCycleState.CLOSING,
        mapping.getStateManager().getContainer(id).getState());
  }

  @Test
  public void testCloseContainerEventWithRatis() throws IOException {

    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer
        .captureLogs(CloseContainerEventHandler.LOG);
    ContainerInfo info = mapping
        .allocateContainer(HddsProtos.ReplicationType.RATIS,
            HddsProtos.ReplicationFactor.THREE, "ozone");
    ContainerID id = new ContainerID(info.getContainerID());
    int[] closeCount = new int[3];
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT, id);
    eventQueue.processAll(1000);
    int i = 0;
    for (DatanodeDetails details : info.getPipeline().getMachines()) {
      closeCount[i] = nodeManager.getCommandCount(details);
      i++;
    }
    i = 0;
    for (DatanodeDetails details : info.getPipeline().getMachines()) {
      Assert.assertEquals(closeCount[i], nodeManager.getCommandCount(details));
      i++;
    }
    // Make sure the information is logged
    Assert.assertTrue(logCapturer.getOutput().contains(
        "container with id : " + id.getId()
            + " is in ALLOCATED state and need not be closed"));
    //Execute these state transitions so that we can close the container.
    mapping.updateContainerState(id.getId(), CREATE);
    mapping.updateContainerState(id.getId(), CREATED);
    eventQueue.fireEvent(CloseContainerEventHandler.CLOSE_CONTAINER_EVENT,
        new ContainerID(info.getContainerID()));
    eventQueue.processAll(1000);
    i = 0;
    // Make sure close is queued for each datanode on the pipeline
    for (DatanodeDetails details : info.getPipeline().getMachines()) {
      Assert.assertEquals(closeCount[i] + 1,
          nodeManager.getCommandCount(details));
      Assert.assertEquals(HddsProtos.LifeCycleState.CLOSING,
          mapping.getStateManager().getContainer(id).getState());
      i++;
    }
  }
}
