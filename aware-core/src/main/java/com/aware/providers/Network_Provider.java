
package com.aware.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.provider.BaseColumns;
import android.util.Log;

import com.aware.Aware;
import com.aware.BuildConfig;
import com.aware.utils.DatabaseHelper;

import java.io.File;
import java.util.HashMap;

/**
 * AWARE Network Content Provider Allows you to access all the recorded network
 * events on the database Database is located at the SDCard : /AWARE/network.db
 * 
 * @author denzil
 * 
 */
public class Network_Provider extends ContentProvider {

	public static final int DATABASE_VERSION = 3;

	/**
	 * Authority of Screen content provider
	 */
	public static String AUTHORITY = "com.aware.provider.network";

	// ContentProvider query paths
	private static final int NETWORK = 1;
	private static final int NETWORK_ID = 2;

	/**
	 * Network content representation
	 * 
	 * @author denzil
	 * 
	 */
	public static final class Network_Data implements BaseColumns {
		private Network_Data() {
		}

		public static final Uri CONTENT_URI = Uri.parse("content://"
				+ Network_Provider.AUTHORITY + "/network");
		public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.aware.network";
		public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.aware.network";

		public static final String _ID = "_id";
		public static final String TIMESTAMP = "timestamp";
		public static final String DEVICE_ID = "device_id";
		public static final String TYPE = "network_type";
		public static final String SUBTYPE = "network_subtype";
		public static final String STATE = "network_state";
	}

	public static String DATABASE_NAME = "network.db";

	public static final String[] DATABASE_TABLES = { "network" };
	public static final String[] TABLES_FIELDS = {
	// network
	Network_Data._ID + " integer primary key autoincrement,"
			+ Network_Data.TIMESTAMP + " real default 0,"
			+ Network_Data.DEVICE_ID + " text default ''," + Network_Data.TYPE
			+ " integer default 0," + Network_Data.SUBTYPE
			+ " text default ''," + Network_Data.STATE + " integer default 0" };

	private static UriMatcher sUriMatcher = null;
	private static HashMap<String, String> networkProjectionMap = null;
	private static DatabaseHelper databaseHelper = null;
	private static SQLiteDatabase database = null;

	private boolean initializeDB() {
        if (databaseHelper == null) {
            databaseHelper = new DatabaseHelper( getContext(), DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS );
        }
        if( databaseHelper != null && ( database == null || ! database.isOpen() )) {
            database = databaseHelper.getWritableDatabase();
        }
        return( database != null && databaseHelper != null);
    }

	/**
	 * Recreates the ContentProvider
	 */
	public static void resetDB( Context c ) {
		Log.d("AWARE", "Resetting " + DATABASE_NAME + "...");

		File db = new File(DATABASE_NAME);
		db.delete();
		databaseHelper = new DatabaseHelper( c, DATABASE_NAME, null, DATABASE_VERSION, DATABASE_TABLES, TABLES_FIELDS);
		if( databaseHelper != null ) {
			database = databaseHelper.getWritableDatabase();
		}
	}
	
	/**
	 * Delete entry from the database
	 */
	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {

	    if( ! initializeDB() ) {
            Log.w(AUTHORITY,"Database unavailable...");
            return 0;
        }

		int count = 0;
		switch (sUriMatcher.match(uri)) {
		case NETWORK:
            database.beginTransaction();
			count = database.delete(DATABASE_TABLES[0], selection,
					selectionArgs);
            database.setTransactionSuccessful();
            database.endTransaction();
			break;
		default:

			throw new IllegalArgumentException("Unknown URI " + uri);
		}

		getContext().getContentResolver().notifyChange(uri, null);
		return count;
	}

	@Override
	public String getType(Uri uri) {
		switch (sUriMatcher.match(uri)) {
		case NETWORK:
			return Network_Data.CONTENT_TYPE;
		case NETWORK_ID:
			return Network_Data.CONTENT_ITEM_TYPE;
		default:
			throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	/**
	 * Insert entry to the database
	 */
	@Override
	public Uri insert(Uri uri, ContentValues initialValues) {

	    if( ! initializeDB() ) {
            Log.w(AUTHORITY,"Database unavailable...");
            return null;
        }

		ContentValues values = (initialValues != null) ? new ContentValues(
				initialValues) : new ContentValues();

		switch (sUriMatcher.match(uri)) {
		case NETWORK:
            database.beginTransaction();
			long network_id = database.insertWithOnConflict(DATABASE_TABLES[0],
					Network_Data.DEVICE_ID, values, SQLiteDatabase.CONFLICT_IGNORE);
            database.setTransactionSuccessful();
            database.endTransaction();
			if (network_id > 0) {
				Uri networkUri = ContentUris.withAppendedId(
						Network_Data.CONTENT_URI, network_id);
				getContext().getContentResolver()
						.notifyChange(networkUri, null);
				return networkUri;
			}
			throw new SQLException("Failed to insert row into " + uri);
		default:

			throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	@Override
	public boolean onCreate() {
	    AUTHORITY = getContext().getPackageName() + ".provider.network";

	    sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Network_Provider.AUTHORITY, DATABASE_TABLES[0],
                NETWORK);
        sUriMatcher.addURI(Network_Provider.AUTHORITY, DATABASE_TABLES[0]
                + "/#", NETWORK_ID);

        networkProjectionMap = new HashMap<String, String>();
        networkProjectionMap.put(Network_Data._ID, Network_Data._ID);
        networkProjectionMap
                .put(Network_Data.TIMESTAMP, Network_Data.TIMESTAMP);
        networkProjectionMap
                .put(Network_Data.DEVICE_ID, Network_Data.DEVICE_ID);
        networkProjectionMap.put(Network_Data.TYPE, Network_Data.TYPE);
        networkProjectionMap.put(Network_Data.SUBTYPE, Network_Data.SUBTYPE);
        networkProjectionMap.put(Network_Data.STATE, Network_Data.STATE);
	    
		return true;
	}

	/**
	 * Query entries from the database
	 */
	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {

	    if( ! initializeDB() ) {
            Log.w(AUTHORITY,"Database unavailable...");
            return null;
        }

		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
		switch (sUriMatcher.match(uri)) {
		case NETWORK:
			qb.setTables(DATABASE_TABLES[0]);
			qb.setProjectionMap(networkProjectionMap);
			break;
		default:

			throw new IllegalArgumentException("Unknown URI " + uri);
		}
		try {
			Cursor c = qb.query(database, projection, selection, selectionArgs,
					null, null, sortOrder);
			c.setNotificationUri(getContext().getContentResolver(), uri);
			return c;
		} catch (IllegalStateException e) {
			if (Aware.DEBUG)
				Log.e(Aware.TAG, e.getMessage());

			return null;
		}
	}

	/**
	 * Update application on the database
	 */
	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
	    if( ! initializeDB() ) {
            Log.w(AUTHORITY,"Database unavailable...");
            return 0;
        }

		int count = 0;
		switch (sUriMatcher.match(uri)) {
		case NETWORK:
            database.beginTransaction();
			count = database.update(DATABASE_TABLES[0], values, selection,
					selectionArgs);
            database.setTransactionSuccessful();
            database.endTransaction();
			break;
		default:

			throw new IllegalArgumentException("Unknown URI " + uri);
		}

		getContext().getContentResolver().notifyChange(uri, null);
		return count;
	}
}