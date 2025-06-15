package com.example.usbhidemulator;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataOutputStream;
import java.io.IOException;

import android.text.Editable;
import android.text.TextWatcher;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "USBHIDEmulator";

    private Process suProcess;
    private DataOutputStream suOutput;

    private EditText keyboardInput;

    private String previousText = "";
    private boolean ignoreTextChange = false;

    private View touchpad;
    private Button btnLeftMouse, btnMiddleMouse, btnRightMouse;

    private float lastX = -1;
    private float lastY = -1;

    private static final byte MOUSE_LEFT = 0x01;
    private static final byte MOUSE_RIGHT = 0x02;
    private static final byte MOUSE_MIDDLE = 0x04;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request root early and block UI thread briefly until ready
        requestRootBlocking();

        Button directKeyboardButton = findViewById(R.id.button_direct_keyboard);
        keyboardInput = findViewById(R.id.edittext_keyboard_input);

        keyboardInput.setVisibility(View.GONE);

        directKeyboardButton.setOnClickListener(v -> {
            ignoreTextChange = true;
            keyboardInput.setText("");
            previousText = "";
            ignoreTextChange = false;

            keyboardInput.setVisibility(View.VISIBLE);
            keyboardInput.requestFocus();

            keyboardInput.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        });

        // Handle Enter key in IME
        keyboardInput.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {

                sendCharAsHid('\n');
                return true;
            }
            return false;
        });

        // Deprecated for most IMEs, but keep for physical keyboards / certain IMEs
        keyboardInput.setOnKeyListener((View v, int keyCode, KeyEvent event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Integer hidCode = androidKeycodeToHid(keyCode);
                if (hidCode != null) {
                    byte modifier = 0x00;

                    if (event.isShiftPressed()) modifier |= 0x02;
                    if (event.isCtrlPressed()) modifier |= 0x01;
                    if (event.isAltPressed()) modifier |= 0x04;
                    if (event.isMetaPressed()) modifier |= 0x08;

                    sendHidKey(modifier, hidCode);
                    return true;
                }
            }
            return false;
        });

        // Handle text changes to support different IMEs and input methods
        keyboardInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!ignoreTextChange) {
                    previousText = s.toString();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreTextChange) return;

                // Handle deletion/backspace
                if (before > 0 && count == 0) {
                    for (int i = 0; i < before; i++) {
                        sendCharAsHid((char) 0x08); // backspace
                    }
                }

                // Handle inserted characters
                if (count > 0) {
                    CharSequence added = s.subSequence(start, start + count);
                    for (int i = 0; i < added.length(); i++) {
                        char c = added.charAt(i);
                        sendCharAsHid(c);
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                // no op
            }
        });

        touchpad = findViewById(R.id.touchpad);
        btnLeftMouse = findViewById(R.id.btn_left_mouse);
        btnMiddleMouse = findViewById(R.id.btn_middle_mouse);
        btnRightMouse = findViewById(R.id.btn_right_mouse);

        touchpad.setOnTouchListener((View v, MotionEvent event) -> {
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    sendMouseClickPress(MOUSE_LEFT);
                    lastX = event.getX();
                    lastY = event.getY();
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (lastX >= 0 && lastY >= 0) {
                        int deltaX = (int) (event.getX() - lastX);
                        int deltaY = (int) (event.getY() - lastY);

                        if (deltaX != 0 || deltaY != 0) {
                            sendMouseMove(deltaX, deltaY);
                            lastX = event.getX();
                            lastY = event.getY();
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendMouseClickRelease();
                    lastX = -1;
                    lastY = -1;
                    break;
            }
            return true;
        });

        btnLeftMouse.setOnClickListener(v -> sendMouseClick(MOUSE_LEFT));
        btnMiddleMouse.setOnClickListener(v -> sendMouseClick(MOUSE_MIDDLE));
        btnRightMouse.setOnClickListener(v -> sendMouseClick(MOUSE_RIGHT));

        // Fix button disappearing on press by disabling default state changes
        btnLeftMouse.setStateListAnimator(null);
        btnMiddleMouse.setStateListAnimator(null);
        btnRightMouse.setStateListAnimator(null);
    }

    private void sendMouseMove(int deltaX, int deltaY) {
        byte buttons = 0x00;
        byte x = (byte) deltaX;
        byte y = (byte) deltaY;
        byte wheel = 0x00;
        byte pan = 0x00;

        byte[] mouseReport = new byte[]{buttons, x, y, wheel, pan};

        new Thread(() -> sendReportWithRoot(mouseReport, "/dev/hidg1")).start();
    }

    private void sendMouseClick(byte buttonMask) {
        byte[] pressReport = new byte[]{buttonMask, 0x00, 0x00, 0x00, 0x00};
        byte[] releaseReport = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00};

        new Thread(() -> {
            sendReportWithRoot(pressReport, "/dev/hidg1");
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            sendReportWithRoot(releaseReport, "/dev/hidg1");
        }).start();
    }

    private void sendMouseClickPress(byte buttonMask) {
        byte[] pressReport = new byte[]{buttonMask, 0x00, 0x00, 0x00, 0x00};
        new Thread(() -> sendReportWithRoot(pressReport, "/dev/hidg1")).start();
    }

    private void sendMouseClickRelease() {
        byte[] releaseReport = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00};
        new Thread(() -> sendReportWithRoot(releaseReport, "/dev/hidg1")).start();
    }

    private void sendHidKey(byte modifier, int hidKey) {
        byte[] keyDownReport = new byte[]{modifier, 0x00, (byte) hidKey, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] keyUpReport = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};

        new Thread(() -> {
            sendReportWithRoot(keyDownReport, "/dev/hidg0");
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            sendReportWithRoot(keyUpReport, "/dev/hidg0");
        }).start();
    }

    private Integer androidKeycodeToHid(int androidKeyCode) {
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_DEL: return 0x2A; // Backspace
            case KeyEvent.KEYCODE_ENTER: return 0x28; // Enter
            case KeyEvent.KEYCODE_TAB: return 0x2B; // Tab
            case KeyEvent.KEYCODE_ESCAPE: return 0x29; // Escape

            case KeyEvent.KEYCODE_F1: return 0x3A;
            case KeyEvent.KEYCODE_F2: return 0x3B;
            case KeyEvent.KEYCODE_F3: return 0x3C;
            case KeyEvent.KEYCODE_F4: return 0x3D;
            case KeyEvent.KEYCODE_F5: return 0x3E;
            case KeyEvent.KEYCODE_F6: return 0x3F;
            case KeyEvent.KEYCODE_F7: return 0x40;
            case KeyEvent.KEYCODE_F8: return 0x41;
            case KeyEvent.KEYCODE_F9: return 0x42;
            case KeyEvent.KEYCODE_F10: return 0x43;
            case KeyEvent.KEYCODE_F11: return 0x44;
            case KeyEvent.KEYCODE_F12: return 0x45;

            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x50;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x4F;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x52;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x51;

            case KeyEvent.KEYCODE_MOVE_HOME: return 0x4A;
            case KeyEvent.KEYCODE_MOVE_END: return 0x4D;
            case KeyEvent.KEYCODE_PAGE_UP: return 0x4B;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0x4E;

            case KeyEvent.KEYCODE_INSERT: return 0x49;

            case KeyEvent.KEYCODE_FORWARD_DEL: return 0x4C;

            // Added keys for punctuation and symbols keys from physical keyboard
            case KeyEvent.KEYCODE_GRAVE: return 0x35; // ` ~
            case KeyEvent.KEYCODE_MINUS: return 0x2D; // - _
            case KeyEvent.KEYCODE_EQUALS: return 0x2E; // = +
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 0x2F; // [ {
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 0x30; // ] }
            case KeyEvent.KEYCODE_BACKSLASH: return 0x31; // \ |
            case KeyEvent.KEYCODE_SEMICOLON: return 0x33; // ; :
            case KeyEvent.KEYCODE_APOSTROPHE: return 0x34; // ' "
            case KeyEvent.KEYCODE_COMMA: return 0x36; // , <
            case KeyEvent.KEYCODE_PERIOD: return 0x37; // . >
            case KeyEvent.KEYCODE_SLASH: return 0x38; // / ?

            default: return null;
        }
    }

    private void sendCharAsHid(char c) {
        Integer hidCode = charToHidKeyCode(c);
        if (hidCode == null) {
            Log.w(TAG, "Unsupported character typed: '" + c + "'");
            return;
        }

        byte modifier = 0x00;

        // Use Shift modifier for uppercase letters
        if (c >= 'A' && c <= 'Z') {
            modifier = 0x02;
            hidCode = 0x04 + (c - 'A');
        } else if (isShiftRequiredSymbol(c)) {
            modifier = 0x02;
            Integer symbolHid = symbolToHidCode(c);
            if (symbolHid != null) {
                hidCode = symbolHid;
            }
        } else if (c == 0x08) { // Backspace
            hidCode = 0x2a;
        }

        final byte mod = modifier;
        final byte hidKey = hidCode.byteValue();

        byte[] keyDownReport = new byte[]{mod, 0x00, hidKey, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] keyUpReport = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};

        new Thread(() -> {
            sendReportWithRoot(keyDownReport, "/dev/hidg0");
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            sendReportWithRoot(keyUpReport, "/dev/hidg0");
        }).start();
    }

    private boolean isShiftRequiredSymbol(char c) {
        switch (c) {
            case '!': case '@': case '#': case '$': case '%':
            case '^': case '&': case '*': case '(': case ')':
            case '_': case '+': case '{': case '}': case '|':
            case ':': case '"': case '<': case '>': case '?':
            case '~':
                return true;
            default:
                return false;
        }
    }

    private Integer symbolToHidCode(char c) {
        switch (c) {
            case '!': return 0x1e; // 1 with shift
            case '@': return 0x1f; // 2
            case '#': return 0x20; // 3
            case '$': return 0x21; // 4
            case '%': return 0x22; // 5
            case '^': return 0x23; // 6
            case '&': return 0x24; // 7
            case '*': return 0x25; // 8
            case '(': return 0x26; // 9
            case ')': return 0x27; // 0
            case '_': return 0x2d; // -
            case '+': return 0x2e; // =
            case '{': return 0x2f; // [
            case '}': return 0x30; // ]
            case '|': return 0x31; // \
            case ':': return 0x33; // ;
            case '"': return 0x34; // '
            case '<': return 0x36; // ,
            case '>': return 0x37; // .
            case '?': return 0x38; // /
            case '~': return 0x35; // `
            default: return null;
        }
    }

    private Integer charToHidKeyCode(char c) {
        if (c >= 'a' && c <= 'z') {
            return 0x04 + (c - 'a');
        } else if (c >= 'A' && c <= 'Z') {
            return 0x04 + (c - 'A');
        } else if (c >= '1' && c <= '9') {
            return 0x1e + (c - '1');
        } else if (c == '0') {
            return 0x27;
        } else if (c == ' ') {
            return 0x2c;
        } else if (c == '\n' || c == '\r') {
            return 0x28;
        } else if (c == '\t') {
            return 0x2b;
        }
        return null;
    }

    private void requestRootBlocking() {
        try {
            suProcess = Runtime.getRuntime().exec("su");
            suOutput = new DataOutputStream(suProcess.getOutputStream());
            Log.i(TAG, "Root shell started");

            Thread.sleep(300);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Failed to start root shell", e);
        }
    }

    private synchronized void sendReportWithRoot(byte[] report, String devicePath) {
        if (suOutput == null) {
            Log.e(TAG, "Root shell not available");
            return;
        }
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append("echo -ne \"");
            for (byte b : report) {
                cmd.append(String.format("\\x%02x", b));
            }
            cmd.append("\" > ").append(devicePath).append("\n");

            suOutput.writeBytes(cmd.toString());
            suOutput.flush();

            Log.d(TAG, "Sent report to " + devicePath + ": " + bytesToHex(report));
        } catch (IOException e) {
            Log.e(TAG, "Failed to send report", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (suOutput != null) {
            try {
                suOutput.writeBytes("exit\n");
                suOutput.flush();
            } catch (IOException ignored) {
            }
        }
        if (suProcess != null) {
            suProcess.destroy();
        }
    }
}
