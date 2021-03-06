/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.support.v4.app;

import static junit.framework.Assert.assertNull;
import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.v4.app.test.LoaderActivity;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class LoaderTest {
    private static final int DELAY_LOADER = 10;

    @Rule
    public ActivityTestRule<LoaderActivity> mActivityRule =
            new ActivityTestRule(LoaderActivity.class);

    @After
    public void clearActivity() {
        LoaderActivity.clearState();
    }

    /**
     * Test to ensure that there is no Activity leak due to Loader
     */
    @Test
    public void testLeak() throws Throwable {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(mActivityRule.getActivity(), LoaderActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        LoaderActivity.sResumed = new CountDownLatch(1);
        instrumentation.startActivitySync(intent);
        assertTrue(LoaderActivity.sResumed.await(1, TimeUnit.SECONDS));

        LoaderFragment fragment = new LoaderFragment();
        FragmentManager fm = LoaderActivity.sActivity.getSupportFragmentManager();

        fm.beginTransaction()
                .add(fragment, "1")
                .commit();

        FragmentTestUtil.executePendingTransactions(mActivityRule, fm);

        fm.beginTransaction()
                .remove(fragment)
                .addToBackStack(null)
                .commit();

        FragmentTestUtil.executePendingTransactions(mActivityRule, fm);

        WeakReference<LoaderActivity> weakActivity = new WeakReference(LoaderActivity.sActivity);

        if (!switchOrientation()) {
            return; // can't switch orientation for square screens
        }

        // Now force a garbage collection.
        FragmentTestUtil.forceGC();
        assertNull(weakActivity.get());
    }

    /**
     * When a LoaderManager is reused, it should notify in onResume
     */
    @Test
    public void startWhenReused() throws Throwable {
        LoaderActivity activity = mActivityRule.getActivity();

        assertEquals("Loaded!", activity.textView.getText().toString());

        if (!switchOrientation()) {
            return; // can't switch orientation for square screens
        }

        // After orientation change, the text should still be loaded properly
        activity = LoaderActivity.sActivity;
        assertEquals("Loaded!", activity.textView.getText().toString());
    }

    /**
     * When a change is interrupted with stop, the data in the LoaderManager remains stale.
     */
    @Test
    public void noStaleData() throws Throwable {
        final LoaderActivity activity = mActivityRule.getActivity();
        final String[] value = new String[] { "First Value" };

        final CountDownLatch[] loadedLatch = new CountDownLatch[] { new CountDownLatch(1) };
        final Loader<String>[] loaders = new Loader[1];
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final Loader<String> loader =
                        activity.getSupportLoaderManager().initLoader(DELAY_LOADER, null,
                                new LoaderManager.LoaderCallbacks<String>() {
                                    @Override
                                    public Loader<String> onCreateLoader(int id, Bundle args) {
                                        return new AsyncTaskLoader<String>(activity) {
                                            @Override
                                            protected void onStopLoading() {
                                                cancelLoad();
                                            }

                                            @Override
                                            public String loadInBackground() {
                                                SystemClock.sleep(50);
                                                return value[0];
                                            }

                                            @Override
                                            protected void onStartLoading() {
                                                if (takeContentChanged()) {
                                                    forceLoad();
                                                }
                                                super.onStartLoading();
                                            }
                                        };
                                    }

                                    @Override
                                    public void onLoadFinished(Loader<String> loader, String data) {
                                        activity.textViewB.setText(data);
                                        loadedLatch[0].countDown();
                                    }

                                    @Override
                                    public void onLoaderReset(Loader<String> loader) {
                                    }
                                });
                loader.forceLoad();
                loaders[0] = loader;
            }
        });

        assertTrue(loadedLatch[0].await(1, TimeUnit.SECONDS));
        assertEquals("First Value", activity.textViewB.getText().toString());

        loadedLatch[0] = new CountDownLatch(1);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                value[0] = "Second Value";
                loaders[0].onContentChanged();
                loaders[0].stopLoading();
            }
        });

        // Since the loader was stopped (and canceled), it shouldn't notify the change
        assertFalse(loadedLatch[0].await(300, TimeUnit.MILLISECONDS));

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loaders[0].startLoading();
            }
        });

        // Since the loader was stopped (and canceled), it shouldn't notify the change
        assertTrue(loadedLatch[0].await(1, TimeUnit.SECONDS));
        assertEquals("Second Value", activity.textViewB.getText().toString());
    }

    private boolean switchOrientation() throws InterruptedException {
        LoaderActivity activity = LoaderActivity.sActivity;

        int currentOrientation = activity.getResources().getConfiguration().orientation;

        int nextOrientation;
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            nextOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            nextOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            return false; // Don't know what to do with square or unknown orientations
        }

        // Now switch the orientation
        LoaderActivity.sResumed = new CountDownLatch(1);

        activity.setRequestedOrientation(nextOrientation);
        assertTrue(LoaderActivity.sResumed.await(1, TimeUnit.SECONDS));
        return true;
    }


    public static class LoaderFragment extends Fragment {
        private static final int LOADER_ID = 1;
        private final LoaderManager.LoaderCallbacks<Boolean> mLoaderCallbacks =
                new LoaderManager.LoaderCallbacks<Boolean>() {
                    @Override
                    public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                        return new DummyLoader(getContext());
                    }

                    @Override
                    public void onLoadFinished(Loader<Boolean> loader, Boolean data) {

                    }

                    @Override
                    public void onLoaderReset(Loader<Boolean> loader) {

                    }
                };

        @Override
        public void onActivityCreated(@Nullable Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            getLoaderManager().initLoader(LOADER_ID, null, mLoaderCallbacks);
        }
    }

    static class DummyLoader extends Loader<Boolean> {
        DummyLoader(Context context) {
            super(context);
        }

        @Override
        protected void onStartLoading() {
            deliverResult(true);
        }
    }
}
