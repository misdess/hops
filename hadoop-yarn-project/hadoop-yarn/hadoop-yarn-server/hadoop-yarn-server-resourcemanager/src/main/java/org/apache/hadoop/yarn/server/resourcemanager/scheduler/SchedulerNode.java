/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import io.hops.ha.common.TransactionState;
import io.hops.ha.common.TransactionStateImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.util.resource.Resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a YARN Cluster Node from the viewpoint of the scheduler.
 */
@Private
@Unstable
public abstract class SchedulerNode {

  private static final Log LOG = LogFactory.getLog(SchedulerNode.class);

  // Recovered
  protected Resource availableResource = Resource.newInstance(0, 0);
  // Recovered
  protected Resource usedResource = Resource.newInstance(0, 0);
  // Recovered
  protected Resource totalResourceCapability;
  // Recovered
  protected RMContainer reservedContainer;
  // Recovered
  protected volatile int numContainers;

  /* set of containers that are allocated containers */
  // Recovered
  protected final Map<ContainerId, RMContainer> launchedContainers =
          new HashMap<ContainerId, RMContainer>();

  // Recovered
  private final RMNode rmNode;
  // Recovered
  private final String nodeName;

  public SchedulerNode(RMNode node, boolean usePortForNodeName) {
    this.rmNode = node;
    this.availableResource = Resources.clone(node.getTotalCapability());
    this.totalResourceCapability = Resources.clone(node.getTotalCapability());
    if (usePortForNodeName) {
      nodeName = rmNode.getHostName() + ":" + node.getNodeID().getPort();
    } else {
      nodeName = rmNode.getHostName();
    }
  }

  public RMNode getRMNode() {
    return this.rmNode;
  }

  /**
   * Get the ID of the node which contains both its hostname and port.
   *
   * @return the ID of the node
   */
  public NodeId getNodeID() {
    return this.rmNode.getNodeID();
  }

  public String getHttpAddress() {
    return this.rmNode.getHttpAddress();
  }
  /**
   * Get the name of the node for scheduling matching decisions.
   * <p/>
   * Typically this is the 'hostname' reported by the node, but it could be
   * configured to be 'hostname:port' reported by the node via the
   * {@link YarnConfiguration#RM_SCHEDULER_INCLUDE_PORT_IN_NODE_NAME} constant.
   * The main usecase of this is Yarn minicluster to be able to differentiate
   * node manager instances by their port number.
   *
   * @return name of the node for scheduling matching decisions.
   */
  public String getNodeName() {
    return nodeName;
  }
  
  /**
   * Get rackname.
   *
   * @return rackname
   */
  public String getRackName() {
    return this.rmNode.getRackName();
  }
  
  /**
   * The Scheduler has allocated containers on this node to the given
   * application.
   *
   * @param applicationId
   *          application
   * @param rmContainer
   *          allocated container
   */
  public synchronized void allocateContainer(ApplicationId applicationId,
                                             RMContainer rmContainer,
                                             TransactionState transactionState) {
    Container container = rmContainer.getContainer();
    deductAvailableResource(container.getResource());
    ++numContainers;

    launchedContainers.put(container.getId(), rmContainer);

    if (transactionState != null) {
      //HOP :: Update numContainers
      ((TransactionStateImpl) transactionState)
              .getFicaSchedulerNodeInfoToUpdate(this.getNodeID().toString())
              .infoToUpdate(this);
      //HOP :: Update reservedContainer
      ((TransactionStateImpl) transactionState)
              .getFicaSchedulerNodeInfoToUpdate(rmNode.getNodeID().toString())
              .toAddLaunchedContainers(container.getId().toString(),
                      rmContainer.getContainerId().toString());
    }
    LOG.info("Assigned container " + container.getId() + " of capacity "
          + container.getResource() + " on host " + rmNode.getNodeAddress()
          + ", which currently has " + numContainers + " containers, "
          + getUsedResource() + " used and " + getAvailableResource()
          + " available");
  }

  /**
   * Get available resources on the node.
   *
   * @return available resources on the node
   */
  public synchronized Resource getAvailableResource() {
    return this.availableResource;
  }

  /**
   * Get used resources on the node.
   *
   * @return used resources on the node
   */
  public synchronized Resource getUsedResource() {
    return this.usedResource;
  }

