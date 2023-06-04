/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.clusterbuilder;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.IntervalProperty;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.SampleCountProperty;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.spi.Spi;

//则用于存储资源的统计信息以及调用者信息，例如该资源的RT、QPS、thread count等等，这些信息将用作为多维度限流，降级的依据
@Spi(isSingleton = false, order = Constants.ORDER_CLUSTER_BUILDER_SLOT)
public class ClusterBuilderSlot extends AbstractLinkedProcessorSlot<DefaultNode> {
	private static volatile Map<ResourceWrapper, ClusterNode> clusterNodeMap = new HashMap<>();

	private static final Object lock = new Object();

	private volatile ClusterNode clusterNode = null;

	@Override
	public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count, boolean prioritized, Object... args) throws Throwable {
		if (clusterNode == null) {
			synchronized (lock) {
				if (clusterNode == null) {
					// Create the cluster node.
					clusterNode = new ClusterNode(resourceWrapper.getName(), resourceWrapper.getResourceType());
					HashMap<ResourceWrapper, ClusterNode> newMap = new HashMap<>(Math.max(clusterNodeMap.size(), 16));
					newMap.putAll(clusterNodeMap);
					newMap.put(node.getId(), clusterNode);

					clusterNodeMap = newMap;
				}
			}
		}
		node.setClusterNode(clusterNode);

		/*
		 * if context origin is set, we should get or create a new {@link Node} of
		 * the specific origin.
		 */
		if (!"".equals(context.getOrigin())) {
			Node originNode = node.getClusterNode().getOrCreateOriginNode(context.getOrigin());
			context.getCurEntry().setOriginNode(originNode);
		}

		fireEntry(context, resourceWrapper, node, count, prioritized, args);
	}

	@Override
	public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
		fireExit(context, resourceWrapper, count, args);
	}

	/**
	 * Get {@link ClusterNode} of the resource of the specific type.
	 *
	 * @param id   resource name.
	 * @param type invoke type.
	 * @return the {@link ClusterNode}
	 */
	public static ClusterNode getClusterNode(String id, EntryType type) {
		return clusterNodeMap.get(new StringResourceWrapper(id, type));
	}

	/**
	 * Get {@link ClusterNode} of the resource name.
	 *
	 * @param id resource name.
	 * @return the {@link ClusterNode}.
	 */
	public static ClusterNode getClusterNode(String id) {
		if (id == null) { return null; }
		ClusterNode clusterNode = null;

		for (EntryType nodeType : EntryType.values()) {
			clusterNode = clusterNodeMap.get(new StringResourceWrapper(id, nodeType));
			if (clusterNode != null) {
				break;
			}
		}

		return clusterNode;
	}

	/**
	 * Get {@link ClusterNode}s map, this map holds all {@link ClusterNode}s, it's key is resource name,
	 * value is the related {@link ClusterNode}. <br/>
	 * DO NOT MODIFY the map returned.
	 *
	 * @return all {@link ClusterNode}s
	 */
	public static Map<ResourceWrapper, ClusterNode> getClusterNodeMap() {
		return clusterNodeMap;
	}

	/**
	 * Reset all {@link ClusterNode}s. Reset is needed when {@link IntervalProperty#INTERVAL} or
	 * {@link SampleCountProperty#SAMPLE_COUNT} is changed.
	 */
	public static void resetClusterNodes() {
		for (ClusterNode node : clusterNodeMap.values()) {
			node.reset();
		}
	}
}
