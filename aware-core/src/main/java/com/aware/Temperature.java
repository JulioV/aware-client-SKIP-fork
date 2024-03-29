
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

import com.aware.providers.Rotation_Provider;
import com.aware.providers.Temperature_Provider;
import com.aware.providers.Temperature_Provider.Temperature_Data;
import com.aware.providers.Temperature_Provider.Temperature_Sensor;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.Aware_Sensor;
import com.aware.utils.Converters;

import java.util.ArrayList;
import java.util.List;

/**
 * AWARE Temperature module
 * - Temperature raw data in ambient room temperature in degree Celsius
 * - Temperature sensor information
 *
 * @author df
 */
public class Temperature extends Aware_Sensor implements SensorEventListener {

    /**
     * Logging tag (default = "AWARE::Temperature")
     */
    private static String TAG = "AWARE::Temperature";

    private static SensorManager mSensorManager;
    private static Sensor mTemperature;
    private static HandlerThread sensorThread = null;
    private static Handler sensorHandler = null;
    private static PowerManager powerManager = null;
    private static PowerManager.WakeLock wakeLock = null;
    private static int FIFO_SIZE = 0;
    private static int FREQUENCY = -1;

    /**
     * Broadcasted event: new sensor values
     * ContentProvider: Temperature_Provider
     */
    public static final String ACTION_AWARE_TEMPERATURE = "ACTION_AWARE_TEMPERATURE";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_SENSOR = "sensor";

    public static final String ACTION_AWARE_TEMPERATURE_LABEL = "ACTION_AWARE_TEMPERATURE_LABEL";
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
            if (intent.getAction().equals(ACTION_AWARE_TEMPERATURE_LABEL)) {
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
        rowData.put(Temperature_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
        rowData.put(Temperature_Data.TIMESTAMP, System.currentTimeMillis());
        rowData.put(Temperature_Data.TEMPERATURE_CELSIUS, event.values[0]);
        rowData.put(Temperature_Data.ACCURACY, event.accuracy);
        rowData.put(Temperature_Data.LABEL, LABEL);

        if (data_values.size() < 250) {
            data_values.add(rowData);

            Intent temperatureData = new Intent(ACTION_AWARE_TEMPERATURE);
            temperatureData.putExtra(EXTRA_DATA, rowData);
            sendBroadcast(temperatureData);

            if (Aware.DEBUG) Log.d(TAG, "Temperature:" + rowData.toString());

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
    }

    /**
     * Database I/O on different thread
     */
    private class AsyncStore extends AsyncTask<ContentValues[], Void, Void> {
        @Override
        protected Void doInBackground(ContentValues[]... data) {
            getContentResolver().bulkInsert(Temperature_Data.CONTENT_URI, data[0]);
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
        String[] columns = new String[]{"count(*) as frequency", "datetime(" + Temperature_Data.TIMESTAMP + "/1000, 'unixepoch','localtime') as sample_time"};
        Cursor qry = context.getContentResolver().query(Temperature_Data.CONTENT_URI, columns, "1) group by (sample_time", null, "sample_time DESC LIMIT 1 OFFSET 2");
        if (qry != null && qry.moveToFirst()) {
            hz = qry.getInt(0);
        }
        if (qry != null && !qry.isClosed()) qry.close();
        return hz;
    }

    private void saveSensorDevice(Sensor sensor) {
        Cursor sensorInfo = getContentResolver().query(Temperature_Sensor.CONTENT_URI, null, null, null, null);
        if (sensorInfo == null || !sensorInfo.moveToFirst()) {
            ContentValues rowData = new ContentValues();
            rowData.put(Temperature_Sensor.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Temperature_Sensor.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Temperature_Sensor.MAXIMUM_RANGE, sensor.getMaximumRange());
            rowData.put(Temperature_Sensor.MINIMUM_DELAY, sensor.getMinDelay());
            rowData.put(Temperature_Sensor.NAME, sensor.getName());
            rowData.put(Temperature_Sensor.POWER_MA, sensor.getPower());
            rowData.put(Temperature_Sensor.RESOLUTION, sensor.getResolution());
            rowData.put(Temperature_Sensor.TYPE, sensor.getType());
            rowData.put(Temperature_Sensor.VENDOR, sensor.getVendor());
            rowData.put(Temperature_Sensor.VERSION, sensor.getVersion());

            getContentResolver().insert(Temperature_Sensor.CONTENT_URI, rowData);
            if (Aware.DEBUG) Log.d(TAG, "Temperature sensor info: " + rowData.toString());

            Intent temp = new Intent(ACTION_AWARE_TEMPERATURE);
            temp.putExtra(EXTRA_SENSOR, rowData);
            sendBroadcast(temp);
        }
        if (sensorInfo != null && !sensorInfo.isClosed()) sensorInfo.close();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate() {
        super.onCreate();

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        sensorThread = new HandlerThread(TAG);
        sensorThread.start();

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        sensorHandler = new Handler(sensorThread.getLooper());

        DATABASE_TABLES = Temperature_Provider.DATABASE_TABLES;
        TABLES_FIELDS = Temperature_Provider.TABLES_FIELDS;
        CONTEXT_URIS = new Uri[]{Temperature_Sensor.CONTENT_URI, Temperature_Data.CONTENT_URI};

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AWARE_TEMPERATURE_LABEL);
        registerReceiver(dataLabeler, filter);

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.HONEYCOMB) {
            mTemperature = mSensorManager.getDefaultSensor(Sensor.TYPE_TEMPERATURE);
        } else {
            mTemperature = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        }

        if (Aware.DEBUG) Log.d(TAG, "Temperature service created!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        sensorHandler.removeCallbacksAndMessages(null);
        mSensorManager.unregisterListener(this, mTemperature);
        sensorThread.quit();

        wakeLock.release();

        unregisterReceiver(dataLabeler);

        if (Aware.DEBUG) Log.d(TAG, "Temperature service terminated...");
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
            if (mTemperature == null) {
                if (DEBUG) Log.d(TAG, "This device does not have a temperature sensor.");
                Aware.setSetting(this, Aware_Preferences.STATUS_TEMPERATURE, false);
                stopSelf();
            } else {
                DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");
                Aware.setSetting(this, Aware_Preferences.STATUS_TEMPERATURE, true);

                FIFO_SIZE = mTemperature.getFifoReservedEventCount();
                saveSensorDevice(mTemperature);

                if (Aware.getSetting(this, Aware_Preferences.FREQUENCY_TEMPERATURE).length() == 0) {
                    Aware.setSetting(this, Aware_Preferences.FREQUENCY_TEMPERATURE, 200000);
                }

                if (FREQUENCY != Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_TEMPERATURE))) {
                    sensorHandler.removeCallbacksAndMessages(null);
                    mSensorManager.unregisterListener(this, mTemperature);
                    mSensorManager.registerListener(this, mTemperature, Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_TEMPERATURE)), FIFO_SIZE, sensorHandler);

                    FREQUENCY = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_TEMPERATURE));
                }

                if (Aware.DEBUG) Log.d(TAG, "Temperature service active...");
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
    private static Temperature temperatureSrv = Temperature.getService();

    /**
     * Get singleton instance to service
     *
     * @return Temperature obj
     */
    public static Temperature getService() {
        if (temperatureSrv == null) temperatureSrv = new Temperature();
        return temperatureSrv;
    }

    private final IBinder serviceBinder = new ServiceBinder();

    public class ServiceBinder extends Binder {
        Temperature getService() {
            return Temperature.getService();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }
}