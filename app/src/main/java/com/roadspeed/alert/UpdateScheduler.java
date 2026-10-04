package com.roadspeed.alert;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class UpdateScheduler {
    private static final int PERIODIC_ID = 47001, NOW_ID = 47002;
    private UpdateScheduler() {}
    public static void schedulePeriodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true)
                .setPeriodic(24L * 60L * 60L * 1000L).build());
    }
    public static void scheduleNowWhenWifiAvailable(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(NOW_ID, new ComponentName(c, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setMinimumLatency(1000)
                .setOverrideDeadline(6L * 60L * 60L * 1000L).build());
    }
}
