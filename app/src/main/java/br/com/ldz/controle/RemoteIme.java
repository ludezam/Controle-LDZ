package br.com.ldz.controle;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public final class RemoteIme extends InputMethodService {
    private static volatile RemoteIme active;
    @Override public void onCreate() { super.onCreate(); active = this; }
    @Override public void onDestroy() { if (active == this) active = null; super.onDestroy(); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public View onCreateInputView() {
        LinearLayout box = new LinearLayout(this); box.setPadding(32,16,32,16); box.setGravity(17); box.setBackgroundColor(0xffeceef3);
        TextView message = new TextView(this); message.setText("Digite pelo celular · Controle LDZ"); message.setTextSize(20); box.addView(message);
        Button change = new Button(this); change.setText("Trocar teclado"); change.setOnClickListener(v -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker()); box.addView(change);
        return box;
    }
    public static boolean send(String text, boolean submit) {
        RemoteIme ime = active; if (ime == null) return false;
        CountDownLatch done = new CountDownLatch(1); boolean[] accepted = {false};
        new Handler(Looper.getMainLooper()).post(() -> {
            InputConnection connection = ime.getCurrentInputConnection();
            if (connection != null) {
                accepted[0] = submit ? connection.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) : connection.commitText(text, 1);
            }
            done.countDown();
        });
        try { done.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return accepted[0];
    }
}
