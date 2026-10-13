package dev.mediacache.alphavideo.gpu.reactnative;

import android.view.View;

import androidx.annotation.Nullable;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ViewManagerDelegate;
import com.facebook.react.viewmanagers.AlphaVideoGpuViewManagerDelegate;
import com.facebook.react.viewmanagers.AlphaVideoGpuViewManagerInterface;

/** New architecture: props and commands arrive through the delegate generated from the codegen spec. */
public abstract class AlphaVideoGpuViewManagerSpec<T extends View> extends SimpleViewManager<T>
        implements AlphaVideoGpuViewManagerInterface<T> {

    private final ViewManagerDelegate<T> delegate = new AlphaVideoGpuViewManagerDelegate<>(this);

    @Nullable
    @Override
    protected ViewManagerDelegate<T> getDelegate() {
        return delegate;
    }
}
