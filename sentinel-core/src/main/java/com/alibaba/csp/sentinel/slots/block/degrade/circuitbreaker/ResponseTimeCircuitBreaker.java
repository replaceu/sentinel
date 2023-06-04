/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * @author Eric Zhao
 * @since 1.8.0
 */
public class ResponseTimeCircuitBreaker extends AbstractCircuitBreaker {

	private static final double SLOW_REQUEST_RATIO_MAX_VALUE = 1.0d;

	private final long		maxAllowedRt;
	private final double	maxSlowRequestRatio;
	private final int		minRequestAmount;

	private final LeapArray<SlowRequestCounter> slidingCounter;

	public ResponseTimeCircuitBreaker(DegradeRule rule) {
		this(rule, new SlowRequestLeapArray(1, rule.getStatIntervalMs()));
	}

	ResponseTimeCircuitBreaker(DegradeRule rule, LeapArray<SlowRequestCounter> stat) {
		super(rule);
		AssertUtil.isTrue(rule.getGrade() == RuleConstant.DEGRADE_GRADE_RT, "rule metric type should be RT");
		AssertUtil.notNull(stat, "stat cannot be null");
		this.maxAllowedRt = Math.round(rule.getCount());
		this.maxSlowRequestRatio = rule.getSlowRatioThreshold();
		this.minRequestAmount = rule.getMinRequestAmount();
		this.slidingCounter = stat;
	}

	@Override
	public void resetStat() {
		// Reset current bucket (bucket count = 1).
		slidingCounter.currentWindow().value().reset();
	}

	@Override
	public void onRequestComplete(Context context) {
		//取得当前滑动窗口
		SlowRequestCounter counter = slidingCounter.currentWindow().value();
		Entry entry = context.getCurEntry();
		if (entry == null) { return; }
		//请求完成时间
		long completeTime = entry.getCompleteTimestamp();
		if (completeTime <= 0) {
			completeTime = TimeUtil.currentTimeMillis();
		}
		//请求响应时间
		long rt = completeTime - entry.getCreateTimestamp();
		//请求响应时间大于最大响应时间，慢调用数加1
		if (rt > maxAllowedRt) {
			counter.slowCount.add(1);
		}
		//总的请求加1
		counter.totalCount.add(1);

		handleStateChangeWhenThresholdExceeded(rt);
	}

	private void handleStateChangeWhenThresholdExceeded(long rt) {
		//open直接返回，已经有其他请求触发熔断降级了
		if (currentState.get() == State.OPEN) { return; }

		if (currentState.get() == State.HALF_OPEN) {
			//half_open放了一个请求进来
			if (rt > maxAllowedRt) {
				//half-open->open
				fromHalfOpenToOpen(1.0d);
			} else {
				//half-open->close
				fromHalfOpenToClose();
			}
			//如果是close就直接返回
			return;
		}

		List<SlowRequestCounter> counters = slidingCounter.values();
		long slowCount = 0;//慢请求数
		long totalCount = 0;//总请求数
		for (SlowRequestCounter counter : counters) {
			slowCount += counter.slowCount.sum();
			totalCount += counter.totalCount.sum();
		}
		//总请求数小于最小请求数，直接返回，不熔断
		if (totalCount < minRequestAmount) { return; }
		double currentRatio = slowCount * 1.0d / totalCount;
		//当前慢请求比例 > 最大慢请求比例
		if (currentRatio > maxSlowRequestRatio) {
			transformToOpen(currentRatio);
		}
		if (Double.compare(currentRatio, maxSlowRequestRatio) == 0 && Double.compare(maxSlowRequestRatio, SLOW_REQUEST_RATIO_MAX_VALUE) == 0) {
			//当前慢请求比例 = 最大慢请求比例 = 1.0
			transformToOpen(currentRatio);
		}
	}

	static class SlowRequestCounter {
		private LongAdder	slowCount;
		private LongAdder	totalCount;

		public SlowRequestCounter() {
			this.slowCount = new LongAdder();
			this.totalCount = new LongAdder();
		}

		public LongAdder getSlowCount() {
			return slowCount;
		}

		public LongAdder getTotalCount() {
			return totalCount;
		}

		public SlowRequestCounter reset() {
			slowCount.reset();
			totalCount.reset();
			return this;
		}

		@Override
		public String toString() {
			return "SlowRequestCounter{" + "slowCount=" + slowCount + ", totalCount=" + totalCount + '}';
		}
	}

	static class SlowRequestLeapArray extends LeapArray<SlowRequestCounter> {

		public SlowRequestLeapArray(int sampleCount, int intervalInMs) {
			super(sampleCount, intervalInMs);
		}

		@Override
		public SlowRequestCounter newEmptyBucket(long timeMillis) {
			return new SlowRequestCounter();
		}

		@Override
		protected WindowWrap<SlowRequestCounter> resetWindowTo(WindowWrap<SlowRequestCounter> w, long startTime) {
			w.resetTo(startTime);
			w.value().reset();
			return w;
		}
	}
}
