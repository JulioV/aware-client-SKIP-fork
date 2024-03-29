
package com.aware;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteException;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.aware.providers.Barometer_Provider;
import com.aware.providers.Barometer_Provider.Barometer_Data;
import com.aware.providers.Barometer_Provider.Barometer_Sensor;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.Aware_Sensor;

import java.util.ArrayList;
import java.util.List;

/**
 * AWARE Barometer module
 * - Ambient pressure raw data, in mbar
 * - Ambient pressure sensor information
 *
 * @author df
 */
public class Barometer extends Aware_Sensor implements SensorEventListener {

    public static String TAG = "AWARE::Barometer";

    private static SensorManager mSensorManager;
    private static Sensor mPressure;
    private static HandlerThread sensorThread = null;
    private static Handler sensorHandler = null;
    private static PowerManager powerManager = null;
    private static PowerManager.WakeLock wakeLock = null;
    private static int FIFO_SIZE = 0;

    private static int FREQUENCY = -1;

    /**
     * Broadcasted event: new sensor values
     * ContentProvider: PressureProvider
     */
    public static final String ACTION_AWARE_BAROMETER = "ACTION_AWARE_BAROMETER";
    public static final String EXTRA_SENSOR = "sensor";
    public static final String EXTRA_DATA = "data";

    public static final String ACTION_AWARE_BAROMETER_LABEL = "ACTION_AWARE_BAROMETER_LABEL";
    public static final String EXTRA_LABEL = "label";

    /**
     * Until today, no available Android phone samples higher than 208Hz (Nexus 7).
     * http://ilessendata.blogspot.com/2012/11/android-accelerometer-sampling-rates.html
     */
    private List<ContentValues> data_values = new ArrayList<ContentValues>();

    private static String LABEL = "";

    private static DataLabel dataLabeler = new DataLabel();

