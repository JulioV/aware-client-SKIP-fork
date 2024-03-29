package com.aware;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.SQLException;
import android.database.sqlite.SQLiteException;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.aware.providers.Locations_Provider;
import com.aware.providers.Locations_Provider.Locations_Data;
import com.aware.ui.PermissionsHandler;
import com.aware.utils.Aware_Sensor;

/**
 * Location service for Aware framework
 * Provides mobile device network triangulation and GPS location
 *
 * @author denzil
 */
public class Locations extends Aware_Sensor implements LocationListener {

    private static LocationManager locationManager = null;

    /**
     * This listener will keep track for failed GPS location requests
     * TODO: extend to log satellite information
     */
    private final GpsStatus.Listener gps_status_listener = new GpsStatus.Listener() {
        @Override
        public void onGpsStatusChanged(int event) {
            switch (event) {
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    break;
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    break;
                case GpsStatus.GPS_EVENT_STARTED:
                    break;
                case GpsStatus.GPS_EVENT_STOPPED:
                    //Save best location, could be GPS or network
                    //This covers the case when the GPS stopped and we did not get a location fix.
                    Location lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

                    Location lastNetwork = null;
                    //Do a quick check on the network provider
                    if (locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null) {
                        locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, Locations.this, getMainLooper());
                        lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }

                    Location bestLocation;
                    if (isBetterLocation(lastNetwork, lastGPS)) {
                        bestLocation = lastNetwork;
                    } else {
                        bestLocation = lastGPS;
                    }

                    if (bestLocation != null) {
                        ContentValues rowData = new ContentValues();
                        rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
                        rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                        rowData.put(Locations_Data.LATITUDE, bestLocation.getLatitude());
                        rowData.put(Locations_Data.LONGITUDE, bestLocation.getLongitude());
                        rowData.put(Locations_Data.BEARING, bestLocation.getBearing());
                        rowData.put(Locations_Data.SPEED, bestLocation.getSpeed());
                        rowData.put(Locations_Data.ALTITUDE, bestLocation.getAltitude());
                        rowData.put(Locations_Data.PROVIDER, bestLocation.getProvider());
                        rowData.put(Locations_Data.ACCURACY, bestLocation.getAccuracy());

                        try {
                            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
                        } catch (SQLException e) {
                            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
                        }

                        Intent locationEvent = new Intent(ACTION_AWARE_LOCATIONS);
                        sendBroadcast(locationEvent);
                    }
                    break;
            }
        }
    };

    /**
     * Broadcasted event: New location available
     */
    public static final String ACTION_AWARE_LOCATIONS = "ACTION_AWARE_LOCATIONS";

    /**
     * Broadcasted event: GPS location is active
     */
    public static final String ACTION_AWARE_GPS_LOCATION_ENABLED = "ACTION_AWARE_GPS_LOCATION_ENABLED";

    /**
     * Broadcasted event: Network location is active
     */
    public static final String ACTION_AWARE_NETWORK_LOCATION_ENABLED = "ACTION_AWARE_NETWORK_LOCATION_ENABLED";

    /**
     * Broadcasted event: GPS location disabled
     */
    public static final String ACTION_AWARE_GPS_LOCATION_DISABLED = "ACTION_AWARE_GPS_LOCATION_DISABLED";

    /**
     * Broadcasted event: Network location disabled
     */
    public static final String ACTION_AWARE_NETWORK_LOCATION_DISABLED = "ACTION_AWARE_NETWORK_LOCATION_DISABLED";

    private static Locations locationSrv = Locations.getService();

    private static int FREQUENCY_NETWORK = -1;
    private static int FREQUENCY_GPS = -1;

    /**
     * Singleton instance of Locations service
     *
     * @return Locations obj
     */
    public static Locations getService() {
        if (locationSrv == null) locationSrv = new Locations();
        return locationSrv;
    }

    /**
     * Service binder
     */
    private LocationBinder locationBinder = new LocationBinder();

    public class LocationBinder extends Binder {
        public Locations getService() {
            return Locations.getService();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return locationBinder;
    }

    /**
     * Determines whether one Location reading is better than the current Location fix
     *
     * @param newLocation  The new Location that you want to evaluate
     * @param lastLocation The last location fix, to which you want to compare the new one
     */
    private boolean isBetterLocation(Location newLocation, Location lastLocation) {
        if (newLocation == null && lastLocation == null) return false;

        if (newLocation != null && lastLocation == null) {
            return true;
        }

        if (lastLocation != null && newLocation == null) {
            return false;
        }

        long timeDelta = newLocation.getTime() - lastLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > 1000 * Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.LOCATION_EXPIRATION_TIME));
        boolean isSignificantlyOlder = timeDelta < -(1000 * Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.LOCATION_EXPIRATION_TIME)));
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) {
            return true;
        } else if (isSignificantlyOlder) {
            return false;
        }

        int accuracyDelta = (int) (newLocation.getAccuracy() - lastLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;
        boolean isFromSameProvider = isSameProvider(newLocation.getProvider(), lastLocation.getProvider());

        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /**
     * Checks whether two providers are the same
     */
    private static boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        DATABASE_TABLES = Locations_Provider.DATABASE_TABLES;
        TABLES_FIELDS = Locations_Provider.TABLES_FIELDS;
        CONTEXT_URIS = new Uri[]{Locations_Data.CONTENT_URI};

        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        REQUIRED_PERMISSIONS.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Aware.DEBUG) Log.d(TAG, "Location sensor is created!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        locationManager.removeUpdates(this);
        locationManager.removeGpsStatusListener(gps_status_listener);

        if (Aware.DEBUG) Log.d(TAG, "Locations service terminated...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        boolean permissions_ok = true;
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                permissions_ok = false;
                break;
            }
        }

        if (permissions_ok) {

            DEBUG = Aware.getSetting(this, Aware_Preferences.DEBUG_FLAG).equals("true");

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_GPS).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_GPS, 180);
            }
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_GPS_ACCURACY).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_GPS_ACCURACY, 150);
            }

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_NETWORK).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_NETWORK, 300);
            }
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_NETWORK_ACCURACY).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_NETWORK_ACCURACY, 1500);
            }
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.LOCATION_EXPIRATION_TIME).length() == 0) {
                Aware.setSetting(getApplicationContext(), Aware_Preferences.LOCATION_EXPIRATION_TIME, 300);
            }

            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_LOCATION_GPS).equals("true")) {
                if (FREQUENCY_GPS != Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_GPS))) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_GPS)) * 1000,
                            Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_GPS_ACCURACY)), this);
                    locationManager.removeGpsStatusListener(gps_status_listener);
                    locationManager.addGpsStatusListener(gps_status_listener);

                    FREQUENCY_GPS = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_GPS));
                }
                if (Aware.DEBUG) Log.d(TAG, "Location tracking with GPS is active: " + FREQUENCY_GPS + "s");
            }
            if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_LOCATION_NETWORK).equals("true")) {
                if (FREQUENCY_NETWORK != Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_NETWORK))) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_NETWORK)) * 1000,
                            Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.MIN_LOCATION_NETWORK_ACCURACY)), this);

                    FREQUENCY_NETWORK = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.FREQUENCY_LOCATION_NETWORK));
                }
                if (Aware.DEBUG) Log.d(TAG, "Location tracking with Network is active: " + FREQUENCY_NETWORK + "s");
            }

        } else {
            Intent permissions = new Intent(this, PermissionsHandler.class);
            permissions.putExtra(PermissionsHandler.EXTRA_REQUIRED_PERMISSIONS, REQUIRED_PERMISSIONS);
            permissions.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissions);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onLocationChanged(Location newLocation) {
        Location bestLocation;

        //If we have both GPS and Network active, check if we got a better location. Otherwise always keep the latest.
        if (Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_LOCATION_GPS).equals("true") && Aware.getSetting(getApplicationContext(), Aware_Preferences.STATUS_LOCATION_NETWORK).equals("true")) {
            Location lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (isBetterLocation(lastNetwork, lastGPS)) {
                if (isBetterLocation(newLocation, lastNetwork)) {
                    bestLocation = newLocation;
                } else {
                    bestLocation = lastNetwork;
                }
            } else {
                if (isBetterLocation(newLocation, lastGPS)) {
                    bestLocation = newLocation;
                } else {
                    bestLocation = lastGPS;
                }
            }
        } else {
            bestLocation = newLocation;
        }

        ContentValues rowData = new ContentValues();
        rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
        rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
        rowData.put(Locations_Data.LATITUDE, bestLocation.getLatitude());
        rowData.put(Locations_Data.LONGITUDE, bestLocation.getLongitude());
        rowData.put(Locations_Data.BEARING, bestLocation.getBearing());
        rowData.put(Locations_Data.SPEED, bestLocation.getSpeed());
        rowData.put(Locations_Data.ALTITUDE, bestLocation.getAltitude());
        rowData.put(Locations_Data.PROVIDER, bestLocation.getProvider());
        rowData.put(Locations_Data.ACCURACY, bestLocation.getAccuracy());

        try {
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        } catch (SQLiteException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        } catch (SQLException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        }

        Intent locationEvent = new Intent(ACTION_AWARE_LOCATIONS);
        sendBroadcast(locationEvent);
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_GPS_LOCATION_DISABLED);
            Intent gps = new Intent(ACTION_AWARE_GPS_LOCATION_DISABLED);
            sendBroadcast(gps);

            ContentValues rowData = new ContentValues();
            rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Locations_Data.PROVIDER, LocationManager.GPS_PROVIDER);
            rowData.put(Locations_Data.LABEL, "disabled");
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        }
        if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_NETWORK_LOCATION_DISABLED);
            Intent network = new Intent(ACTION_AWARE_NETWORK_LOCATION_DISABLED);
            sendBroadcast(network);

            ContentValues rowData = new ContentValues();
            rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Locations_Data.PROVIDER, LocationManager.NETWORK_PROVIDER);
            rowData.put(Locations_Data.LABEL, "disabled");
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_GPS_LOCATION_ENABLED);
            Intent gps = new Intent(ACTION_AWARE_GPS_LOCATION_ENABLED);
            sendBroadcast(gps);

            ContentValues rowData = new ContentValues();
            rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Locations_Data.PROVIDER, LocationManager.GPS_PROVIDER);
            rowData.put(Locations_Data.LABEL, "enabled");
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        }
        if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
            if (Aware.DEBUG) Log.d(TAG, ACTION_AWARE_NETWORK_LOCATION_ENABLED);
            Intent network = new Intent(ACTION_AWARE_NETWORK_LOCATION_ENABLED);
            sendBroadcast(network);

            ContentValues rowData = new ContentValues();
            rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
            rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
            rowData.put(Locations_Data.PROVIDER, LocationManager.NETWORK_PROVIDER);
            rowData.put(Locations_Data.LABEL, "enabled");
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (Aware.DEBUG)
            Log.d(TAG, "onStatusChanged: " + provider + " Status:" + status + " Extras:" + extras.toString());

        //Save best location, could be GPS or network
        //This covers the case when the GPS stopped and we did not get a location fix.
        Location lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        Location lastNetwork = null;
        //Do a quick check on the network provider
        if (locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null) {
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, Locations.this, getMainLooper());
            lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        Location bestLocation;
        if (isBetterLocation(lastNetwork, lastGPS)) {
            bestLocation = lastNetwork;
        } else {
            bestLocation = lastGPS;
        }

        ContentValues rowData = new ContentValues();
        rowData.put(Locations_Data.TIMESTAMP, System.currentTimeMillis());
        rowData.put(Locations_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
        rowData.put(Locations_Data.LATITUDE, bestLocation.getLatitude());
        rowData.put(Locations_Data.LONGITUDE, bestLocation.getLongitude());
        rowData.put(Locations_Data.BEARING, bestLocation.getBearing());
        rowData.put(Locations_Data.SPEED, bestLocation.getSpeed());
        rowData.put(Locations_Data.ALTITUDE, bestLocation.getAltitude());
        rowData.put(Locations_Data.PROVIDER, bestLocation.getProvider());
        rowData.put(Locations_Data.ACCURACY, bestLocation.getAccuracy());

        try {
            getContentResolver().insert(Locations_Data.CONTENT_URI, rowData);
        } catch (SQLiteException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        } catch (SQLException e) {
            if (Aware.DEBUG) Log.d(TAG, e.getMessage());
        }

        Intent locationEvent = new Intent(ACTION_AWARE_LOCATIONS);
        sendBroadcast(locationEvent);
    }
}