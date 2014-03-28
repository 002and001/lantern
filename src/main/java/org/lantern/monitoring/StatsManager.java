package org.lantern.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.lantern.Country;
import org.lantern.LanternConstants;
import org.lantern.LanternService;
import org.lantern.LanternUtils;
import org.lantern.event.Events;
import org.lantern.monitoring.Stats.Gauges;
import org.lantern.state.Mode;
import org.lantern.state.Model;
import org.lantern.state.SyncPath;
import org.lantern.util.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class StatsManager implements LanternService {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(StatsManager.class);
    // Get stats every minute
    private static final long GET_INTERVAL = 60;
    // Post stats every 5 minutes
    private static final long POST_INTERVAL = 5;

    private final Model model;
    private final StatshubAPI statshub = new StatshubAPI(
            LanternUtils.isFallbackProxy() ? null :
                    LanternConstants.LANTERN_LOCALHOST_ADDR);

    private final MemoryMXBean memoryMXBean = ManagementFactory
            .getMemoryMXBean();
    private final OperatingSystemMXBean osStats = ManagementFactory
            .getOperatingSystemMXBean();

    private final ScheduledExecutorService getScheduler = Threads
            .newSingleThreadScheduledExecutor("StatsManager-Get");
    private final ScheduledExecutorService postScheduler = Threads
            .newSingleThreadScheduledExecutor("StatsManager-Post");

    @Inject
    public StatsManager(Model model) {
        this.model = model;
    }

    @Override
    public void start() {
        getScheduler.scheduleAtFixedRate(
                getStats,
                30,
                GET_INTERVAL,
                TimeUnit.SECONDS);
        postScheduler.scheduleAtFixedRate(
                postStats,
                1, // wait 1 minute before first posting stats, to give the
                   // system a chance to initialize metadata
                POST_INTERVAL,
                TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        getScheduler.shutdownNow();
        postScheduler.shutdownNow();
        try {
            getScheduler.awaitTermination(30, TimeUnit.SECONDS);
            postScheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            LOGGER.warn("Unable to await termination of schedulers", ie);
        }
    }

    private final Runnable getStats = new Runnable() {
        public void run() {
            try {
                StatsResponse resp = statshub.getStats("country");
                if (resp != null) {
                    Map<String, Stats> countryDim = resp.getDims().get(
                            "country");
                    if (countryDim != null) {
//                        model.setGlobalStats(countryDim.get("total"));
//                        for (Country country : model.getCountries().values()) {
//                            country.setStats(countryDim.get(
//                                    country.getCode().toLowerCase()));
//                        }
//                        Events.sync(SyncPath.GLOBAL_STATS,
//                                model.getGlobalStats());
//                        Events.sync(SyncPath.COUNTRIES, model.getCountries());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Unable to getStats: " + e.getMessage(), e);
            }
        }
    };

    private final Runnable postStats = new Runnable() {
        public void run() {
            // Only report stats if user enabled auto-reporting
//            if (model.getSettings().isAutoReport()) {
//                try {
//                    String userGuid = model.getUserGuid();
//                    String countryCode = model.getLocation().getCountry();
//                    if (StringUtils.isBlank(countryCode)
//                            || "--".equals(countryCode)) {
//                        countryCode = "xx";
//                    }
//
//                    String instanceId = model.getInstanceId();
//                    Stats instanceStats =
//                            model.getInstanceStats().toInstanceStats();
//                    addSystemStats(instanceStats);
//                    statshub.postInstanceStats(
//                            instanceId,
//                            userGuid,
//                            countryCode,
//                            LanternUtils.isFallbackProxy(),
//                            instanceStats);
//
//                    if (userGuid != null) {
//                        Stats userStats = model.getInstanceStats()
//                                .toUserStats(
//                                        userGuid,
//                                        Mode.give == model.getSettings()
//                                                .getMode(),
//                                        Mode.get == model.getSettings()
//                                                .getMode());
//                        statshub.postUserStats(userGuid, countryCode, userStats);
//                    }
//                } catch (Exception e) {
//                    LOGGER.warn("Unable to postStats: " + e.getMessage(), e);
//                }
//            }
        }
    };

    private void addSystemStats(Stats stats) {
        stats.setGauge(Gauges.processCPUUsage,
                scalePercent(getSystemStat("getProcessCpuLoad")));
        stats.setGauge(Gauges.systemCPUUsage,
                scalePercent(getSystemStat("getSystemCpuLoad")));
        stats.setGauge(Gauges.systemLoadAverage,
                scalePercent(osStats.getSystemLoadAverage()));
        stats.setGauge(Gauges.memoryUsage, memoryMXBean
                .getHeapMemoryUsage()
                .getCommitted() +
                memoryMXBean.getNonHeapMemoryUsage()
                        .getCommitted());
        stats.setGauge(Gauges.openFileDescriptors,
                getOpenFileDescriptors());
    }

    private long getOpenFileDescriptors() {
        if (!isOnUnix()) {
            return 0L;
        }
        return (Long) getSystemStat("getOpenFileDescriptorCount");
    }

    private Long scalePercent(Number value) {
        if (value == null)
            return null;
        return (long) (((Double) value) * 100.0);
    }

    private <T extends Number> T getSystemStat(final String name) {
        if (!isOnUnix()) {
            return (T) (Double) 0.0;
        } else {
            try {
                final Method method = osStats.getClass()
                        .getDeclaredMethod(name);
                method.setAccessible(true);
                return (T) method.invoke(osStats);
            } catch (final Exception e) {
                LOGGER.debug("Unable to get system stat: {}", name, e);
                return (T) (Double) 0.0;
            }
        }
    }

    private boolean isOnUnix() {
        return osStats.getClass().getName()
                .equals("com.sun.management.UnixOperatingSystem");
    }
}
