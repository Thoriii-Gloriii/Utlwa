package com.buddyapps.utlwa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles play/pause/next controls from the ongoing now-playing notification. */
public class PlayerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (action.equals("com.buddyapps.utlwa.PLAYPAUSE")) MainActivity.remotePlayPause();
        else if (action.equals("com.buddyapps.utlwa.NEXT")) MainActivity.remoteNext();
        else if (action.equals("com.buddyapps.utlwa.PREV")) MainActivity.remotePrev();
    }
}
