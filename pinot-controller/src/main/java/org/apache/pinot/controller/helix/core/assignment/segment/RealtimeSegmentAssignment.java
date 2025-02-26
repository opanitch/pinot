/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix.core.assignment.segment;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.helix.HelixManager;
import org.apache.pinot.common.assignment.InstancePartitions;
import org.apache.pinot.common.tier.Tier;
import org.apache.pinot.common.utils.SegmentUtils;
import org.apache.pinot.spi.config.table.ReplicaGroupStrategyConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.assignment.InstancePartitionsType;
import org.apache.pinot.spi.utils.CommonConstants.Helix.StateModel.SegmentStateModel;
import org.apache.pinot.spi.utils.RebalanceConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Segment assignment for LLC real-time table.
 * <ul>
 *   <li>
 *     For the CONSUMING segments, it is very similar to replica-group based segment assignment with the following
 *     differences:
 *     <ul>
 *       <li>
 *         1. Within a replica-group, all segments of the same stream partition are always assigned to the same exactly
 *         one instance, and because of that we can directly assign or rebalance the CONSUMING segments to the instances
 *         based on the partition id
 *       </li>
 *       <li>
 *         2. Partition id for an instance is derived from the index of the instance (within the replica-group for
 *         replica-group based assignment), instead of explicitly stored in the instance partitions
 *       </li>
 *       <li>
 *         TODO: Support explicit partition configuration in instance partitions
 *       </li>
 *     </ul>
 *   </li>
 *   <li>
 *     For the COMPLETED segments:
 *     <ul>
 *       <li>
 *         If COMPLETED instance partitions are provided, reassign COMPLETED segments the same way as
 *         OfflineSegmentAssignment to relocate COMPLETED segments and offload them from CONSUMING instances to
 *         COMPLETED instances
 *       </li>
 *       <li>
 *         If COMPLETED instance partitions are not provided, reassign COMPLETED segments the same way as CONSUMING
 *         segments with CONSUMING instance partitions to ensure COMPLETED segments are served by the correct instances
 *         when instances for the table has been changed.
 *       </li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * TODO: This class shares a lot of common code with {@link OfflineSegmentAssignment}, extracts a base class from them.
 */
