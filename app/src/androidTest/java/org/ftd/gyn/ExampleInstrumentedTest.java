package org.ftd.gyn;

import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.clearText;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.closeSoftKeyboard;
import static android.support.test.espresso.action.ViewActions.typeText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import android.support.test.espresso.core.deps.guava.base.Optional;
import android.util.Log;
import android.view.View;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import static org.junit.Assert.*;

/**
 * Instrumentation test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    private static final String ADDRESS_PREFIX = "0x";

    private static final String TAG = "ExampleInstrumentedTest";

    public int[] gains = {
            128*1/*00:80*/,
            128*2/*01:00*/,
            128*3/*01:80*/,
            128*4/*02:00*/,
            128*5/*02:80*/,
            128*6/*03:00*/,
            128*7/*03:80*/,
            128*8/*04:00*/
    };

    public int[] exposure = {1976*1, 1976*2, 1976*3, 1976*4};

    @Rule
    public ActivityTestRule<IrisguiActicity> rule =
            new ActivityTestRule(IrisguiActicity.class, true,
                    false);

    @Test
    public void deIspRiskTest() {
        int count = 0;
        Intent intent = new Intent();
        rule.launchActivity(intent);

        onView(withId(R.id.open)).perform(click()).check(matches(isDisplayed()));

        onView(withId(R.id.startStream)).perform(click());

        while (count < 36000) {
            long beginTime = System.currentTimeMillis();

            // sensor gain
            // addr: 0x3508, 0x3509
            // value: 128~128x8
            setRegister(gains, 8, "0x3509", "0x3508");

            // exposure
            // addr: 0x380e, 0x380f
            // value: 1976~1976x4
            setRegister(exposure, 4, "0x380f", "0x380e");

            long duration = System.currentTimeMillis() - beginTime;
            long needSleep;
            if ((needSleep = 100000 - duration) <= 0) {
                needSleep = 0;
            }
            //Log.d(TAG, "duration:"+ duration + ", needSleep:" + needSleep);
            SystemClock.sleep(needSleep/1000);
            count++;
        }
    }

    public void setRegister(int data[], int count, String addr1, String addr2){
        Random random=new java.util.Random();
        int index = random.nextInt(count);

        int high = ((data[index] >> 8) & 0xff);
        String highStr = ADDRESS_PREFIX + Integer.toHexString(high);
        int low = data[index] & 0xff;
        String lowStr = ADDRESS_PREFIX + Integer.toHexString(low);

        //Log.d(TAG, "high:" + high + ", low:" + low + ", high hex:" + highStr + ", low hex:" + lowStr);

        //write low
        onView(withId(R.id.editRegisterAddr)).perform(typeText(addr1), closeSoftKeyboard());
        onView(withId(R.id.editRegisterValue)).perform(typeText(lowStr), closeSoftKeyboard());
        onView(withId(R.id.writeRegister)).perform(click());

        onView(withId(R.id.editRegisterAddr)).perform(clearText());
        onView(withId(R.id.editRegisterValue)).perform(clearText());

        //write high
        onView(withId(R.id.editRegisterAddr)).perform(typeText(addr2), closeSoftKeyboard());
        onView(withId(R.id.editRegisterValue)).perform(typeText(highStr), closeSoftKeyboard());
        onView(withId(R.id.writeRegister)).perform(click());

        onView(withId(R.id.editRegisterAddr)).perform(clearText());
        onView(withId(R.id.editRegisterValue)).perform(clearText());

        //read low
        onView(withId(R.id.editRegisterAddr)).perform(typeText(addr1), closeSoftKeyboard());
        onView(withId(R.id.readRegister)).perform(click());
        onView(withId(R.id.editRegisterValue)).check(matches(withText(lowStr)));

        onView(withId(R.id.editRegisterAddr)).perform(clearText());
        onView(withId(R.id.editRegisterValue)).perform(clearText());

        //read high
        onView(withId(R.id.editRegisterAddr)).perform(typeText(addr2), closeSoftKeyboard());
        onView(withId(R.id.readRegister)).perform(click());
        onView(withId(R.id.editRegisterValue)).check(matches(withText(highStr)));

        onView(withId(R.id.editRegisterAddr)).perform(clearText());
        onView(withId(R.id.editRegisterValue)).perform(clearText());
    }

}
