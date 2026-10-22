package dev.mediacache.alphavideo.gpu.reactnative;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;

import dev.mediacache.alphavideo.gpu.AlphaVideoGpuView;

import java.util.Map;

/** Turns a player view's callbacks into React events on that view. */
final class PlayerEventForwarder implements AlphaVideoGpuView.PlayerCallback {

    static Map<String, Object> exportedEvents() {
        return MapBuilder.<String, Object>builder()
            .put("onLoad", MapBuilder.of("registrationName", "onLoad"))
            .put("onFrameUpdate", MapBuilder.of("registrationName", "onFrameUpdate"))
            .put("onStateChange", MapBuilder.of("registrationName", "onStateChange"))
            .put("onFinished", MapBuilder.of("registrationName", "onFinished"))
            .put("onError", MapBuilder.of("registrationName", "onError"))
            .build();
    }

    private final View view;

    PlayerEventForwarder(View view) {
        this.view = view;
    }

    @Override
    public void onLoad(int width, int height, int frameCount, double frameRate) {
        WritableMap event = Arguments.createMap();
        event.putInt("width", width);
        event.putInt("height", height);
        event.putInt("frameCount", frameCount);
        event.putDouble("frameRate", frameRate);
        event.putDouble("duration", frameCount > 0 && frameRate > 0 ? frameCount / frameRate : 0);
        send("onLoad", event);
    }

    @Override
    public void onFrameUpdate(int frame, int totalFrames) {
        WritableMap event = Arguments.createMap();
        event.putInt("frame", frame);
        event.putInt("totalFrames", totalFrames);
        send("onFrameUpdate", event);
    }

    @Override
    public void onStateChange(String state) {
        WritableMap event = Arguments.createMap();
        event.putString("state", state);
        send("onStateChange", event);
    }

    @Override
    public void onFinished() {
        send("onFinished", Arguments.createMap());
    }

    @Override
    public void onError(String error, @Nullable String code) {
        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        if (code != null) event.putString("code", code);
        send("onError", event);
    }

    private void send(String eventName, WritableMap params) {
        ReactContext context = (ReactContext) view.getContext();
        EventDispatcher dispatcher = UIManagerHelper.getEventDispatcherForReactTag(context, view.getId());
        if (dispatcher == null) return;
        dispatcher.dispatchEvent(new PlayerEvent(UIManagerHelper.getSurfaceId(view), view.getId(), eventName, params));
    }

    private static final class PlayerEvent extends Event<PlayerEvent> {
        private final String eventName;
        private final WritableMap eventData;

        PlayerEvent(int surfaceId, int viewTag, String eventName, WritableMap eventData) {
            super(surfaceId, viewTag);
            this.eventName = eventName;
            this.eventData = eventData;
        }

        @NonNull
        @Override
        public String getEventName() {
            return eventName;
        }

        @Nullable
        @Override
        protected WritableMap getEventData() {
            return eventData;
        }
    }
}
