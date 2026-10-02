package com.fahim.myide;

import android.app.Activity;
import android.content.res.Configuration;

public class AppCompatDelegateHelper {

    public static final int MODE_LIGHT = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_SYSTEM = 2;

    public static void setMode(Activity activity, int mode) {
        Configuration config =
            new Configuration(activity.getResources().getConfiguration());

        if (mode == MODE_LIGHT) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                          | Configuration.UI_MODE_NIGHT_NO;
        } else if (mode == MODE_DARK) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                          | Configuration.UI_MODE_NIGHT_YES;
        }

        activity.getResources().updateConfiguration(config,
            activity.getResources().getDisplayMetrics());
    }
}