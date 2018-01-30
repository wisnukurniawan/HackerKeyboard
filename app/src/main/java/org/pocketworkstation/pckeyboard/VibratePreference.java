package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

public class VibratePreference extends SeekBarPreferenceString {
    public VibratePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    @Override
    public void onChange(float val) {
        LatinIME ime = LatinIME.sInstance;
        Log.d("wkwkwkwk", "nilai " + val);
        if (ime != null) ime.vibrate((int) val);
    }
}