package com.volosyukivan;

import java.util.HashSet;

import org.apache.http.impl.client.DefaultProxyAuthenticationHandler;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.inputmethodservice.ExtractEditText;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.volosyukivan.RemoteKeyListener.Stub;

public class WiFiInputMethod extends InputMethodService {
  public static final int KEY_HOME = -1000;
  public static final int KEY_END = -1001;
  public static final int KEY_CONTROL = -1002;
  public static final int KEY_DEL = -1003;

  
  
  @Override
  public void onStartInput(EditorInfo attribute, boolean restarting) {
    super.onStartInput(attribute, restarting);
    try {
      String text = getText();
      remoteKeyboard.startTextEdit(text);
    } catch (RemoteException e) {
      Debug.e("failed communicating to HttpService", e);
    } catch (NullPointerException e) {
      Debug.e("remoteKeyboard is not connected yet", e);
    }
  }

  ServiceConnection serviceConnection;
  private RemoteKeyboard remoteKeyboard;
  protected Stub keyboardListener;

  @Override
  public void onDestroy() {
//    Debug.d("WiFiInputMethod onDestroy()");
    try {
      if (remoteKeyboard != null)
        remoteKeyboard.unregisterKeyListener(keyboardListener);
    } catch (RemoteException e) {
//      Debug.d("Failed to unregister listener");
    }
    remoteKeyboard = null;
    if (serviceConnection != null)
      unbindService(serviceConnection);
    serviceConnection = null;
    super.onDestroy();
  }

  PowerManager.WakeLock wakeLock;
  HashSet<Integer> pressedKeys = new HashSet<Integer>();
  
  @Override
  public void onCreate() {
    super.onCreate();
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    wakeLock = pm.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "wifikeyboard");
//    Debug.d("WiFiInputMethod started");
    serviceConnection = new ServiceConnection() {
      //@Override
      public void onServiceConnected(ComponentName name, IBinder service) {
//        Debug.d("WiFiInputMethod connected to HttpService.");
        try {
          remoteKeyboard = RemoteKeyboard.Stub.asInterface(service);
          keyboardListener = new RemoteKeyListener.Stub() {
            @Override
            public void keyEvent(int code, boolean pressed) throws RemoteException {
              // Debug.d("got key in WiFiInputMethod");
              receivedKey(code, pressed);
            }
            @Override
            public void charEvent(int code) throws RemoteException {
              // Debug.d("got key in WiFiInputMethod");
              receivedChar(code);
            }
            @Override
            public boolean setText(String text) throws RemoteException {
              return WiFiInputMethod.this.setText(text);
            }
            @Override
            public String getText() throws RemoteException {
              return WiFiInputMethod.this.getText();
            }
          };
          RemoteKeyboard.Stub.asInterface(service).registerKeyListener(keyboardListener);
        } catch (RemoteException e) {
          throw new RuntimeException(
              "WiFiInputMethod failed to connected to HttpService.", e);
        }
      }
      //@Override
      public void onServiceDisconnected(ComponentName name) {
//        Debug.d("WiFiInputMethod disconnected from HttpService.");
      }
    }; 
    if (this.bindService(new Intent(this, HttpService.class),
        serviceConnection, BIND_AUTO_CREATE) == false) {
      throw new RuntimeException("failed to connect to HttpService");
    }
  }
  
  void receivedChar(int code) {
    wakeLock.acquire();
    wakeLock.release();
    InputConnection conn = getCurrentInputConnection();
    if (conn == null) {
//      Debug.d("connection closed");
      return;
    }
    
    if (pressedKeys.contains(KEY_CONTROL)) {
      switch (code) {
      case 'a':case 'A': selectAll(conn); return;
      case 'x':case 'X': cut(conn); return;
      case 'c':case 'C': copy(conn); return;
      case 'v':case 'V': paste(conn); return;
      }
    }

    String text = null; 
    if (code >= 0 && code <= 65535) {
      text = new String(new char[] { (char) code } );
    } else {
      int HI_SURROGATE_START = 0xD800;
      int LO_SURROGATE_START = 0xDC00;
      int hi = ((code >> 10) & 0x3FF) - 0x040 | HI_SURROGATE_START;
      int lo = LO_SURROGATE_START | (code & 0x3FF);
      text = new String(new char[] { (char) hi, (char) lo } );
    }
    conn.commitText(text, 1);
  }
  
  void receivedKey(int code, boolean pressed) {
    if (code == KeyboardHttpServer.FOCUS) {
      for (int key : pressedKeys) {
        sendKey(key, false, false);
      }
      pressedKeys.clear();
      resetModifiers();
      return;
    }
    if (pressedKeys.contains(code) == pressed) {
      if (pressed == false) return;
      // ignore autorepeat on following keys
      switch (code) {
      case KeyEvent.KEYCODE_ALT_LEFT:
      case KeyEvent.KEYCODE_SHIFT_LEFT:
      case KeyEvent.KEYCODE_HOME:
      case KeyEvent.KEYCODE_MENU: return;
      }
    }
    if (pressed) {
      pressedKeys.add(code);
      sendKey(code, pressed, false);
    } else {
      pressedKeys.remove(code);
      sendKey(code, pressed, pressedKeys.isEmpty());
    }
  }
  
  void resetModifiers() {
    InputConnection conn = getCurrentInputConnection();
    if (conn == null) {
      return;
    }
    conn.clearMetaKeyStates(
        KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_SYM_ON);
  }
  
  private long lastWake;
  
  void sendKey(int code, boolean down, boolean resetModifiers) {
    long time = System.currentTimeMillis();
    if (time - lastWake > 5000) {
      wakeLock.acquire();
      wakeLock.release();
      lastWake = time;
    }
    InputConnection conn = getCurrentInputConnection();
    if (conn == null) {
//      Debug.d("connection closed");
      return;
    }
    if (code < 0) {
      if (down == false) return;
      switch (code) {
      case KEY_HOME: keyHome(conn); break;
      case KEY_END: keyEnd(conn); break;
      case KEY_DEL: keyDel(conn); break;
      }
      return;
    }
    
    if (code == KeyEvent.KEYCODE_DPAD_LEFT && pressedKeys.contains(KEY_CONTROL)) {
      if (down == false) return;
      wordLeft(conn);
      return;
    } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && pressedKeys.contains(KEY_CONTROL)) {
      if (down == false) return;
      wordRight(conn);
      return;
    } else if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
      if (pressedKeys.contains(KEY_CONTROL)) {
        if (!down) return;
        copy(conn);
        return;
      }
      if (pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) {
        if (!down) return;
        paste(conn);
        return;
      }
    }
    