    public static class DataLabel extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_AWARE_BAROMETER_LABEL)) {
                LABEL = intent.getStringExtra(EXTRA_LABEL);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //We log current accuracy on the sensor changed event
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        ContentValues rowData = new ContentValues();
        rowData.put(Barometer_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
        rowData.put(Barometer_Data.TIMESTAMP, System.currentTimeMillis());
        rowData.put(Barometer_Data.AMBIENT_PRESSURE, event.values[0]);
        rowData.put(Barometer_Data.ACCURACY, event.accuracy);
        rowData.put(Barometer_Data.LABEL, LABEL);

        if (data_values.size() < 250) {
            data_values.add(rowData);

            Intent pressureData = new Intent(ACTION_AWARE_BAROMETER);
            pressureData.putExtra(EXTRA_DATA, rowData);
            sendBroadcast(pressureData);

            if (Aware.DEBUG) Log.d(TAG, "Barometer:" + rowData.toString());

            return;
        }

        ContentValues[] data_buffer = new ContentValues[data_values.size()];
        data_values.toArray(data_buffer);

        try {
            if (!Aware.getSetting(getApplicationContext(), Aware_Preferences.DEBUG_DB_SLOW).equals("true")) {
                new AsyncStore().execute(data_buffer);
            }
        } catch (SQLiteException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        } catch (SQLException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        }
        data_values.clear();
    }

    /**
     * Database I/O on different thread
     */
    private class AsyncStore extends AsyncTask<ContentValues[], Void, Void> {
        @Override
        protected Void doInBackground(ContentValues[]... data) {
            getContentResolver().bulkInsert(Barometer_Data.CONTENT_URI, data[0]);
            return null;
        }
    }

    /**
     * Calculates the sampling rate in Hz (i.e., how many samples did we collect in the past second)
     *
     * @param context
     * @return hz
     */
    public static int getFrequency(Context context) {
        int hz = 0;
        String[] columns = new String[]{"count(*) as frequency", "datetime(" + Barometer_Data.TIMESTAMP + "/1000, 'unixepoch','localtime') as sample_time"};
        Cursor qry = context.getContentResolver().query(Barometer_Data.CONTENT_URI, columns, "1) group by (sample_time", null, "sample_time DESC LIMIT 1 OFFSET 2");
        if (qry != null && qry.moveToFirst()) {
            hz = qry.getInt(0);
        }
        if (qry != null && !qry.isClosed()) qry.close();
        return hz;
    }

    private void saveSensorDevice(Sensor sensor) {
        Cursor sensorInfo = getContentResolver().query(Barometer_Sensor.CONTENT_URI, null, null, null, null);
        if (sensorInfo == null || !sensorInfo.moveToFirst()) {
            ContentValues rowData = new ContentValues();
            rowData.put(Barometer_Sensor.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Barometer_Sensor.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Barometer_Sensor.MAXIMUM_RANGE, sensor.getMaximumRange());
            rowData.put(Barometer_Sensor.MINIMUM_DELAY, sensor.getMinDelay());
            rowData.put(Barometer_Sensor.NAME, sensor.getName());
            rowData.put(Barometer_Sensor.POWER_MA, sensor.getPower());
            rowData.put(Barometer_Sensor.RESOLUTION, sensor.getResolution());
            rowData.put(Barometer_Sensor.TYPE, sensor.getType());
            rowData.put(Barometer_Sensor.VENDOR, sensor.getVendor());
            rowData.put(Barometer_Sensor.VERSION, sensor.getVersion());

            getContentResolver().insert(Barometer_Sensor.CONTENT_URI, rowData);

            Intent pressureDev = new Intent(ACTION_AWARE_BAROMETER);
            pressureDev.putExtra(EXTRA_SENSOR, rowData);
            sendBroadcast(pressureDev);

            if (Aware.DEBUG) Log.d(TAG, "Barometer sensor info: " + rowData.toString());
        }
        if (sensorInfo != null && !sensorInfo.isClosed()) sensorInfo.close();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        FIFO_SIZE = mPressure.getFifoReservedEventCount();

        sensorThread = new HandlerThread(TAG);
        sensorThread.start();

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        sensorHandler = new Handler(sensorThread.getLooper());

        DATABASE_TABLES = Barometer_Provider.DATABASE_TABLES;
        TABLES_FIELDS = Barometer_Provider.TABLES_FIELDS;
        CONTEXT_URIS = new Uri[]{Barometer_Sensor.CONTENT_URI, Barometer_Data.CONTENT_URI};

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AWARE_BAROMETER_LABEL);
        registerReceiver(dataLabeler, filter);

        if (Aware.DEBUG) Log.d(TAG, "Barometer service created!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        sensorHandler.removeCallbacksAndMessages(null);
        mSensorManager.unregisterListener(this, mPressure);
        sensorThread.quit();

        wakeLock.release();

        unregisterReceiver(dataLabeler);

        if (Aware.DEBUG) Log.d(TAG, "Barometer service terminated...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        boolean permissions_ok = true;
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                permissions_ok = false;
                break;
            }
        }

        if (permissions_ok) {
            if (mPressure == null) {
                if (Aware.DEBUG) Log.w(TAG, "This device does not have a barometer sensor!");
                Aware.setSetting(this, Aware_Preferences.STATUS_BAROMETER, false);
                stopSelf();
            } else {

                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

                Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_BAROMETER, true);
                saveSensorDevice(mPressure);

                if (Aware.getSetting(this, Aware_Preferences.FREQUENCY_BAROMETER).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.FREQUENCY_BAROMETER, 200000);
                }

                if (FREQUENCY != Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_BAROMETER))) {
                    sensorHandler.removeCallbacksAndMessages(null);
                    mSensorManager.unregisterListener(this, mPressure);
                    mSensorManager.registerListener(this, mPressure, Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_BAROMETER)), FIFO_SIZE, sensorHandler);

                    FREQUENCY = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_BAROMETER));
                }

                if (Aware.DEBUG) Log.d(TAG, "Barometer service active: " + FREQUENCY + "ms");
            }
        } else {
            Intent permissions = new Intent(this, PermissionsHandler.class);
            permissions.putExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, REQUIRED_PERMISSIONS);
            permissions.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissions);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    //Singleton instance of this service
    private static Barometer pressureSrv = Barometer.getService();

    /**
     * Get singleton instance to service
     *
     * @return Pressure obj
     */
    public static Barometer getService() {
        if (pressureSrv == null) pressureSrv = new Barometer();
        return pressureSrv;
    }

    private final IBinder serviceBinder = new ServiceBinder();

    public class ServiceBinder extends Binder {
        Barometer getService() {
            return Barometer.getService();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }
}