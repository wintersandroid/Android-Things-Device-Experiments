/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.pca6895servotest;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.support.v4.text.TextUtilsCompatJellybeanMr1;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Spinner;
import android.widget.TextView;

import com.appyvet.rangebar.RangeBar;
import com.example.androidthings.pca6895servotest.rf24.RF24;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.common.base.Joiner;
import com.google.common.primitives.Longs;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.ItemSelect;
import org.androidannotations.annotations.KeyDown;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.sharedpreferences.Pref;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;


/**
 * Skeleton of the main Android Things activity. Implement your device's logic
 * in this class.
 * <p>
 * Android Things peripheral APIs are accessible through the class
 * PeripheralManagerService. For example, the snippet below will open a GPIO pin and
 * set it to HIGH:
 * <p>
 * <pre>{@code
 * PeripheralManagerService service = new PeripheralManagerService();
 * mLedGpio = service.openGpio("BCM6");
 * mLedGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
 * mLedGpio.setValue(true);
 * }</pre>
 * <p>
 * For more complex peripherals, look for an existing user-space driver, or implement one if none
 * is available.
 */

@EActivity(R.layout.main_activity)
public class MainActivity extends Activity {
  private static final String TAG = MainActivity.class.getSimpleName();
  static {
    System.loadLibrary("native-lib");
  }

  @Pref
  AppPrefs_ appPrefs;

  @ViewById(R.id.seekBar)
  RangeBar rangeBar;

  @ViewById(R.id.textView)
  TextView textView;

  @ViewById(R.id.spinnerChannel)
  Spinner spinnerChannel;

  private static final int SERVO_MIN = 145;
  private static final int SERVO_MAX = 580;
  private int usingChannel = 0;
  private GPIODevice gpioDevice;
  private RF24 rf24;

  class RangeBarChangeListener implements RangeBar.OnRangeBarChangeListener {

    @Override
    public void onRangeChangeListener(RangeBar rangeBar, int leftPinIndex, int rightPinIndex, String leftPinValue, String rightPinValue) {
      try {
        setChannelLeftAngle(usingChannel, leftPinIndex);
        setChannelRightAngle(usingChannel, rightPinIndex);
        updateText();
      } catch (Exception e) { // NOSONAR
        Log.d("ERROR", "Exception: " + e.getMessage());
      }

    }
  }

  static final int AF_RED  = 6;
  static final int AF_GREEN = 7;
  static final int AF_BLUE  = 8;

  static final int AF_E =   13;
  static final int AF_RW =   14;
  static final int AF_RS =   15;

  static final int AF_DB4 =   12;
  static final int AF_DB5 =   11;
  static final int AF_DB6 =   10;
  static final int AF_DB7 =  9;

  private LCDDriver lcdDriver;

  static final int AF_SELECT =  0;
//  static final int AF_RIGHT =  1;
//  static final int AF_DOWN =  2;
//  static final int AF_UP =  3;
//  static final int AF_LEFT =  4;

  enum ServoPosition{
    LEFT,RIGHT
  }

  private List<Integer> channelLeftAngles = new ArrayList<>(16);
  private List<Integer> channelRightAngles = new ArrayList<>(16);

  private ServoPosition[] servoPositions = {ServoPosition.LEFT,ServoPosition.LEFT,ServoPosition.LEFT,ServoPosition.LEFT,ServoPosition.LEFT};

  private DeviceHolder deviceHolder = DeviceHolder.getInstance();



  @AfterViews
  protected void onAfterViews() {
    channelLeftAngles.clear();
    if(!appPrefs.channelAnglesLeft().get().isEmpty()){
      TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter('|');

      // Once per string to split
      splitter.setString(appPrefs.channelAnglesLeft().get());
      for (String s : splitter) {
        channelLeftAngles.add(Integer.parseInt(s));
      }
    }else{
      for(int i = 0; i < 16; i++){
        channelLeftAngles.add(20);
      }
    }

    if(!appPrefs.channelAnglesRight().get().isEmpty()){
      channelRightAngles.clear();
      TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter('|');

      // Once per string to split
      splitter.setString(appPrefs.channelAnglesRight().get());
      for (String s : splitter) {
        channelRightAngles.add(Integer.parseInt(s));
      }
    }else{
      for(int i = 0; i < 16; i++){
        channelRightAngles.add(40);
      }
    }

    rangeBar.setOnRangeBarChangeListener(new RangeBarChangeListener());
    spinnerChannel.setSelection(appPrefs.selectedChannel().get());

    try {
      PeripheralManagerService peripheralManagerService = new PeripheralManagerService();

      @SuppressWarnings("squid:S2095")
      PCA9685Servo pca9685Servo = new PCA9685Servo(PCA9685Servo.PCA9685_ADDRESS, peripheralManagerService);
      pca9685Servo.setServoMinMaxPwm(0, 180, SERVO_MIN, SERVO_MAX);
      deviceHolder.setDevice(DeviceHolder.Devices.PCA9685SERVO,pca9685Servo);

      @SuppressWarnings("squid:S2095")
      MCP23017 mcp23017 = new MCP23017((byte) 0x20, peripheralManagerService);


      mcp23017.setPinMode(AF_RED, MCP23017.PinMode.MODE_OUTPUT);
      mcp23017.setPinMode(AF_GREEN, MCP23017.PinMode.MODE_OUTPUT);
      mcp23017.setPinMode(AF_BLUE, MCP23017.PinMode.MODE_OUTPUT);
      mcp23017.writePin(AF_RED, MCP23017.PinState.HIGH);
      mcp23017.writePin(AF_GREEN, MCP23017.PinState.HIGH);
      mcp23017.writePin(AF_BLUE, MCP23017.PinState.HIGH);

      deviceHolder.setDevice(DeviceHolder.Devices.MCP23017, mcp23017);

      doSelect();

      mcp23017.setPinMode(AF_RW, MCP23017.PinMode.MODE_OUTPUT);
      mcp23017.writePin(AF_RW, MCP23017.PinState.LOW);
      lcdDriver = new LCDDriver(mcp23017,2,16,4,AF_RS,AF_E,AF_DB4,AF_DB5,AF_DB6,AF_DB7,0,0,0,0);

      lcdDriver.lcdPuts("Hello");


      gpioDevice = new GPIODevice(peripheralManagerService);

      gpioDevice.setPinMode(13, IODeviceInterface.PinMode.MODE_OUTPUT);
      gpioDevice.writePin(13, IODeviceInterface.PinState.HIGH);

      pingRadioThread(peripheralManagerService);

    } catch (Exception e) { // NOSONAR
      Log.d("ERROR", "Exception: " + e.getMessage());
    }
  }

