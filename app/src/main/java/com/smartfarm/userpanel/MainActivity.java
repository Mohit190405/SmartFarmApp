package com.smartfarm.userpanel;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.mikhaellopez.circularprogressbar.CircularProgressBar;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SELECTED_TIME = "selectedTime";
    private static final String KEY_WATER_AMOUNT = "waterAmount";

    private EditText etWaterAmount;
    private Button btnSend, btnSetTime;
    private String selectedTime = "";
    private String previousWaterAmount = "";
    private String previousWaterTime = "";
    private boolean hasPreviousDetails = false;
    private boolean isDemoMode = false;
    private DatabaseReference databaseReference;

    private Timer timer;
    private double outsideTempMin, outsideTempMax, roomTempMin, roomTempMax;
    private double outsideHumidityMin, outsideHumidityMax, roomHumidityMin, roomHumidityMax;
    private Random random = new Random();
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        databaseReference = FirebaseDatabase.getInstance().getReference();

        initializeViews();
        restoreInstanceState(savedInstanceState);
        setUpListeners();
        fetchDataFromFirebase();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_SELECTED_TIME, selectedTime);
        outState.putString(KEY_WATER_AMOUNT, etWaterAmount.getText().toString());
    }

    private void initializeViews() {
        etWaterAmount = findViewById(R.id.etWaterAmount);
        btnSend = findViewById(R.id.btnSend);
        btnSetTime = findViewById(R.id.btnSetTime);
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            selectedTime = savedInstanceState.getString(KEY_SELECTED_TIME, "");
            String waterAmount = savedInstanceState.getString(KEY_WATER_AMOUNT, "");
            etWaterAmount.setText(waterAmount);
        }
    }

    private void setUpListeners() {
        btnSetTime.setOnClickListener(v -> showTimePicker());
        btnSend.setOnClickListener(v -> validateAndSend());
        setupUiElementListeners();
    }

    private void validateAndSend() {
        String waterAmount = etWaterAmount.getText().toString();

        if (TextUtils.isEmpty(waterAmount)) {
            showMessage(getString(R.string.error_empty_water_amount));
        } else if (!TextUtils.isDigitsOnly(waterAmount) || Integer.parseInt(waterAmount) <= 0) {
            showMessage(getString(R.string.error_invalid_water_amount));
        } else if (TextUtils.isEmpty(selectedTime)) {
            showMessage(getString(R.string.error_no_time_set));
        } else {
            showConfirmationDialog(waterAmount, selectedTime);
        }
    }

    private void showConfirmationDialog(String waterAmount, String time) {
        String message;

        if (hasPreviousDetails) {
            message = String.format(getString(R.string.confirm_update_message),
                    previousWaterAmount, previousWaterTime, waterAmount, time);
        } else {
            message = String.format(getString(R.string.confirm_new_set_message), waterAmount, time);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_update_title))
                .setMessage(message)
                .setPositiveButton("Yes", (dialog, which) -> submitData(waterAmount, time))
                .setNegativeButton("No", null)
                .show();
    }

    private void submitData(String waterAmount, String time) {
        showLoadingIndicator();
        DatabaseReference waterRef = databaseReference.child("watering");
        waterRef.child("amount").setValue(waterAmount).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                waterRef.child("time").setValue(time).addOnCompleteListener(submitTask -> {
                    hideLoadingIndicator();
                    if (submitTask.isSuccessful()) {
                        showMessage(getString(R.string.data_submitted));
                    } else {
                        handleFirebaseError(submitTask.getException());
                    }
                });
            } else {
                hideLoadingIndicator();
                handleFirebaseError(task.getException());
            }
        });
    }

    private void showLoadingIndicator() {
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.loading_submission));
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private void hideLoadingIndicator() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(12)
                .setMinute(0)
                .setTitleText(getString(R.string.time_picker_title))
                .build();

        picker.show(getSupportFragmentManager(), "TAG");

        picker.addOnPositiveButtonClickListener(view -> {
            int selectedHour = picker.getHour();
            int selectedMinute = picker.getMinute();
            selectedTime = String.format("%02d:%02d", selectedHour, selectedMinute);
            showMessage(getString(R.string.info_time_set, selectedTime));
        });

        picker.addOnNegativeButtonClickListener(view -> showMessage(getString(R.string.info_time_cancelled)));
    }

    private void setupUiElementListeners() {
        setUpCardViewListener(R.id.cctvCard, getString(R.string.info_cctv_not_ready));
        setUpTextViewListener(R.id.tvOutTemp, getString(R.string.info_out_temp));
        setUpTextViewListener(R.id.tvRoomTemp, getString(R.string.info_room_temp));
        setUpTextViewListener(R.id.tvOutHumidity, getString(R.string.info_out_humidity));
        setUpTextViewListener(R.id.tvRoomHumidity, getString(R.string.info_room_humidity));
        setUpProgressBarListener(R.id.cpbOutTemp, getString(R.string.info_out_temp_progress));
        setUpProgressBarListener(R.id.cpbOutHumidity, getString(R.string.info_out_humidity_progress));
        setUpProgressBarListener(R.id.cpbRoomTemp, getString(R.string.info_room_temp_progress));
        setUpProgressBarListener(R.id.cpbRoomHumidity, getString(R.string.info_room_humidity_progress));
    }

    private void setUpCardViewListener(int cardViewId, String message) {
        CardView cardView = findViewById(cardViewId);
        cardView.setOnClickListener(v -> showMessage(message));
    }

    private void setUpTextViewListener(int textViewId, String message) {
        TextView textView = findViewById(textViewId);
        textView.setOnClickListener(v -> showMessage(message));
    }

    private void setUpProgressBarListener(int progressBarId, String message) {
        CircularProgressBar progressBar = findViewById(progressBarId);
        progressBar.setOnClickListener(v -> showMessage(message));
    }

    private void showMessage(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void fetchDataFromFirebase() {
        databaseReference.child("settings").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    String demoModeStr = dataSnapshot.child("demoMode").getValue(String.class);
                    isDemoMode = "on".equals(demoModeStr);

                    if (isDemoMode) {
                        DataSnapshot tempFluctuation = dataSnapshot.child("tempFluctuationRange");
                        outsideTempMin = tempFluctuation.child("outsideTemperature").child("min").getValue(Double.class);
                        outsideTempMax = tempFluctuation.child("outsideTemperature").child("max").getValue(Double.class);
                        roomTempMin = tempFluctuation.child("roomTemperature").child("min").getValue(Double.class);
                        roomTempMax = tempFluctuation.child("roomTemperature").child("max").getValue(Double.class);

                        DataSnapshot humidityFluctuation = dataSnapshot.child("humidityFluctuationRange");
                        outsideHumidityMin = humidityFluctuation.child("outsideHumidity").child("min").getValue(Double.class);
                        outsideHumidityMax = humidityFluctuation.child("outsideHumidity").child("max").getValue(Double.class);
                        roomHumidityMin = humidityFluctuation.child("roomHumidity").child("min").getValue(Double.class);
                        roomHumidityMax = humidityFluctuation.child("roomHumidity").child("max").getValue(Double.class);

                        startDemoModeSimulation();
                    } else {
                        updateRealData();
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                handleFirebaseError(databaseError.toException());
            }
        });
    }

    private void startDemoModeSimulation() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    double outsideTemp = generateRandomValue(outsideTempMin, outsideTempMax);
                    double roomTemp = generateRandomValue(roomTempMin, roomTempMax);
                    double outsideHumidity = generateRandomValue(outsideHumidityMin, outsideHumidityMax);
                    double roomHumidity = generateRandomValue(roomHumidityMin, roomHumidityMax);

                    // Update UI elements
                    ((TextView) findViewById(R.id.tvOutTemp)).setText(String.format("%.1f °C", outsideTemp));
                    ((TextView) findViewById(R.id.tvRoomTemp)).setText(String.format("%.1f °C", roomTemp));
                    ((TextView) findViewById(R.id.tvOutHumidity)).setText(String.format("%.1f %%", outsideHumidity));
                    ((TextView) findViewById(R.id.tvRoomHumidity)).setText(String.format("%.1f %%", roomHumidity));

                    // Update progress bars
                    ((CircularProgressBar) findViewById(R.id.cpbOutTemp)).setProgress((float) outsideTemp);
                    ((CircularProgressBar) findViewById(R.id.cpbRoomTemp)).setProgress((float) roomTemp);
                    ((CircularProgressBar) findViewById(R.id.cpbOutHumidity)).setProgress((float) outsideHumidity);
                    ((CircularProgressBar) findViewById(R.id.cpbRoomHumidity)).setProgress((float) roomHumidity);
                });
            }
        }, 0, 5000); // Update every 5 seconds
    }

    private double generateRandomValue(double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    private void updateRealData() {
        // Code to fetch real data from Firebase (not provided in original code)
        // This would typically involve setting up listeners to update the UI based on real-time data.
    }

    private void handleFirebaseError(Exception e) {
        Log.e("FirebaseError", "Error: ", e);
        showMessage(getString(R.string.error_firebase_operation));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
        }
    }
}
