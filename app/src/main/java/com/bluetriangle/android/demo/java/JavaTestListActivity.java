package com.bluetriangle.android.demo.java;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProvider;

import com.bluetriangle.analytics.Timer;
import com.bluetriangle.analytics.Tracker;
import com.bluetriangle.analytics.okhttp.BlueTriangleOkHttpInterceptor;
import com.bluetriangle.android.demo.DemoApplication;
import com.bluetriangle.android.demo.R;
import com.bluetriangle.android.demo.databinding.ActivityTestListBinding;
import com.bluetriangle.android.demo.java.screenTracking.ScreenTrackingActivity;
import com.bluetriangle.android.demo.kotlin.CPUTestActivity;
import com.bluetriangle.android.demo.kotlin.TestListViewModel;
import com.bluetriangle.android.demo.tests.ANRTest;
import com.bluetriangle.android.demo.tests.ANRTestScenario;
import com.bluetriangle.android.demo.tests.LaunchTestScenario;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class JavaTestListActivity extends AppCompatActivity {
    private static final String TAG = JavaTestListActivity.class.getSimpleName();
    private ActivityTestListBinding binding;
    private Timer timer = null;
    private OkHttpClient okHttpClient = null;
    private TestListViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_test_list);
        setTitle(R.string.main_title);

        viewModel = new ViewModelProvider(this).get(TestListViewModel.class);
        binding.setViewModel(viewModel);

        updateButtonState();
        addButtonClickListeners();

        okHttpClient =
                new OkHttpClient.Builder()
                        .addInterceptor(new BlueTriangleOkHttpInterceptor(Objects.requireNonNull(Tracker.getInstance()).getConfiguration()))
                        .build();
    }

    private void addButtonClickListeners() {
        binding.buttonStart.setOnClickListener(this::startButtonClicked);
        binding.buttonInteractive.setOnClickListener(this::interactiveButtonClicked);
        binding.buttonStop.setOnClickListener(this::stopButtonClicked);
        binding.buttonNext.setOnClickListener(this::nextButtonClicked);
        binding.buttonBackground.setOnClickListener(this::backgroundButtonClicked);
        binding.buttonCrash.setOnClickListener(this::crashButtonClicked);
        binding.buttonTrackCatchException.setOnClickListener(this::trackCatchExceptionButtonClicked);
        binding.buttonNetwork.setOnClickListener(this::captureNetworkRequests);
        binding.buttonScreenTrack.setOnClickListener(this::screenTrackButtonClicked);
        binding.buttonAnr.setOnClickListener(v -> {
            launchAnrActivity(ANRTestScenario.Unknown, ANRTest.Unknown);
        });

        binding.cpuTest.setOnClickListener((v)-> {
            startActivity(new Intent(this, CPUTestActivity.class));
        });

        binding.buttonLaunchGallery.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivity(Intent.createChooser(intent, "Pick Image"));
        });

        binding.btnAnrTestRun.setOnClickListener(v -> {
            launchAnrActivity(viewModel.getAnrTestScenario().getValue(), viewModel.getAnrTest().getValue());
        });
    }

    private void updateButtonState() {
        binding.buttonStart.setEnabled(timer == null || !timer.isRunning() || timer.hasEnded());
        binding.buttonInteractive.setEnabled(timer != null && timer.isRunning() && !timer.isInteractive() && !timer.hasEnded());
        binding.buttonStop.setEnabled(timer != null && timer.isRunning() && !timer.hasEnded());
    }

    private void startButtonClicked(View view) {
        timer = new Timer(JavaTestListActivity.class.getSimpleName(), "Ä Traffic Šegment").start();
        updateButtonState();
    }

    private void interactiveButtonClicked(View view) {
        if (timer.isRunning()) {
            timer.interactive();
        }
        updateButtonState();
    }

    private void stopButtonClicked(View view) {
        if (timer.isRunning()) {
            timer.end().submit();
        }
        updateButtonState();
    }

    private void nextButtonClicked(View view) {
        Timer timer = new Timer("Next Page", "Android Traffic").start();
        Intent intent = new Intent(this, NextActivity.class);
        intent.putExtra(Timer.EXTRA_TIMER, timer);
        startActivity(intent);
    }

    private void screenTrackButtonClicked(View view) {
        Intent intent = new Intent(this, ScreenTrackingActivity.class);
        startActivity(intent);
    }

    private void backgroundButtonClicked(View view) {
        Timer backgroundTimer = new Timer("Background Timer", "background traffic").start();

        try {
            Thread.sleep(500);
            backgroundTimer.interactive();
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        backgroundTimer.end().submit();
    }

    private void trackCatchExceptionButtonClicked(View view) {
        try {
            Objects.requireNonNull(Tracker.getInstance()).raiseTestException();
        } catch (Throwable e) {
            Objects.requireNonNull(Tracker.getInstance()).trackException("A test exception caught!", null, e, Tracker.BTErrorType.NativeAppCrash);
        }
    }

    private void crashButtonClicked(View view) {
        Objects.requireNonNull(Tracker.getInstance()).raiseTestException();
        //new NativeWrapper().testCrash();
    }

    private void launchAnrActivity(ANRTestScenario anrTestScenario, ANRTest anrTest) {
        Intent intent = new Intent(this, ANRTestActivity.class);
        intent.putExtra(ANRTestActivity.TestScenario, anrTestScenario);
        intent.putExtra(ANRTestActivity.Test, anrTest);
        startActivity(intent);
        //new NativeWrapper().testANR();
    }


    private void captureNetworkRequests(View view) {
        Timer timer = new Timer("Test Network Capture", "Android Traffic").start();
        Request imageRequest =
                new Request.Builder().url("https://www.httpbin.org/image/jpeg").build();

        okHttpClient.newCall(imageRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.d(TAG, "onFailure: " + call.request());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.d(TAG, "onResponse: " + call.request());
            }
        });

        Request jsonRequest = new Request.Builder().url("https://www.httpbin.org/json").build();
        okHttpClient.newCall(jsonRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.d(TAG, "onFailure: " + call.request());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.d(TAG, "onResponse: " + call.request());
                timer.end().submit();

                RequestBody body = new FormBody.Builder().add("test", "value").build();
                Request postRequest = new Request.Builder().url("https://httpbin.org/post").method("POST", body).build();
                okHttpClient.newCall(postRequest).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.d(TAG, "onFailure: " + call.request());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        Log.d(TAG, "onResponse: " + call.request());
                        new Timer("Test Network Capture 2", "Android Traffic").start().end().submit();
                    }
                });
            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        DemoApplication.Companion.checkLaunchTest(LaunchTestScenario.OnActivityStart);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DemoApplication.Companion.checkLaunchTest(LaunchTestScenario.OnActivityResume);
    }
}