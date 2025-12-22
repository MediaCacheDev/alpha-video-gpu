package com.ktx2player;

import android.content.Context;

import com.facebook.react.bridge.ReadableMap;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for managing KTX2Player instances by ID.
 */
public class KTX2PlayerRegistry {
    private static KTX2PlayerRegistry sInstance;
    private final Map<String, KTX2Player> mPlayers = new HashMap<>();

    private KTX2PlayerRegistry() {}

    public static synchronized KTX2PlayerRegistry getInstance() {
        if (sInstance == null) {
            sInstance = new KTX2PlayerRegistry();
        }
        return sInstance;
    }

    public void createPlayer(String playerId, Context context, ReadableMap options) {
        KTX2Player player = new KTX2Player(context);
        
        if (options != null) {
            if (options.hasKey("autoPlay")) {
                player.setAutoPlay(options.getBoolean("autoPlay"));
            }
            if (options.hasKey("loop")) {
                player.setLoop(options.getBoolean("loop"));
            }
            if (options.hasKey("playbackSpeed")) {
                player.setPlaybackSpeed((float) options.getDouble("playbackSpeed"));
            }
        }
        
        mPlayers.put(playerId, player);
    }

    public KTX2Player getPlayer(String playerId) {
        return mPlayers.get(playerId);
    }

    public void destroyPlayer(String playerId) {
        KTX2Player player = mPlayers.remove(playerId);
        if (player != null) {
            player.destroy();
        }
    }

    public void destroyAll() {
        for (KTX2Player player : mPlayers.values()) {
            player.destroy();
        }
        mPlayers.clear();
    }
}
