
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
 * AWARE Installations Content Provider Allows you to access all the recorded
 * installations on the database Database is located at the SDCard :
 * /AWARE/installations.db
 * 
 * @author denzil
 * 
 */
public class Installations_Provider extends ContentProvider {

	private static final int DATABASE_VERSION = 3;

	/**
	 * Authority of Installations content provider
	 */
	public static String AUTHORITY = "com.aware.provider.installations";

	// ContentProvider query paths
	private static final int INSTALLATIONS = 1;
	private static final int INSTALLATIONS_ID = 2;

	/**
	 * Installations content representation
	 * 
	 * @author denzil
	 * 
	 */
	public static final class Installations_Data implements BaseColumns {
		private Installations_Data() {
		}

		public static final Uri CONTENT_URI = Uri.parse("content://"
				+ Installations_Provider.AUTHORITY + "/installations");
		public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.aware.applications.installations";
		public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.aware.applications.installations";

		public static final String _ID = "_id";
		public static final String TIMESTAMP = "timestamp";
		public static final String DEVICE_ID = "device_id";
		public static final String PACKAGE_NAME = "package_name";
		public static final String APPLICATION_NAME = "application_name";
		public static final String INSTALLATION_STATUS = "installation_status";
	}

	public static String DATABASE_NAME = "installations.db";

	public static final String[] DATABASE_TABLES = { "installations" };

	public static final String[] TABLES_FIELDS = {
	// installations
	Installations_Data._ID + " integer primary key autoincrement,"
			+ Installations_Data.TIMESTAMP + " real default 0,"
			+ Installations_Data.DEVICE_ID + " text default '',"
			+ Installations_Data.PACKAGE_NAME + " text default '',"
			+ Installations_Data.APPLICATION_NAME + " text default '',"
			+ Installations_Data.INSTALLATION_STATUS + " integer default -1" };

	private static UriMatcher sUriMatcher = null;
	private static HashMap<String, String> installationsMap = null;
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
		case INSTALLATIONS:
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
		case INSTALLATIONS:
			return Installations_Data.CONTENT_TYPE;
		case INSTALLATIONS_ID:
			return Installations_Data.CONTENT_ITEM_TYPE;
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
		case INSTALLATIONS:
            database.beginTransaction();
			long installations_id = database.insertWithOnConflict(DATABASE_TABLES[0],
					Installations_Data.PACKAGE_NAME, values, SQLiteDatabase.CONFLICT_IGNORE);
            database.setTransactionSuccessful();
            database.endTransaction();
			if (installations_id > 0) {
				Uri installationsUri = ContentUris.withAppendedId(
						Installations_Data.CONTENT_URI, installations_id);
				getContext().getContentResolver().notifyChange(
						installationsUri, null);
				return installationsUri;
			}
			throw new SQLException("Failed to insert row into " + uri);
		default:
			throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	@Override
	public boolean onCreate() {
	    AUTHORITY = getContext().getPackageName() + ".provider.installations";

	    sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Installations_Provider.AUTHORITY,
                DATABASE_TABLES[0], INSTALLATIONS);
        sUriMatcher.addURI(Installations_Provider.AUTHORITY, DATABASE_TABLES[0]
                + "/#", INSTALLATIONS_ID);

        installationsMap = new HashMap<String, String>();
        installationsMap.put(Installations_Data._ID, Installations_Data._ID);
        installationsMap.put(Installations_Data.TIMESTAMP,
                Installations_Data.TIMESTAMP);
        installationsMap.put(Installations_Data.DEVICE_ID,
                Installations_Data.DEVICE_ID);
        installationsMap.put(Installations_Data.PACKAGE_NAME,
                Installations_Data.PACKAGE_NAME);
        installationsMap.put(Installations_Data.APPLICATION_NAME,
                Installations_Data.APPLICATION_NAME);
        installationsMap.put(Installations_Data.INSTALLATION_STATUS,
                Installations_Data.INSTALLATION_STATUS);
	    
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
		case INSTALLATIONS:
			qb.setTables(DATABASE_TABLES[0]);
			qb.setProjectionMap(installationsMap);
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
		case INSTALLATIONS:
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