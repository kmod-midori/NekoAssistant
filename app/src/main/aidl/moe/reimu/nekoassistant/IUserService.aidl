package moe.reimu.nekoassistant;

import moe.reimu.nekoassistant.IPreviewFrameListener;


interface IUserService {
    void destroy() = 16777114;
    boolean startDisplay(int width, int height, int dpi) = 1;

    android.graphics.Bitmap getLastBitmap() = 2;
    void startActivity(in android.content.Intent intent, boolean forceStop) = 3;
    String getFocusedPackageName() = 4;

    boolean injectInputEvent(in android.view.InputEvent input) = 5;
    boolean injectSwipeEvent(float x1, float y1, float x2, float y2, long duration) = 6;
    boolean injectTapEvent(float x, float y) = 7;
    boolean injectKeyPressEvent(int keyCode) = 8;

    boolean stopDisplay() = 9;
    void registerPreviewFrameListener(in IPreviewFrameListener listener) = 10;
    void unregisterPreviewFrameListener(in IPreviewFrameListener listener) = 11;
}
