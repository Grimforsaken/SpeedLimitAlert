package com.roadspeed.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        UpdateScheduler.schedulePeriodic(context);
        if (Prefs.get(context).getBoolean(Prefs.KEY_AUTO_UPDATE, true)) UpdateScheduler.scheduleNowWhenWifiAvailable(context);
    }
}