  /**
   * Get total resources on the node.
   *
   * @return total resources on the node.
   */
  public Resource getTotalResource() {
    return this.totalResourceCapability;
  }

  private synchronized boolean isValidContainer(Container c) {
    if (launchedContainers.containsKey(c.getId())) {
      return true;
    }
    return false;
  }

  private synchronized void updateResource(Container container,
                                           TransactionState transactionState) {
    addAvailableResource(container.getResource());
    --numContainers;

    //HOP :: Update numContainers
    if (transactionState != null) {
      ((TransactionStateImpl) transactionState)
              .getFicaSchedulerNodeInfoToUpdate(getNodeID().toString())
              .infoToUpdate(this);
    }
  }

  /**
   * Release an allocated container on this node.
   *
   * @param container
   *        container to be released
   */
  public synchronized void releaseContainer(Container container,
                                            TransactionState transactionState) {
    if (!isValidContainer(container)) {
      LOG.error("Invalid container released " + container);
      return;
    }

    /* remove the containers from the nodemanager */
    if (null != launchedContainers.remove(container.getId())) {
      //HOP :: Update reservedContainer
      if (transactionState != null) {
        ((TransactionStateImpl) transactionState)
                .getFicaSchedulerNodeInfoToUpdate(rmNode.getNodeID().toString())
                .toRemoveLaunchedContainers(container.getId().toString());
      }
      updateResource(container, transactionState);
    }

    LOG.info("Released container " + container.getId() + " of capacity "
            + container.getResource() + " on host " + rmNode.getNodeAddress()
            + ", which currently has " + numContainers + " containers, "
            + getUsedResource() + " used and " + getAvailableResource()
            + " available" + ", release resources=" + true);
  }

  private synchronized void addAvailableResource(Resource resource) {
    if (resource == null) {
      LOG.error("Invalid resource addition of null resource for "
          +  rmNode.getNodeAddress());
      return;
    }
    Resources.addTo(availableResource, resource);
    Resources.subtractFrom(usedResource, resource);

    //HOP :: Update resources
  }

  private synchronized void deductAvailableResource(Resource resource) {
    if (resource == null) {
      LOG.error("Invalid deduction of null resource for "
          + rmNode.getNodeAddress());
      return;
    }
    Resources.subtractFrom(availableResource, resource);
    Resources.addTo(usedResource, resource);
    //HOP :: Update resources
  }

  /**
   * Reserve container for the attempt on this node
   */
  public abstract void reserveResource(SchedulerApplicationAttempt attempt,
                                       Priority priority, RMContainer container,
                                       TransactionState transactionState);

  /**
   * Unreserve resources on this node
   */
  public abstract void unreserveResource(SchedulerApplicationAttempt attempt,
                                         TransactionState transactionState);

  @Override
  public String toString() {
    return "host: " + rmNode.getNodeAddress() + " #containers="
            + getNumContainers() + " available="
            + getAvailableResource().getMemory() + " used="
            + getUsedResource().getMemory();
  }

  /**
   * Get number of active containers on the node.
   *
   * @return number of active containers on the node
   */
  public int getNumContainers() {
    return numContainers;
  }

  public synchronized List<RMContainer> getRunningContainers() {
    return new ArrayList<RMContainer>(launchedContainers.values());
  }

  public synchronized Map<ContainerId, RMContainer> getLaunchedContainers() {
    return launchedContainers;
  }

  public synchronized RMContainer getReservedContainer() {
    return reservedContainer;
  }

  protected synchronized void setReservedContainer(
          RMContainer reservedContainer, TransactionState transactionState) {
    this.reservedContainer = reservedContainer;
    if(transactionState!=null){
        ((TransactionStateImpl) transactionState)
        .getFicaSchedulerNodeInfoToUpdate(this.getNodeID().toString())
        .updateReservedContainer(this);
    }
  }

  /**
   * Apply delta resource on node's available resource.
   *
   * @param deltaResource
   *        the delta of resource need to apply to node
   */
  public synchronized void
      applyDeltaOnAvailableResource(Resource deltaResource) {
    // we can only adjust available resource if total resource is changed.
    Resources.addTo(this.availableResource, deltaResource);
  }
}