  boolean stop = false;

  @Background
  void pingRadioThread(PeripheralManagerService peripheralManagerService){
    try {
      rf24 = new RF24(peripheralManagerService, gpioDevice, 0, 22, 32);
      rf24.begin();
      rf24.setRetries((byte)15,(byte)15);
      Log.d(TAG, rf24.printDetails());

      String[] pipes = {"1Node","2Node"};

      rf24.openWritingPipe(pipes[0].getBytes());
      rf24.openReadingPipe((byte)1,pipes[1].getBytes());

      pingOut();
      //pongBack();

    } catch (Exception e) { // NOSONAR
      Log.d("ERROR", "Exception: " + e.getMessage());
    }

  }

  private void pongBack() throws IOException, InterruptedException {
    rf24.startListening();
    while(!stop){
      if(rf24.available()){
        long got_time =0;
        while(rf24.available()){
          byte[] got_buffer = rf24.read(4);
          got_time = byteArrayToClong(got_buffer);
        }
        rf24.stopListening();
        rf24.write(longToCByteArray(got_time),4);
        Log.d(TAG,String.format("Got payload %d...", got_time));

        Thread.sleep(925);
      }
      //stop = true;
    }
  }

  private void pingOut() throws IOException, InterruptedException {
    rf24.startListening();
    while(!stop){
      rf24.stopListening();
      Log.d(TAG,"Sending...");
      long time =  SystemClock.uptimeMillis();
      byte[] buffer = longToCByteArray(time);
//            Longs.toByteArray(time);
//        for(int x = 0; x < 4; x++){
//          buffer[x] = buffer[x+4];
//        }
      boolean ok = rf24.write(buffer,4);

      if(!ok){
        Log.e(TAG,"Send Failed");
      }
      rf24.startListening();
      long started_waiting_at = SystemClock.uptimeMillis();
      boolean timeout = false;
      while(!rf24.available() && !timeout){
        if(SystemClock.uptimeMillis() - started_waiting_at > 200){
          timeout = true;
        }
      }

      if(timeout){
        Log.e(TAG,"Response timed out");
      }else{
        byte[] got_buffer = rf24.read(4);
        long got_time = byteArrayToClong(got_buffer);

        Log.d(TAG,String.format("GOT Response %d, sent %d, trip delay %d",got_time,time,SystemClock.uptimeMillis() - time));
      }

      Thread.sleep(1000);

    }
  }

  @Override
  public void onResume(){
    super.onResume();

    Intent intent = new Intent(this,ButtonDriverService.class);
    startService(intent);
  }

  @Override
  public void onPause(){
    super.onPause();
    Intent intent = new Intent(this,ButtonDriverService.class);
    stopService(intent);
  }

  @Click(R.id.buttonSetLeft)
  void onButtonSetLeftClick(){
    try {
      IODeviceInterface deviceInterface = DeviceHolder.getInstance().getDevice(DeviceHolder.Devices.PCA9685SERVO);
      if(deviceInterface != null && deviceInterface instanceof PCA9685Servo) {
        ((PCA9685Servo)deviceInterface).setServoAngle(usingChannel, getChannelLeftAngle(usingChannel));
        servoPositions[usingChannel] = ServoPosition.LEFT;
      }
    } catch (IOException e) { // NOSONAR - logged with android.
      Log.d(TAG,"Exception on Left Click: " + e.getMessage());
    }
  }

