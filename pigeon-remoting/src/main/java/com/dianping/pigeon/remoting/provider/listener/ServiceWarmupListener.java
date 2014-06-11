/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.provider.listener;

import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.util.Constants;
import com.dianping.pigeon.remoting.provider.config.ProviderConfig;
import com.dianping.pigeon.remoting.provider.service.PublishStatus;
import com.dianping.pigeon.remoting.provider.service.ServiceProviderFactory;

public class ServiceWarmupListener implements Runnable {

	private static final Logger logger = LoggerLoader.getLogger(ServiceWarmupListener.class);

	private static ConfigManager configManager = ExtensionLoader.getExtension(ConfigManager.class);

	private static final int CHECK_INTERVAL = configManager.getIntValue(Constants.KEY_WEIGHT_WARMUPPERIOD,
			Constants.DEFAULT_WEIGHT_WAMUPPERIOD);

	private static final int START_DELAY = configManager.getIntValue(Constants.KEY_WEIGHT_STARTDELAY, CHECK_INTERVAL);

	private static volatile boolean isServiceWarmupListenerStarted = false;

	private static ServiceWarmupListener currentWarmupListener = null;

	private volatile boolean isStop = false;

	public static void start() {
		boolean warmupEnable = ConfigManagerLoader.getConfigManager().getBooleanValue(
				Constants.KEY_SERVICEWARMUP_ENABLE, true);
		if (!isServiceWarmupListenerStarted && warmupEnable) {
			currentWarmupListener = new ServiceWarmupListener();
			Thread t = new Thread(currentWarmupListener);
			t.setDaemon(true);
			t.setName("Pigeon-Service-Warmup-Listener");
			t.start();
			isServiceWarmupListenerStarted = true;
		}
	}

	public static void stop() {
		if (currentWarmupListener != null) {
			currentWarmupListener.setStop(true);
			while (isServiceWarmupListenerStarted) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			}
			currentWarmupListener = null;
		}
	}

	public boolean isStop() {
		return isStop;
	}

	public void setStop(boolean isStop) {
		this.isStop = isStop;
	}

	public void run() {
		try {
			Thread.sleep(START_DELAY);
			if (!isStop) {
				ServiceProviderFactory.setServerWeight(Constants.WEIGHT_START);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		int publishedCount = 0;
		int unpublishedCount = 0;
		while (!isStop
				&& (ServiceProviderFactory.getPublishStatus().equals(PublishStatus.TOPUBLISH)
						|| ServiceProviderFactory.getPublishStatus().equals(PublishStatus.PUBLISHING) || ServiceProviderFactory
						.getPublishStatus().equals(PublishStatus.PUBLISHED))) {
			Map<String, ProviderConfig<?>> services = ServiceProviderFactory.getAllServiceProviders();
			for (Entry<String, ProviderConfig<?>> entry : services.entrySet()) {
				ProviderConfig<?> providerConfig = entry.getValue();
				if (providerConfig.isPublished()) {
					publishedCount++;
				} else {
					unpublishedCount++;
				}
			}
			if (publishedCount > 0 && unpublishedCount == 0) {
				ServiceProviderFactory.setPublishStatus(PublishStatus.PUBLISHED);
				break;
			} else {
				try {
					Thread.sleep(CHECK_INTERVAL);
				} catch (InterruptedException e) {
				}
			}
		}
		int weight = Constants.WEIGHT_START;
		while (!isStop && weight < Constants.WEIGHT_DEFAULT) {
			ServiceProviderFactory.setPublishStatus(PublishStatus.WARMINGUP);
			try {
				Thread.sleep(CHECK_INTERVAL);
				if (!ServiceProviderFactory.getPublishStatus().equals(PublishStatus.WARMINGUP)
						&& !ServiceProviderFactory.getPublishStatus().equals(PublishStatus.PUBLISHED)) {
					logger.warn("Warm-up task will be end, current status:" + ServiceProviderFactory.getPublishStatus());
					break;
				}
				ServiceProviderFactory.setServerWeight(++weight);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		if (weight == Constants.WEIGHT_DEFAULT) {
			ServiceProviderFactory.setPublishStatus(PublishStatus.WARMEDUP);
		}
		logger.info("Warm-up task end, current weight:" + ServiceProviderFactory.getServerWeight());
		isServiceWarmupListenerStarted = false;
	}
}