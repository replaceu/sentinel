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
package com.alibaba.csp.sentinel.slots.system;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.concurrent.TimeUnit;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.StringUtil;

import com.sun.management.OperatingSystemMXBean;

/**
 * @author jialiang.linjl
 */
public class SystemStatusListener implements Runnable {

	volatile double	currentLoad		= -1;
	volatile double	currentCpuUsage	= -1;

	volatile String reason = StringUtil.EMPTY;

	volatile long	processCpuTime	= 0;
	volatile long	processUpTime	= 0;

	public double getSystemAverageLoad() {
		return currentLoad;
	}

	public double getCpuUsage() {
		return currentCpuUsage;
	}

	@Override
	public void run() {
		try {
			//获取操作系统的信息
			OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
			//系统的平均负载
			currentLoad = osBean.getSystemLoadAverage();

			double systemCpuUsage = osBean.getSystemCpuLoad();

			RuntimeMXBean runtimeBean = ManagementFactory.getPlatformMXBean(RuntimeMXBean.class);
			long newProcessCpuTime = osBean.getProcessCpuTime();
			long newProcessUpTime = runtimeBean.getUptime();
			int cpuCores = osBean.getAvailableProcessors();
			long processCpuTimeDiffInMs = TimeUnit.NANOSECONDS.toMillis(newProcessCpuTime - processCpuTime);
			long processUpTimeDiffInMs = newProcessUpTime - processUpTime;
			double processCpuUsage = (double) processCpuTimeDiffInMs / processUpTimeDiffInMs / cpuCores;
			processCpuTime = newProcessCpuTime;
			processUpTime = newProcessUpTime;
			//当前CPU的负载
			currentCpuUsage = Math.max(processCpuUsage, systemCpuUsage);

			if (currentLoad > SystemRuleManager.getSystemLoadThreshold()) {
				writeSystemStatusLog();
			}
		} catch (Throwable e) {
			RecordLog.warn("[SystemStatusListener] Failed to get system metrics from JMX", e);
		}
	}

	private void writeSystemStatusLog() {
		StringBuilder sb = new StringBuilder();
		sb.append("Load exceeds the threshold: ");
		sb.append("load:").append(String.format("%.4f", currentLoad)).append("; ");
		sb.append("cpuUsage:").append(String.format("%.4f", currentCpuUsage)).append("; ");
		sb.append("qps:").append(String.format("%.4f", Constants.ENTRY_NODE.passQps())).append("; ");
		sb.append("rt:").append(String.format("%.4f", Constants.ENTRY_NODE.avgRt())).append("; ");
		sb.append("thread:").append(Constants.ENTRY_NODE.curThreadNum()).append("; ");
		sb.append("success:").append(String.format("%.4f", Constants.ENTRY_NODE.successQps())).append("; ");
		sb.append("minRt:").append(String.format("%.2f", Constants.ENTRY_NODE.minRt())).append("; ");
		sb.append("maxSuccess:").append(String.format("%.2f", Constants.ENTRY_NODE.maxSuccessQps())).append("; ");
		RecordLog.info(sb.toString());
	}
}