  @Click(R.id.buttonSetRight)
  void onButtonSetRightClick(){
    try {
      IODeviceInterface deviceInterface = DeviceHolder.getInstance().getDevice(DeviceHolder.Devices.PCA9685SERVO);
      if(deviceInterface != null && deviceInterface instanceof PCA9685Servo) {
        ((PCA9685Servo)deviceInterface).setServoAngle(usingChannel, getChannelRightAngle(usingChannel));
        servoPositions[usingChannel] = ServoPosition.RIGHT;
      }
    } catch (IOException e) { // NOSONAR - logged with android.
      Log.d(TAG,"Exception on Right Click: " + e.getMessage());
    }
  }

  @ItemSelect(R.id.spinnerChannel)
  void onItemSelect(boolean selected, int position){
    if(selected) {
      usingChannel = position;
      rangeBar.setRangePinsByIndices(getChannelLeftAngle(usingChannel),getChannelRightAngle(usingChannel));
      appPrefs.selectedChannel().put(position);
      updateText();
    }
  }

  private void updateText() {
    textView.setText(String.format(Locale.getDefault(), "Channel %d Angle Left %d Angle Right %d",
        usingChannel, rangeBar.getLeftIndex(), rangeBar.getRightIndex()));

  }


  @Override
  protected void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "onDestroy");
  }

  @KeyDown({KeyEvent.KEYCODE_0,KeyEvent.KEYCODE_1,KeyEvent.KEYCODE_2,KeyEvent.KEYCODE_3,KeyEvent.KEYCODE_4})
  void onKeyDown0(KeyEvent keyEvent){
    int channel =keyEvent.getKeyCode() - KeyEvent.KEYCODE_0;
    Log.d(TAG,"Key Down " + channel);
    try {
      if(channel == AF_SELECT){
        doSelect();
      }else{
        swapChannel(channel-1);
      }

    } catch (IOException e) { // NOSONAR - logged with android.
      Log.d(TAG,"Exception on Right Click: " + e.getMessage());
    }
  }

  int currentColour = 0;

  class Rgb{
    Rgb(boolean r, boolean g, boolean b){
      this.r = r;
      this.g = g;
      this.b = b;
    }
    final boolean  r;
    final boolean  g;
    final boolean  b;
  }

  Rgb[] rbgs = {
      new Rgb(true,false,false),
      new Rgb(false,true,false),
      new Rgb(false,false,true),
      new Rgb(true,true,true),
      new Rgb(true,false,true),
      new Rgb(false,true,true),
      new Rgb(true,true,false),
  };

  private void doSelect() throws IOException {
    IODeviceInterface deviceInterface = DeviceHolder.getInstance().getDevice(DeviceHolder.Devices.MCP23017);

    deviceInterface.writePin(AF_RED, rbgs[currentColour].r ? IODeviceInterface.PinState.LOW :  IODeviceInterface.PinState.HIGH);
    deviceInterface.writePin(AF_GREEN, rbgs[currentColour].g ? IODeviceInterface.PinState.LOW :  IODeviceInterface.PinState.HIGH);
    deviceInterface.writePin(AF_BLUE, rbgs[currentColour].b ? IODeviceInterface.PinState.LOW :  IODeviceInterface.PinState.HIGH);

    currentColour ++;
    if(currentColour == 7){
      currentColour = 0;
    }

    if(gpioDevice != null) {
      gpioDevice.writePin(13, gpioDevice.readPin(13) == IODeviceInterface.PinState.HIGH ? IODeviceInterface.PinState.LOW : IODeviceInterface.PinState.HIGH);
    }


  }

  private void swapChannel(int channel) throws IOException {
    IODeviceInterface deviceInterface = DeviceHolder.getInstance().getDevice(DeviceHolder.Devices.PCA9685SERVO);
    if(deviceInterface != null && deviceInterface instanceof PCA9685Servo) {
      if(servoPositions[channel] == ServoPosition.LEFT) {
        ((PCA9685Servo)deviceInterface).setServoAngle(channel, getChannelRightAngle(channel));
        servoPositions[channel] = ServoPosition.RIGHT;
        lcdDriver.lcdClear();
        lcdDriver.lcdPuts("CH " + channel + " RIGHT");
      }else{
        ((PCA9685Servo)deviceInterface).setServoAngle(channel, getChannelLeftAngle(channel));
        servoPositions[channel] = ServoPosition.LEFT;
        lcdDriver.lcdClear();
        lcdDriver.lcdPuts("CH " + channel + " LEFT");
      }
    }
  }

  private void setChannelLeftAngle(int channel, int angle){
    channelLeftAngles.set(channel,angle);
    appPrefs.channelAnglesLeft().put(Joiner.on('|').join(channelLeftAngles));
  }

  private void setChannelRightAngle(int channel, int angle){
    channelRightAngles.set(channel,angle);
    appPrefs.channelAnglesRight().put(Joiner.on('|').join(channelRightAngles));
  }

  int getChannelLeftAngle(int channel){
    return channelLeftAngles.get(channel);
  }

  int getChannelRightAngle(int channel){
    return channelRightAngles.get(channel);
  }

  public native byte[] longToCByteArray(long value);
  public native long  byteArrayToClong(byte[] array);
}
