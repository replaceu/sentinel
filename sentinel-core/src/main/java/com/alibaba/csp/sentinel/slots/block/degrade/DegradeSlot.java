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
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.List;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreaker;
import com.alibaba.csp.sentinel.spi.Spi;

//通过统计信息以及预设的规则，来做熔断降级
@Spi(order = Constants.ORDER_DEGRADE_SLOT)
public class DegradeSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

	@Override
	public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count, boolean prioritized, Object... args) throws Throwable {
        //在触发后续slot前执行熔断的检查
		performChecking(context, resourceWrapper);
		fireEntry(context, resourceWrapper, node, count, prioritized, args);
	}

	void performChecking(Context context, ResourceWrapper r) throws BlockException {
        //根据资源名称查找断路器circuitBreakers
		List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(r.getName());
		if (circuitBreakers == null || circuitBreakers.isEmpty()) { return; }
        //遍历所有的断路器，判断是否让它他国
		for (CircuitBreaker cb : circuitBreakers) {
            //tryPass里面只做了状态检查，熔断是否关闭或者打开
			if (!cb.tryPass(context)) { throw new DegradeException(cb.getRule().getLimitApp(), cb.getRule()); }
		}
	}

	@Override
	public void exit(Context context, ResourceWrapper r, int count, Object... args) {
		Entry curEntry = context.getCurEntry();
        //如果当前其他slot已经有了BlockException直接调用fireExit,不用继续走熔断逻辑了
		if (curEntry.getBlockError() != null) {
			fireExit(context, r, count, args);
			return;
		}
        //根据资源名称查找断路器CircuitBreaker
		List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(r.getName());
		if (circuitBreakers == null || circuitBreakers.isEmpty()) {
			fireExit(context, r, count, args);
			return;
		}

		if (curEntry.getBlockError() == null) {
            //调用CircuitBreaker的onRequestComplete()方法
			for (CircuitBreaker circuitBreaker : circuitBreakers) {
				circuitBreaker.onRequestComplete(context);
			}
		}

		fireExit(context, r, count, args);
	}
}
