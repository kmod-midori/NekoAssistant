package moe.reimu.nekoassistant;


interface IUserService {
    void destroy() = 16777114;
    boolean startDisplay(int width, int height, int dpi, in android.view.Surface surface) = 1;

    void startActivity(in android.content.Intent intent, boolean forceStop) = 3;
    String getFocusedPackageName() = 4;

    boolean injectInputEvent(in android.view.InputEvent input) = 5;
    boolean injectSwipeEvent(float x1, float y1, float x2, float y2, long duration) = 6;
    boolean injectTapEvent(float x, float y) = 7;
    boolean injectKeyPressEvent(int keyCode) = 8;

    boolean stopDisplay() = 9;
}
