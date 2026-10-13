package dev.mediacache.alphavideo.gpu.reactnative;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.uimanager.SimpleViewManager;

/**
 * Old architecture: the same methods as the interface codegen generates for the new one, so the
 * manager compiles against either. Props arrive through its {@code @ReactProp} setters.
 */
public abstract class AlphaVideoGpuViewManagerSpec<T extends View> extends SimpleViewManager<T> {

    public abstract void setSource(T view, @Nullable ReadableMap value);
    public abstract void setParts(T view, @Nullable ReadableArray value);
    public abstract void setAutoPlay(T view, boolean value);
    public abstract void setLoop(T view, boolean value);
    public abstract void setPlaybackSpeed(T view, float value);
    public abstract void setPaused(T view, boolean value);
    public abstract void setCacheKey(T view, @Nullable String value);
    public abstract void setStreamingRingSize(T view, int value);
    public abstract void setStreamingCacheSize(T view, int value);
    public abstract void setStreamingPrefetchAhead(T view, int value);
    public abstract void setMemoryStrategy(T view, @Nullable String value);
    public abstract void setStream(T view, boolean value);
    public abstract void setEnableFrameEvents(T view, boolean value);
    public abstract void setVerbose(T view, boolean value);

    public abstract void play(T view);
    public abstract void pause(T view);
    public abstract void stop(T view);
    public abstract void seekToFrame(T view, int frame);
    public abstract void seekToTime(T view, double seconds);

    @Override
    public void receiveCommand(@NonNull T view, String commandName, @Nullable ReadableArray args) {
        switch (commandName) {
            case "play":
                play(view);
                break;
            case "pause":
                pause(view);
                break;
            case "stop":
                stop(view);
                break;
            case "seekToFrame":
                if (args != null && args.size() > 0) seekToFrame(view, args.getInt(0));
                break;
            case "seekToTime":
                if (args != null && args.size() > 0) seekToTime(view, args.getDouble(0));
                break;
        }
    }
}