//    if (pressedKeys.contains(KEY_CONTROL)) {
//      if (down == false) return;
//      switch (code) {
//      case KeyEvent.KEYCODE_A: selectAll(conn); break;
//      case KeyEvent.KEYCODE_X: cut(conn); break;
//      case KeyEvent.KEYCODE_C: copy(conn); break;
//      case KeyEvent.KEYCODE_V: paste(conn); break;
//      }
//      return;
//    }
    
    
    conn.sendKeyEvent(new KeyEvent(
        down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code));
    if (resetModifiers) {
      conn.clearMetaKeyStates(
          KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_SYM_ON);
    }
  }
  
  private void keyDel(InputConnection conn) {
    if (pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) {
      cut(conn);
      return;
    }
    conn.deleteSurroundingText(0, 1);
    conn.commitText("", 0);
  }

  private void paste(InputConnection conn) {
    conn.performContextMenuAction(android.R.id.paste);
  }

  private void copy(InputConnection conn) {
    conn.performContextMenuAction(android.R.id.copy);
  }

  private void cut(InputConnection conn) {
    conn.performContextMenuAction(android.R.id.cut);
  }

  private void selectAll(InputConnection conn) {
    ExtractedText text = conn.getExtractedText(req, 0);
    conn.setSelection(0, text.text.length());
  }
  
  ExtractedTextRequest req = new ExtractedTextRequest();
  {
    req.hintMaxChars = 100000;
    req.hintMaxLines = 10000;
  }
  
  private void wordRight(InputConnection conn) {
    boolean shift = pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
    ExtractedText text = conn.getExtractedText(req, 0);
    if (text == null) return;
    
    int end = text.selectionEnd;
    String str = text.text.toString();
    int len = str.length();
    
    for (; end < len; end++) {
      if (!Character.isSpace(str.charAt(end))) break;
    }
    for (; end < len; end++) {
      if (Character.isSpace(str.charAt(end))) break;
    }
    int start = shift ? text.selectionStart : end;
    Log.d("wifikeyboard", "start = " + start + " end = " + end);
    conn.setSelection(start, end);
  }
  
  private void wordLeft(InputConnection conn) {
    boolean shift = pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
    ExtractedText text = conn.getExtractedText(req, 0);
    if (text == null) return;
    
    int end = text.selectionEnd - 1;
    
    String str = text.text.toString();
    
    for (; end >= 0; end--) {
      if (!Character.isSpace(str.charAt(end))) break;
    }
    for (; end >= 0; end--) {
      if (Character.isSpace(str.charAt(end))) break;
    }
    end++;
    int start = shift ? text.selectionStart : end;
    Log.d("wifikeyboard", "start = " + start + " end = " + end);
    conn.setSelection(start, end);
  }

  private void keyEnd(InputConnection conn) {
    boolean control = pressedKeys.contains(KEY_CONTROL);
    boolean shift = pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
    ExtractedText text = conn.getExtractedText(req, 0);
    if (text == null) return;
    int end;
    if (control) {
      end = text.text.length();
    } else {
      end = text.text.toString().indexOf('\n', text.selectionEnd);
      if (end == -1) end = text.text.length(); 
    }
    int start = shift ? text.selectionStart : end;
    Log.d("wifikeyboard", "start = " + start + " end = " + end);
    conn.setSelection(start, end);
  }

  private void keyHome(InputConnection conn) {
    boolean control = pressedKeys.contains(KEY_CONTROL);
    boolean shift = pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT);
    ExtractedText text = conn.getExtractedText(req, 0);
    if (text == null) return;
    
    int end;
    if (control) {
      end = 0;
    } else {
      end = text.text.toString().lastIndexOf('\n', text.selectionEnd - 1);
      end++;
    }
    int start = shift ? text.selectionStart : end;
    Log.d("wifikeyboard", "start = " + start + " end = " + end);
   conn.setSelection(start, end);
  }

  boolean setText(String text) {
    // FIXME: need feedback if the input was lost
    InputConnection conn = getCurrentInputConnection();
    if (conn == null) {
//      Debug.d("connection closed");
      return false;
    }
    conn.beginBatchEdit();
    // FIXME: hack
    conn.deleteSurroundingText(100000, 100000);
    conn.commitText(text, text.length());
    conn.endBatchEdit();
    return true;
  }
  
  String getText() {
    String text = "";
    try {
      InputConnection conn = getCurrentInputConnection();
      ExtractedTextRequest req = new ExtractedTextRequest();
      req.hintMaxChars = 1000000;
      req.hintMaxLines = 10000;
      req.flags = 0;
      req.token = 1;
      text = conn.getExtractedText(req, 0).text.toString();
    } catch (Throwable t) {
    }
    return text;
  }
}
