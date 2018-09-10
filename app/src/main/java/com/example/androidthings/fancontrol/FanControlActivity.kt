// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.fancontrol

import android.app.Activity
import android.content.Context
import android.graphics.Color

import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.KeyEvent

import com.google.android.things.contrib.driver.apa102.Apa102
import com.google.android.things.contrib.driver.button.Button
import com.google.android.things.contrib.driver.button.ButtonInputDriver
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager

import java.io.IOException

class FanControlActivity : Activity() {

    private var m_fanOn = false
    private var m_currTemp = 0.0f
    private val FRAME_DELAY_MS = 100

    private var mButtonInputDriver: ButtonInputDriver? = null
    private var mDisplay: AlphanumericDisplay? = null

    private var mLedstrip: Apa102? = null
    private val mRainbow = IntArray(7)

    private var mLed: Gpio? = null
    private var alphaTweak = 0
    private var animCounter = 0
    private var mIsConnected: Boolean = false
    private val mIsSimulated = true

    private val mHandler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UPDATE_BAROMETER_UI -> {
                }
            }
        }
    }

    private val mAnimateRunnable = object : Runnable {
        override fun run() {
            val colors = IntArray(mRainbow.size)
            animCounter = animCounter + 1

            if (mIsSimulated) {
                // For testing
                if (m_currTemp > 40) {
                    m_fanOn = true
                }
                if (m_currTemp < 10) {
                    m_fanOn = false
                }
            } else {
                // Configuration messages are used to set fan state in another runnable.
            }

            if (!mIsConnected) {
                if (animCounter and 1 == 0) {
                    for (i in colors.indices) {
                        colors[6 - i] = Color.rgb(0, 0, 0)
                    }
                    m_currTemp = 0f
                } else {
                    for (i in colors.indices) {
                        colors[6 - i] = Color.rgb(0, 255, 0)
                    }
                    m_currTemp = -999f
                }
            } else {
                if (m_fanOn) {
                    m_currTemp -= .03f
                    for (i in colors.indices) {
                        val a = alphaTweak + i * (255 / 7)
                        colors[6 - i] = Color.rgb(0, 0, a % 255)
                    }
                } else {
                    alphaTweak += 255 / 7
                    m_currTemp += .03f
                    for (i in colors.indices) {
                        val a = alphaTweak + i * (255 / 7)
                        colors[i] = Color.rgb(a % 255, 0, 0)
                    }
                }
            }

            if (mDisplay != null) {
                try {
                    mDisplay!!.display(m_currTemp.toDouble())
                } catch (e: IOException) {
                    Log.e(TAG, "Error setting display", e)
                }

            }

            try {
                mLedstrip!!.write(colors)
            } catch (e: IOException) {
                Log.e(TAG, "Error setting ledstrip", e)
            }

            // Trigger loop again in future.
            if (!mIsConnected) {
                if (animCounter < 6) { // Green blink animation
                    mHandler.postDelayed(this, 250)
                } else {
                    animCounter = 0
                    mHandler.postDelayed(this, 1000)
                }
            } else {
                mHandler.postDelayed(this, FRAME_DELAY_MS.toLong()) // Normal delay
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Started Fan Control Station")

        val cm = applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val activeNetwork = cm.activeNetworkInfo
        mIsConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting


        setContentView(R.layout.activity_main)

        // GPIO button that generates 'A' keypresses (handled by onKeyUp method)
        try {
            mButtonInputDriver = ButtonInputDriver(BoardDefaults.buttonGpioPin,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_A)
            mButtonInputDriver!!.register()
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_A")
        } catch (e: IOException) {
            throw RuntimeException("Error initializing GPIO button", e)
        }

        try {
            mDisplay = AlphanumericDisplay(BoardDefaults.i2cBus)
            mDisplay!!.setEnabled(true)
            mDisplay!!.clear()
            Log.d(TAG, "Initialized I2C Display")
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing display", e)
            Log.d(TAG, "Display disabled")
            mDisplay = null
        }

        // SPI ledstrip
        try {
            mLedstrip = Apa102(BoardDefaults.spiBus, Apa102.Mode.BGR)
            mLedstrip!!.brightness = LEDSTRIP_BRIGHTNESS
            for (i in mRainbow.indices) {
                mRainbow[i] = Color.rgb(0, 0, 0)
            }
        } catch (e: IOException) {
            mLedstrip = null // Led strip is optional.
        }

        mHandler.post(mAnimateRunnable)

        // GPIO led
        try {
            val pioManager = PeripheralManager.getInstance()
            mLed = pioManager.openGpio(BoardDefaults.ledGpioPin)
            mLed!!.setEdgeTriggerType(Gpio.EDGE_NONE)
            mLed!!.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
            mLed!!.setActiveType(Gpio.ACTIVE_HIGH)
        } catch (e: IOException) {
            throw RuntimeException("Error initializing led", e)
        }

        // Configure the Cloud IoT Connector --
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_A) {
            Log.d(TAG, "A Click")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_A) {
            true
        } else super.onKeyUp(keyCode, event)
    }


    override fun onDestroy() {
        super.onDestroy()
        if (mButtonInputDriver != null) {
            try {
                mButtonInputDriver!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            mButtonInputDriver = null
        }

        if (mLedstrip != null) {
            try {
                mLedstrip!!.brightness = 0
                mLedstrip!!.write(IntArray(7))
                mLedstrip!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error disabling ledstrip", e)
            } finally {
                mLedstrip = null
            }
        }

        if (mLed != null) {
            try {
                mLed!!.value = false
                mLed!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error disabling led", e)
            } finally {
                mLed = null
            }
        }

        // clean up Cloud publisher.
    }

    companion object {

        private val TAG = FanControlActivity::class.java!!.getSimpleName()
        private val LEDSTRIP_BRIGHTNESS = 1

        private val MSG_UPDATE_BAROMETER_UI = 1
    }
}