public class RealtimeSegmentAssignment implements SegmentAssignment {
  private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeSegmentAssignment.class);

  private HelixManager _helixManager;
  private String _realtimeTableName;
  private int _replication;
  private String _partitionColumn;

  @Override
  public void init(HelixManager helixManager, TableConfig tableConfig) {
    _helixManager = helixManager;
    _realtimeTableName = tableConfig.getTableName();
    _replication = tableConfig.getValidationConfig().getReplicasPerPartitionNumber();
    ReplicaGroupStrategyConfig replicaGroupStrategyConfig =
        tableConfig.getValidationConfig().getReplicaGroupStrategyConfig();
    _partitionColumn = replicaGroupStrategyConfig != null ? replicaGroupStrategyConfig.getPartitionColumn() : null;

    if (_partitionColumn == null) {
      LOGGER.info("Initialized RealtimeSegmentAssignment with replication: {} without partition column for table: {} ",
          _replication, _realtimeTableName);
    } else {
      LOGGER.info("Initialized RealtimeSegmentAssignment with replication: {} and partition column: {} for table: {}",
          _replication, _partitionColumn, _realtimeTableName);
    }
  }

  @Override
  public List<String> assignSegment(String segmentName, Map<String, Map<String, String>> currentAssignment,
      Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap) {
    Preconditions.checkState(instancePartitionsMap.size() == 1, "One instance partition type should be provided");
    Map.Entry<InstancePartitionsType, InstancePartitions> typeToInstancePartitions =
        instancePartitionsMap.entrySet().iterator().next();
    InstancePartitionsType instancePartitionsType = typeToInstancePartitions.getKey();
    InstancePartitions instancePartitions = typeToInstancePartitions.getValue();
    LOGGER.info("Assigning segment: {} with instance partitions: {} for table: {}", segmentName, instancePartitions,
        _realtimeTableName);
    List<String> instancesAssigned =
        instancePartitionsType == InstancePartitionsType.CONSUMING ? assignConsumingSegment(segmentName,
            instancePartitions) : assignCompletedSegment(segmentName, currentAssignment, instancePartitions);
    LOGGER.info("Assigned segment: {} to instances: {} for table: {}", segmentName, instancesAssigned,
        _realtimeTableName);
    return instancesAssigned;
  }

  /**
   * Helper method to check whether the number of replica-groups matches the table replication for replica-group based
   * instance partitions. Log a warning if they do not match and use the one inside the instance partitions. The
   * mismatch can happen when table is not configured correctly (table replication and numReplicaGroups does not match
   * or replication changed without reassigning instances).
   */
  private void checkReplication(InstancePartitions instancePartitions) {
    int numReplicaGroups = instancePartitions.getNumReplicaGroups();
    if (numReplicaGroups != _replication) {
      LOGGER.warn(
          "Number of replica-groups in instance partitions {}: {} does not match replication in table config: {} for "
              + "table: {}, use: {}", instancePartitions.getInstancePartitionsName(), numReplicaGroups, _replication,
          _realtimeTableName, numReplicaGroups);
    }
  }

  /**
   * Helper method to assign instances for CONSUMING segment based on the segment partition id and instance partitions.
   */
  private List<String> assignConsumingSegment(String segmentName, InstancePartitions instancePartitions) {
    int segmentPartitionId = getSegmentPartitionId(segmentName);
    int numReplicaGroups = instancePartitions.getNumReplicaGroups();
    int numPartitions = instancePartitions.getNumPartitions();

    if (numReplicaGroups == 1 && numPartitions == 1) {
      // Non-replica-group based assignment:
      // Uniformly spray the partitions and replicas across the instances.
      // E.g. (6 instances, 3 partitions, 4 replicas)
      // "0_0": [i0,  i1,  i2,  i3,  i4,  i5  ]
      //         p0r0 p0r1 p0r2 p1r3 p1r0 p1r1
      //         p1r2 p1r3 p2r0 p2r1 p2r2 p2r3

      List<String> instances =
          SegmentAssignmentUtils.getInstancesForNonReplicaGroupBasedAssignment(instancePartitions, _replication);
      int numInstances = instances.size();
      List<String> instancesAssigned = new ArrayList<>(_replication);
      for (int replicaId = 0; replicaId < _replication; replicaId++) {
        int instanceIndex = (segmentPartitionId * _replication + replicaId) % numInstances;
        instancesAssigned.add(instances.get(instanceIndex));
      }
      return instancesAssigned;
    } else {
      // Replica-group based assignment

      checkReplication(instancePartitions);
      List<String> instancesAssigned = new ArrayList<>(numReplicaGroups);

      if (numPartitions == 1) {
        // Implicit partition:
        // Within a replica-group, uniformly spray the partitions across the instances.
        // E.g. (within a replica-group, 3 instances, 6 partitions)
        // "0_0": [i0, i1, i2]
        //         p0  p1  p2
        //         p3  p4  p5

        for (int replicaGroupId = 0; replicaGroupId < numReplicaGroups; replicaGroupId++) {
          List<String> instances = instancePartitions.getInstances(0, replicaGroupId);
          instancesAssigned.add(instances.get(segmentPartitionId % instances.size()));
        }
      } else {
        // Explicit partition:
        // Assign segment to the first instance within the partition.

        for (int replicaGroupId = 0; replicaGroupId < numReplicaGroups; replicaGroupId++) {
          int partitionId = segmentPartitionId % numPartitions;
          instancesAssigned.add(instancePartitions.getInstances(partitionId, replicaGroupId).get(0));
        }
      }

      return instancesAssigned;
    }
  }

  @Override
  public Map<String, Map<String, String>> rebalanceTable(Map<String, Map<String, String>> currentAssignment,
      Map<InstancePartitionsType, InstancePartitions> instancePartitionsMap, @Nullable List<Tier> sortedTiers,
      @Nullable Map<String, InstancePartitions> tierInstancePartitionsMap, Configuration config) {
    InstancePartitions completedInstancePartitions = instancePartitionsMap.get(InstancePartitionsType.COMPLETED);
    InstancePartitions consumingInstancePartitions = instancePartitionsMap.get(InstancePartitionsType.CONSUMING);
    Preconditions.checkState(consumingInstancePartitions != null,
        "Failed to find CONSUMING instance partitions for table: %s", _realtimeTableName);
    boolean includeConsuming = config.getBoolean(RebalanceConfigConstants.INCLUDE_CONSUMING,
        RebalanceConfigConstants.DEFAULT_INCLUDE_CONSUMING);
    boolean bootstrap =
        config.getBoolean(RebalanceConfigConstants.BOOTSTRAP, RebalanceConfigConstants.DEFAULT_BOOTSTRAP);

    // Rebalance tiers first
    Map<String, Map<String, String>> nonTierAssignment = currentAssignment;
    List<Map<String, Map<String, String>>> newTierAssignments = null;
    if (sortedTiers != null) {
      Preconditions.checkState(tierInstancePartitionsMap != null, "Tier to instancePartitions map is null");
      LOGGER.info("Rebalancing tiers: {} for table: {} with bootstrap: {}", tierInstancePartitionsMap.keySet(),
          _realtimeTableName, bootstrap);

      // Get tier to segment assignment map for ONLINE segments i.e. current assignments split by tiers they are
      // eligible for
      SegmentAssignmentUtils.TierSegmentAssignment tierSegmentAssignment =
          new SegmentAssignmentUtils.TierSegmentAssignment(_realtimeTableName, sortedTiers, currentAssignment);
      Map<String, Map<String, Map<String, String>>> tierNameToSegmentAssignmentMap =
          tierSegmentAssignment.getTierNameToSegmentAssignmentMap();

      // For each tier, calculate new assignment using instancePartitions for that tier
      newTierAssignments = new ArrayList<>(tierNameToSegmentAssignmentMap.size());
      for (Map.Entry<String, Map<String, Map<String, String>>> entry : tierNameToSegmentAssignmentMap.entrySet()) {
        String tierName = entry.getKey();
        Map<String, Map<String, String>> tierCurrentAssignment = entry.getValue();

        InstancePartitions tierInstancePartitions = tierInstancePartitionsMap.get(tierName);
        Preconditions.checkNotNull(tierInstancePartitions,
            "Failed to find instance partitions for tier: %s of table: %s", tierName, _realtimeTableName);

        LOGGER.info("Rebalancing tier: {} for table: {} with bootstrap: {}, instance partitions: {}", tierName,
            _realtimeTableName, bootstrap, tierInstancePartitions);
        newTierAssignments.add(reassignSegments(tierName, tierCurrentAssignment, tierInstancePartitions, bootstrap));
      }

      // Rest of the operations should happen only on segments which were not already assigned as part of tiers
      nonTierAssignment = tierSegmentAssignment.getNonTierSegmentAssignment();
    }

    LOGGER.info("Rebalancing table: {} with COMPLETED instance partitions: {}, CONSUMING instance partitions: {}, "
            + "includeConsuming: {}, bootstrap: {}", _realtimeTableName, completedInstancePartitions,
        consumingInstancePartitions, includeConsuming, bootstrap);

    SegmentAssignmentUtils.CompletedConsumingOfflineSegmentAssignment completedConsumingOfflineSegmentAssignment =
        new SegmentAssignmentUtils.CompletedConsumingOfflineSegmentAssignment(nonTierAssignment);
    Map<String, Map<String, String>> newAssignment;

    // Reassign COMPLETED segments first
    Map<String, Map<String, String>> completedSegmentAssignment =
        completedConsumingOfflineSegmentAssignment.getCompletedSegmentAssignment();
    if (completedInstancePartitions != null) {
      // When COMPLETED instance partitions are provided, reassign COMPLETED segments in a balanced way (relocate
      // COMPLETED segments to offload them from CONSUMING instances to COMPLETED instances)
      LOGGER.info("Reassigning COMPLETED segments with COMPLETED instance partitions for table: {}",
          _realtimeTableName);
      newAssignment = reassignSegments(InstancePartitionsType.COMPLETED.toString(), completedSegmentAssignment,
          completedInstancePartitions, bootstrap);
    } else {
      // When COMPLETED instance partitions are not provided, reassign COMPLETED segments the same way as CONSUMING
      // segments with CONSUMING instance partitions (ensure COMPLETED segments are served by the correct instances when
      // instances for the table has been changed)
      LOGGER.info(
          "No COMPLETED instance partitions found, reassigning COMPLETED segments the same way as CONSUMING segments "
              + "with CONSUMING instance partitions for table: {}", _realtimeTableName);

      newAssignment = new TreeMap<>();
      for (String segmentName : completedSegmentAssignment.keySet()) {
        List<String> instancesAssigned = assignConsumingSegment(segmentName, consumingInstancePartitions);
        Map<String, String> instanceStateMap =
            SegmentAssignmentUtils.getInstanceStateMap(instancesAssigned, SegmentStateModel.ONLINE);
        newAssignment.put(segmentName, instanceStateMap);
      }
    }

    // Reassign CONSUMING segments if configured
    Map<String, Map<String, String>> consumingSegmentAssignment =
        completedConsumingOfflineSegmentAssignment.getConsumingSegmentAssignment();
    if (includeConsuming) {
      LOGGER.info("Reassigning CONSUMING segments with CONSUMING instance partitions for table: {}",
          _realtimeTableName);

      for (String segmentName : consumingSegmentAssignment.keySet()) {
        List<String> instancesAssigned = assignConsumingSegment(segmentName, consumingInstancePartitions);
        Map<String, String> instanceStateMap =
            SegmentAssignmentUtils.getInstanceStateMap(instancesAssigned, SegmentStateModel.CONSUMING);
        newAssignment.put(segmentName, instanceStateMap);
      }
    } else {
      newAssignment.putAll(consumingSegmentAssignment);
    }

    // Keep the OFFLINE segments not moved, and RealtimeSegmentValidationManager will periodically detect the OFFLINE
    // segments and re-assign them
    newAssignment.putAll(completedConsumingOfflineSegmentAssignment.getOfflineSegmentAssignment());

    // Add tier assignments, if available
    if (CollectionUtils.isNotEmpty(newTierAssignments)) {
      newTierAssignments.forEach(newAssignment::putAll);
    }

    LOGGER.info("Rebalanced table: {}, number of segments to be moved to each instance: {}", _realtimeTableName,
        SegmentAssignmentUtils.getNumSegmentsToBeMovedPerInstance(currentAssignment, newAssignment));
    return newAssignment;
  }

  /**
   * Rebalances segments in the current assignment using the instancePartitions and returns new assignment
   */
  private Map<String, Map<String, String>> reassignSegments(String instancePartitionType,
      Map<String, Map<String, String>> currentAssignment, InstancePartitions instancePartitions, boolean bootstrap) {
    Map<String, Map<String, String>> newAssignment;
    if (bootstrap) {
      LOGGER.info("Bootstrapping segment assignment for {} segments of table: {}", instancePartitionType,
          _realtimeTableName);

      // When bootstrap is enabled, start with an empty assignment and reassign all segments
      newAssignment = new TreeMap<>();
      for (String segment : currentAssignment.keySet()) {
        List<String> assignedInstances = assignCompletedSegment(segment, newAssignment, instancePartitions);
        newAssignment.put(segment,
            SegmentAssignmentUtils.getInstanceStateMap(assignedInstances, SegmentStateModel.ONLINE));
      }
    } else {
      int numReplicaGroups = instancePartitions.getNumReplicaGroups();
      int numPartitions = instancePartitions.getNumPartitions();

      if (numReplicaGroups == 1 && numPartitions == 1) {
        // Non-replica-group based assignment

        List<String> instances =
            SegmentAssignmentUtils.getInstancesForNonReplicaGroupBasedAssignment(instancePartitions, _replication);
        newAssignment =
            SegmentAssignmentUtils.rebalanceTableWithHelixAutoRebalanceStrategy(currentAssignment, instances,
                _replication);
      } else {
        // Replica-group based assignment

        checkReplication(instancePartitions);

        if (_partitionColumn == null || numPartitions == 1) {
          // NOTE: Shuffle the segments within the current assignment to avoid moving only new segments to the new added
          //       servers, which might cause hotspot servers because queries tend to hit the new segments. Use the
          //       table name hash as the random seed for the shuffle so that the result is deterministic.
          List<String> segments = new ArrayList<>(currentAssignment.keySet());
          Collections.shuffle(segments, new Random(_realtimeTableName.hashCode()));

          newAssignment = new TreeMap<>();
          SegmentAssignmentUtils.rebalanceReplicaGroupBasedPartition(currentAssignment, instancePartitions, 0, segments,
              newAssignment);
        } else {
          newAssignment = rebalanceTableWithPartition(currentAssignment, instancePartitions);
        }
      }
    }
    return newAssignment;
  }

  private Map<String, Map<String, String>> rebalanceTableWithPartition(
      Map<String, Map<String, String>> currentAssignment, InstancePartitions instancePartitions) {
    int numPartitions = instancePartitions.getNumPartitions();
    Map<Integer, List<String>> instancePartitionIdToSegmentsMap = new HashMap<>();
    for (String segmentName : currentAssignment.keySet()) {
      int instancePartitionId = getSegmentPartitionId(segmentName) % numPartitions;
      instancePartitionIdToSegmentsMap.computeIfAbsent(instancePartitionId, k -> new ArrayList<>()).add(segmentName);
    }

    // NOTE: Shuffle the segments within the current assignment to avoid moving only new segments to the new added
    //       servers, which might cause hotspot servers because queries tend to hit the new segments. Use the table
    //       name hash as the random seed for the shuffle so that the result is deterministic.
    Random random = new Random(_realtimeTableName.hashCode());
    for (List<String> segments : instancePartitionIdToSegmentsMap.values()) {
      Collections.shuffle(segments, random);
    }

    return SegmentAssignmentUtils.rebalanceReplicaGroupBasedTable(currentAssignment, instancePartitions,
        instancePartitionIdToSegmentsMap);
  }

  /**
   * Helper method to assign instances for COMPLETED segment based on the current assignment and instance partitions.
   */
  private List<String> assignCompletedSegment(String segmentName, Map<String, Map<String, String>> currentAssignment,
      InstancePartitions instancePartitions) {
    int numReplicaGroups = instancePartitions.getNumReplicaGroups();
    int numPartitions = instancePartitions.getNumPartitions();

    if (numReplicaGroups == 1 && numPartitions == 1) {
      // Non-replica-group based assignment

      return SegmentAssignmentUtils.assignSegmentWithoutReplicaGroup(currentAssignment, instancePartitions,
          _replication);
    } else {
      // Replica-group based assignment

      checkReplication(instancePartitions);

      int partitionId;
      if (_partitionColumn == null || numPartitions == 1) {
        partitionId = 0;
      } else {
        // Uniformly spray the segment partitions over the instance partitions
        partitionId = getSegmentPartitionId(segmentName) % numPartitions;
      }

      return SegmentAssignmentUtils.assignSegmentWithReplicaGroup(currentAssignment, instancePartitions, partitionId);
    }
  }

  private int getSegmentPartitionId(String segmentName) {
    Integer segmentPartitionId =
        SegmentUtils.getRealtimeSegmentPartitionId(segmentName, _realtimeTableName, _helixManager, _partitionColumn);
    if (segmentPartitionId == null) {
      // This case is for the uploaded segments for which there's no partition information.
      // A random, but consistent, partition id is calculated based on the hash code of the segment name.
      // Note that '% 10K' is used to prevent having partition ids with large value which will be problematic later in
      // instance assignment formula.
      segmentPartitionId = Math.abs(segmentName.hashCode() % 10_000);
    }
    return segmentPartitionId;
  }
}
