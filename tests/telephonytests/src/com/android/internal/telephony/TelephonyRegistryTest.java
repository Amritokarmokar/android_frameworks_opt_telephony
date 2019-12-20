/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.telephony;

import static android.telephony.PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE;
import static android.telephony.PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE;
import static android.telephony.PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED;
import static android.telephony.PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;
import static android.telephony.TelephonyManager.MODEM_COUNT_DUAL_MODEM;
import static android.telephony.TelephonyManager.RADIO_POWER_OFF;
import static android.telephony.TelephonyManager.RADIO_POWER_ON;
import static android.telephony.TelephonyManager.RADIO_POWER_UNAVAILABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.LinkProperties;
import android.os.ServiceManager;
import android.telephony.Annotation;
import android.telephony.PhoneCapability;
import android.telephony.PhoneStateListener;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.TelephonyRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TelephonyRegistryTest extends TelephonyTest {
    @Mock
    private ISub.Stub mISubStub;
    private PhoneStateListenerWrapper mPhoneStateListener;
    private TelephonyRegistry mTelephonyRegistry;
    private PhoneCapability mPhoneCapability;
    private int mActiveSubId;
    private int mSrvccState = -1;
    private int mRadioPowerState = RADIO_POWER_UNAVAILABLE;

    public class PhoneStateListenerWrapper extends PhoneStateListener {
        // This class isn't mockable to get invocation counts because the IBinder is null and
        // crashes the TelephonyRegistry. Make a cheesy verify(times()) alternative.
        public AtomicInteger invocationCount = new AtomicInteger(0);

        @Override
        public void onSrvccStateChanged(int srvccState) {
            invocationCount.incrementAndGet();
            mSrvccState = srvccState;
        }

        @Override
        public void onPhoneCapabilityChanged(PhoneCapability capability) {
            invocationCount.incrementAndGet();
            mPhoneCapability = capability;
        }
        @Override
        public void onActiveDataSubscriptionIdChanged(int activeSubId) {
            invocationCount.incrementAndGet();
            mActiveSubId = activeSubId;
        }
        @Override
        public void onRadioPowerStateChanged(@Annotation.RadioPowerState int state) {
            invocationCount.incrementAndGet();
            mRadioPowerState = state;
        }
        @Override
        public void onPreciseDataConnectionStateChanged(PreciseDataConnectionState preciseState) {
            invocationCount.incrementAndGet();
        }
    }

    private void addTelephonyRegistryService() {
        mServiceManagerMockedServices.put("telephony.registry", mTelephonyRegistry.asBinder());
        mTelephonyRegistry.systemRunning();
    }

    @Before
    public void setUp() throws Exception {
        super.setUp("TelephonyRegistryTest");
        // ServiceManager.getService("isub") will return this stub for any call to
        // SubscriptionManager.
        mServiceManagerMockedServices.put("isub", mISubStub);
        mTelephonyRegistry = new TelephonyRegistry(mContext);
        addTelephonyRegistryService();
        mPhoneStateListener = new PhoneStateListenerWrapper();
        processAllMessages();
        assertEquals(mTelephonyRegistry.asBinder(),
                ServiceManager.getService("telephony.registry"));
    }

    @After
    public void tearDown() throws Exception {
        mTelephonyRegistry = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testPhoneCapabilityChanged() {
        // mTelephonyRegistry.listen with notifyNow = true should trigger callback immediately.
        PhoneCapability phoneCapability = new PhoneCapability(1, 2, 3, null, false);
        mTelephonyRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        mTelephonyRegistry.listen(mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                LISTEN_PHONE_CAPABILITY_CHANGE, true);
        processAllMessages();
        assertEquals(phoneCapability, mPhoneCapability);

        // notifyPhoneCapabilityChanged with a new capability. Callback should be triggered.
        phoneCapability = new PhoneCapability(3, 2, 2, null, false);
        mTelephonyRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        processAllMessages();
        assertEquals(phoneCapability, mPhoneCapability);
    }


    @Test @SmallTest
    public void testActiveDataSubChanged() {
        // mTelephonyRegistry.listen with notifyNow = true should trigger callback immediately.
        int[] activeSubs = {0, 1, 2};
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(activeSubs);
        int activeSubId = 0;
        mTelephonyRegistry.notifyActiveDataSubIdChanged(activeSubId);
        mTelephonyRegistry.listen(mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE, true);
        processAllMessages();
        assertEquals(activeSubId, mActiveSubId);

        // notifyPhoneCapabilityChanged with a new capability. Callback should be triggered.
        mActiveSubId = 1;
        mTelephonyRegistry.notifyActiveDataSubIdChanged(activeSubId);
        processAllMessages();
        assertEquals(activeSubId, mActiveSubId);
    }

    /**
     * Test that we first receive a callback when listen(...) is called that contains the latest
     * notify(...) response and then that the callback is called correctly when notify(...) is
     * called.
     */
    @Test
    @SmallTest
    public void testSrvccStateChanged() throws Exception {
        // Return a phone ID of 0 for all sub ids given.
        doReturn(0/*phoneId*/).when(mISubStub).getPhoneId(anyInt());
        int srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_STARTED;
        mTelephonyRegistry.notifySrvccStateChanged(0 /*subId*/, srvccState);
        // Should receive callback when listen is called that contains the latest notify result.
        mTelephonyRegistry.listenForSubscriber(0 /*subId*/, mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                LISTEN_SRVCC_STATE_CHANGED, true);
        processAllMessages();
        assertEquals(srvccState, mSrvccState);

        // trigger callback
        srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED;
        mTelephonyRegistry.notifySrvccStateChanged(0 /*subId*/, srvccState);
        processAllMessages();
        assertEquals(srvccState, mSrvccState);
    }

    /**
     * Test that a SecurityException is thrown when we try to listen to a SRVCC state change without
     * READ_PRIVILEGED_PHONE_STATE.
     */
    @Test
    @SmallTest
    public void testSrvccStateChangedNoPermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        int srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_STARTED;
        mTelephonyRegistry.notifySrvccStateChanged(0 /*subId*/, srvccState);
        try {
            mTelephonyRegistry.listenForSubscriber(0 /*subId*/, mContext.getOpPackageName(),
                    mPhoneStateListener.callback,
                    LISTEN_SRVCC_STATE_CHANGED, true);
            fail();
        } catch (SecurityException e) {
            // pass test!
        }
    }

    /**
     * Test multi sim config change.
     */
    @Test
    public void testMultiSimConfigChange() {
        mTelephonyRegistry.listenForSubscriber(1, mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                LISTEN_RADIO_POWER_STATE_CHANGED, true);
        processAllMessages();
        assertEquals(RADIO_POWER_UNAVAILABLE, mRadioPowerState);

        // Notify RADIO_POWER_ON on invalid phoneId. Shouldn't go through.
        mTelephonyRegistry.notifyRadioPowerStateChanged(1, 1, RADIO_POWER_ON);
        processAllMessages();
        assertEquals(RADIO_POWER_UNAVAILABLE, mRadioPowerState);

        // Switch to DSDS and re-send RADIO_POWER_ON on phone 1. This time it should be notified.
        doReturn(MODEM_COUNT_DUAL_MODEM).when(mTelephonyManager).getActiveModemCount();
        mContext.sendBroadcast(new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED));
        mTelephonyRegistry.notifyRadioPowerStateChanged(1, 1, RADIO_POWER_ON);
        processAllMessages();
        assertEquals(RADIO_POWER_ON, mRadioPowerState);

        // Switch back to single SIM mode and re-send on phone 0. This time it should be notified.
        doReturn(MODEM_COUNT_DUAL_MODEM).when(mTelephonyManager).getActiveModemCount();
        mContext.sendBroadcast(new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED));
        mTelephonyRegistry.notifyRadioPowerStateChanged(0, 1, RADIO_POWER_OFF);
        processAllMessages();
        assertEquals(RADIO_POWER_OFF, mRadioPowerState);
    }

    /**
     * Test multi sim config change.
     */
    @Test
    public void testPreciseDataConnectionStateChanged() {
        final int subId = 0;
        // Initialize the PSL with a PreciseDataConnection
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId, "default",
                new PreciseDataConnectionState(
                    0, 0, 0, "default", new LinkProperties(), 0, null));
        mTelephonyRegistry.listenForSubscriber(subId, mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE, true);
        processAllMessages();
        // Verify that the PDCS is reported for the only APN
        assertEquals(mPhoneStateListener.invocationCount.get(), 1);

        // Add IMS APN and verify that the listener is invoked for the IMS APN
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId, "ims",
                new PreciseDataConnectionState(
                    0, 0, 0, "ims", new LinkProperties(), 0, null));
        processAllMessages();

        assertEquals(mPhoneStateListener.invocationCount.get(), 2);

        // Unregister the listener
        mTelephonyRegistry.listenForSubscriber(subId, mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                PhoneStateListener.LISTEN_NONE, true);
        processAllMessages();

        // Re-register the listener and ensure that both APN types are reported
        mTelephonyRegistry.listenForSubscriber(subId, mContext.getOpPackageName(),
                mPhoneStateListener.callback,
                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE, true);
        processAllMessages();
        assertEquals(mPhoneStateListener.invocationCount.get(), 4);

        // Send a duplicate event to the TelephonyRegistry and verify that the listener isn't
        // invoked.
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId, "ims",
                new PreciseDataConnectionState(
                    0, 0, 0, "ims", new LinkProperties(), 0, null));
        processAllMessages();
        assertEquals(mPhoneStateListener.invocationCount.get(), 4);
    }
}
