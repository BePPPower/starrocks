// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/proc/StatisticProcDir.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.proc;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.LocalTablet.TabletStatus;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Table.TableType;
import com.starrocks.catalog.Tablet;
import com.starrocks.clone.TabletSchedCtx.Priority;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Pair;
import com.starrocks.common.util.ListComparator;
import com.starrocks.system.SystemInfoService;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.thrift.TTaskType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StatisticProcDir implements ProcDirInterface {
    public static final ImmutableList<String> TITLE_NAMES = new ImmutableList.Builder<String>()
            .add("DbId").add("DbName").add("TableNum").add("PartitionNum")
            .add("IndexNum").add("TabletNum").add("ReplicaNum").add("UnhealthyTabletNum")
            .add("InconsistentTabletNum").add("CloningTabletNum")
            .build();
    private static final Logger LOG = LogManager.getLogger(StatisticProcDir.class);

    private Catalog catalog;

    // db id -> set(tablet id)
    Multimap<Long, Long> unhealthyTabletIds;
    // db id -> set(tablet id)
    Multimap<Long, Long> inconsistentTabletIds;
    // db id -> set(tablet id)
    Multimap<Long, Long> cloningTabletIds;

    public StatisticProcDir(Catalog catalog) {
        this.catalog = catalog;
        unhealthyTabletIds = HashMultimap.create();
        inconsistentTabletIds = HashMultimap.create();
        cloningTabletIds = HashMultimap.create();
    }

    @Override
    public ProcResult fetchResult() throws AnalysisException {
        Preconditions.checkNotNull(catalog);

        BaseProcResult result = new BaseProcResult();

        result.setNames(TITLE_NAMES);
        List<Long> dbIds = catalog.getDbIds();
        if (dbIds == null || dbIds.isEmpty()) {
            // empty
            return result;
        }

        SystemInfoService infoService = Catalog.getCurrentSystemInfo();

        int totalDbNum = 0;
        int totalTableNum = 0;
        int totalPartitionNum = 0;
        int totalIndexNum = 0;
        int totalTabletNum = 0;
        int totalReplicaNum = 0;

        unhealthyTabletIds.clear();
        inconsistentTabletIds.clear();
        cloningTabletIds = AgentTaskQueue.getTabletIdsByType(TTaskType.CLONE);
        List<List<Comparable>> lines = new ArrayList<List<Comparable>>();
        for (Long dbId : dbIds) {
            if (dbId == 0) {
                // skip information_schema database
                continue;
            }
            Database db = catalog.getDb(dbId);
            if (db == null) {
                continue;
            }

            ++totalDbNum;
            List<Long> aliveBeIdsInCluster = infoService.getClusterBackendIds(db.getClusterName(), true);
            db.readLock();
            try {
                int dbTableNum = 0;
                int dbPartitionNum = 0;
                int dbIndexNum = 0;
                int dbTabletNum = 0;
                int dbReplicaNum = 0;

                for (Table table : db.getTables()) {
                    if (table.getType() != TableType.OLAP) {
                        continue;
                    }

                    ++dbTableNum;
                    OlapTable olapTable = (OlapTable) table;

                    for (Partition partition : olapTable.getAllPartitions()) {
                        boolean useStarOS = partition.isUseStarOS();
                        short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());
                        ++dbPartitionNum;
                        for (MaterializedIndex materializedIndex : partition
                                .getMaterializedIndices(IndexExtState.VISIBLE)) {
                            ++dbIndexNum;
                            for (Tablet tablet : materializedIndex.getTablets()) {
                                ++dbTabletNum;

                                if (useStarOS) {
                                    continue;
                                }

                                LocalTablet localTablet = (LocalTablet) tablet;
                                dbReplicaNum += localTablet.getReplicas().size();

                                Pair<TabletStatus, Priority> res = localTablet.getHealthStatusWithPriority(
                                        infoService, db.getClusterName(),
                                        partition.getVisibleVersion(),
                                        replicationNum, aliveBeIdsInCluster);

                                // here we treat REDUNDANT as HEALTHY, for user friendly.
                                if (res.first != TabletStatus.HEALTHY && res.first != TabletStatus.REDUNDANT
                                        && res.first != TabletStatus.COLOCATE_REDUNDANT &&
                                        res.first != TabletStatus.NEED_FURTHER_REPAIR) {
                                    unhealthyTabletIds.put(dbId, tablet.getId());
                                }

                                if (!localTablet.isConsistent()) {
                                    inconsistentTabletIds.put(dbId, tablet.getId());
                                }
                            } // end for tablets
                        } // end for indices
                    } // end for partitions
                } // end for tables

                List<Comparable> oneLine = new ArrayList<Comparable>(TITLE_NAMES.size());
                oneLine.add(dbId);
                oneLine.add(db.getFullName());
                oneLine.add(dbTableNum);
                oneLine.add(dbPartitionNum);
                oneLine.add(dbIndexNum);
                oneLine.add(dbTabletNum);
                oneLine.add(dbReplicaNum);
                oneLine.add(unhealthyTabletIds.get(dbId).size());
                oneLine.add(inconsistentTabletIds.get(dbId).size());
                oneLine.add(cloningTabletIds.get(dbId).size());

                lines.add(oneLine);

                totalTableNum += dbTableNum;
                totalPartitionNum += dbPartitionNum;
                totalIndexNum += dbIndexNum;
                totalTabletNum += dbTabletNum;
                totalReplicaNum += dbReplicaNum;
            } finally {
                db.readUnlock();
            }
        } // end for dbs

        // sort by dbName
        ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(1);
        Collections.sort(lines, comparator);

        // add sum line after sort
        List<Comparable> finalLine = new ArrayList<Comparable>(TITLE_NAMES.size());
        finalLine.add("Total");
        finalLine.add(totalDbNum);
        finalLine.add(totalTableNum);
        finalLine.add(totalPartitionNum);
        finalLine.add(totalIndexNum);
        finalLine.add(totalTabletNum);
        finalLine.add(totalReplicaNum);
        finalLine.add(unhealthyTabletIds.size());
        finalLine.add(inconsistentTabletIds.size());
        finalLine.add(cloningTabletIds.size());
        lines.add(finalLine);

        // add result
        for (List<Comparable> line : lines) {
            List<String> row = new ArrayList<String>(line.size());
            for (Comparable comparable : line) {
                row.add(comparable.toString());
            }
            result.addRow(row);
        }

        return result;
    }

    @Override
    public boolean register(String name, ProcNodeInterface node) {
        return false;
    }

    @Override
    public ProcNodeInterface lookup(String dbIdStr) throws AnalysisException {
        long dbId = -1L;
        try {
            dbId = Long.valueOf(dbIdStr);
        } catch (NumberFormatException e) {
            throw new AnalysisException("Invalid db id format: " + dbIdStr);
        }

        if (catalog.getDb(dbId) == null) {
            throw new AnalysisException("Invalid db id: " + dbIdStr);
        }

        return new IncompleteTabletsProcNode(unhealthyTabletIds.get(dbId),
                inconsistentTabletIds.get(dbId),
                cloningTabletIds.get(dbId));
    }
}
