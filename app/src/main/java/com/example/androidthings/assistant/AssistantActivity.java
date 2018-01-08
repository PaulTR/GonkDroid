/*
 * Copyright 2017, The Android Open Source Project
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

package com.example.androidthings.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;

import com.example.androidthings.assistant.EmbeddedAssistant.ConversationCallback;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;
import com.google.android.things.device.DeviceManager;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.ConverseResponse.EventType;
import com.google.auth.oauth2.UserCredentials;
import com.google.rpc.Status;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Random;

public class AssistantActivity extends FragmentActivity implements Button.OnButtonEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DroidCommands {

    private static final String SERVICE_ID = "UNIQUE_SERVICE_ID";

    private String LED_PIN = "BCM24";
    private Button mButton;
    private Gpio mLed;

    private String LED_BLINKER = "BCM12";
    private Gpio mBlinker;

    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int DEFAULT_VOLUME = 100;

    private Max98357A mDac;
    private EmbeddedAssistant mEmbeddedAssistant;
    private AudioDeviceInfo audioInputDevice = null;
    private AudioDeviceInfo audioOutputDevice = null;

    private int buttonPressCounter = 0;

    private Gpio mButtonLight;

    private Handler mConsoleTimerHandler = new Handler();
    private Handler mHandler = new Handler();
    private Handler mLoopingSoundHandler = new Handler();

    private GoogleApiClient mGoogleApiClient;
    private String endpoint;
    private CommandParser mParser;

    private WifiManager wifiManager;

    private PayloadCallback mPayloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String s, Payload payload) {
            mParser.parse(new String(payload.asBytes()));
        }

        @Override
        public void onPayloadTransferUpdate(String s, PayloadTransferUpdate payloadTransferUpdate) {}
    };

    private final ConnectionLifecycleCallback mConnectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    endpoint = endpointId;

                    Nearby.Connections.acceptConnection(mGoogleApiClient, endpointId, mPayloadCallback)
                            .setResultCallback(new ResultCallback<com.google.android.gms.common.api.Status>() {
                                @Override
                                public void onResult(@NonNull com.google.android.gms.common.api.Status status) {
                                    if( status.isSuccess() ) {
                                        try {
                                            mButtonLight.setValue(true);
                                        } catch (IOException e) {
                                        }
                                    }
                                }
                            });

                    Nearby.Connections.stopAdvertising(mGoogleApiClient);
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result) {}

                @Override
                public void onDisconnected(String endpointId) {
                    try {
                        mButtonLight.setValue(false);
                    } catch( IOException e ) {}
                }
            };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiManager.setWifiEnabled(true);

        mParser = new CommandParser(this);

        mGoogleApiClient = new GoogleApiClient.Builder(this, this, this)
                .addApi(Nearby.CONNECTIONS_API)
                .enableAutoManage(this, this)
                .build();

        audioInputDevice = findAudioDevice(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUS);
        audioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUS);

        try {
            mDac = VoiceHat.openDac();
            mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
            PeripheralManagerService pioService = new PeripheralManagerService();
            mLed = pioService.openGpio(LED_PIN);
            mButton = VoiceHat.openButton();
            mButtonLight = VoiceHat.openLed();
            mButtonLight.setValue(false);

            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);

            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        int initVolume = preferences.getInt(PREF_CURRENT_VOLUME, DEFAULT_VOLUME);

        UserCredentials userCredentials = null;
        try {
            userCredentials =
                    EmbeddedAssistant.generateCredentials(this, R.raw.credentials);
        } catch (IOException | JSONException e) {}

        mEmbeddedAssistant = new EmbeddedAssistant.Builder()
                .setCredentials(userCredentials)
                .setAudioInputDevice(audioInputDevice)
                .setAudioOutputDevice(audioOutputDevice)
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioVolume(initVolume)
                .setRequestCallback(new EmbeddedAssistant.RequestCallback() {})
                .setConversationCallback(new ConversationCallback() {
                    @Override
                    public void onResponseStarted() {
                        if (mDac != null) {
                            try {
                                mDac.setSdMode(Max98357A.SD_MODE_LEFT);
                                mLed.setValue(false);
                            } catch (IOException e) {}
                        }
                    }

                    @Override
                    public void onResponseFinished() {
                        if (mDac != null) {
                            try {
                                mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                                mLed.setValue(true);
                            } catch (IOException e) {}
                        }
                    }

                    @Override
                    public void onConversationEvent(EventType eventType) {}

                    @Override
                    public void onAudioSample(ByteBuffer audioSample) {
                        try {
                            mLed.setValue(!mLed.getValue());
                        } catch (IOException e) {}
                    }

                    @Override
                    public void onConversationError(Status error) {}

                    @Override
                    public void onError(Throwable throwable) {}

                    @Override
                    public void onVolumeChanged(int percentage) {
                        Editor editor = PreferenceManager
                                .getDefaultSharedPreferences(AssistantActivity.this)
                                .edit();
                        editor.putInt(PREF_CURRENT_VOLUME, percentage);
                        editor.apply();
                    }

                    @Override
                    public void onConversationFinished() {}
                })
                .build();

        mEmbeddedAssistant.connect();

        try {
            PeripheralManagerService service = new PeripheralManagerService();
            mBlinker = service.openGpio(LED_BLINKER);
            mBlinker.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mHandler.post(mBlinkRunnable);
        } catch( IOException e ) {}
    }

    private AudioDeviceInfo findAudioDevice(int deviceFlag, int deviceType) {
        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] adis = manager.getDevices(deviceFlag);
        for (AudioDeviceInfo adi : adis) {
            if (adi.getType() == deviceType) {
                return adi;
            }
        }
        return null;
    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(!pressed);
            }
        } catch (IOException e) {}

        if (pressed) {
            if( buttonPressCounter == 0 ) {
                mConsoleTimerHandler.postDelayed(mConsoleModeRunnable, 5000);
            }

            buttonPressCounter++;
            if( wifiManager.isWifiEnabled() ) {
                mEmbeddedAssistant.startConversation();
            } else {
                playSound(R.raw.droid_gonk_03);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if( mBlinker != null ) {
            try {
                mBlinker.close();
            } catch( IOException e ) {}
        }

        mHandler.removeCallbacks(mBlinkRunnable);

        if( mButtonLight != null ) {
            try {
                mButtonLight.close();
            } catch( IOException e ) {}

            mButtonLight = null;
        }

        if (mLed != null) {
            try {
                mLed.close();
            } catch (IOException e) {}
            mLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {}
            mButton = null;
        }
        if (mDac != null) {
            try {
                mDac.close();
            } catch (IOException e) {}
            mDac = null;
        }
        mEmbeddedAssistant.destroy();

    }

    private Runnable mLoopingNoiseRunnable = new Runnable() {
        @Override
        public void run() {
            playSoundByIndex(new Random().nextInt(5 ) + 1);

            mLoopingSoundHandler.postDelayed(mLoopingNoiseRunnable, 5000);
        }
    };

    private Runnable mBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                mBlinker.setValue(!mBlinker.getValue());

                mHandler.postDelayed(mBlinkRunnable, 1000);
            } catch (IOException e) {}
        }
    };

    private Runnable mConsoleModeRunnable = new Runnable() {
        @Override
        public void run() {
            if( buttonPressCounter >= 3 && buttonPressCounter <= 6) {
                startAdvertising();
            } else if( buttonPressCounter > 7 ) {

                mLoopingSoundHandler.removeCallbacks(mLoopingNoiseRunnable);
                DeviceManager manager = new DeviceManager();
                manager.reboot();
            }

            buttonPressCounter = 0;
        }
    };

    private void playSound(int sound) {
        InputStream inputStream = getResources().openRawResource(sound);
        try {
            mDac.setSdMode(Max98357A.SD_MODE_LEFT);
            mEmbeddedAssistant.playSound(inputStream);
        } catch( IOException e ) {}
    }

    private void startAdvertising() {
        Nearby.Connections.startAdvertising(
                mGoogleApiClient,
                "Droid",
                SERVICE_ID,
                mConnectionLifecycleCallback,
                new AdvertisingOptions(Strategy.P2P_STAR));


    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {}

    @Override
    public void onConnectionSuspended(int i) {}

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {}

    @Override
    public void onRestartCommand() {
        Nearby.Connections.sendPayload(mGoogleApiClient, endpoint, Payload.fromBytes("Rebooting...".getBytes()));
        DeviceManager deviceManager = new DeviceManager();
        deviceManager.reboot();
    }

    @Override
    public void onBackgroundSoundToggle(boolean enabled) {
        if( enabled ) {
            mLoopingSoundHandler.post(mLoopingNoiseRunnable);
        } else {
            mLoopingSoundHandler.removeCallbacks(mLoopingNoiseRunnable);
        }

        Nearby.Connections.sendPayload(mGoogleApiClient, endpoint, Payload.fromBytes("Success".getBytes()));
    }

    @Override
    public void onPlaySound(int sound) {
        Nearby.Connections.sendPayload(mGoogleApiClient, endpoint, Payload.fromBytes("Attempting to play sound...".getBytes()));
        playSoundByIndex(sound);
    }

    private void playSoundByIndex(int index) {
        switch( index ) {
            case 1: {
                playSound(R.raw.droid_gonk_01);
                break;
            }
            case 2: {
                playSound(R.raw.droid_gonk_02);
                break;
            }
            case 3: {
                playSound(R.raw.droid_gonk_03);
                break;
            }
            case 4: {
                playSound(R.raw.droid_gonk_04);
                break;
            }
            case 5: {
                playSound(R.raw.droid_gonk_die_01);
                break;
            }
            case 6: {
                playSound(R.raw.droid_gonk_die_02);
                break;
            }
        }
    }
}
