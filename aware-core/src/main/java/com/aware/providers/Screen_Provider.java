
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
 * AWARE Screen Content Provider Allows you to access all the recorded screen
 * events on the database Database is located at the SDCard : /AWARE/screen.db
 * 
 * @author denzil
 * 
 */
public class Screen_Provider extends ContentProvider {

	public static final int DATABASE_VERSION = 3;

	/**
	 * Authority of Screen content provider
	 */
	public static String AUTHORITY = "com.aware.provider.screen";

	// ContentProvider query paths
	private static final int SCREEN = 1;
	private static final int SCREEN_ID = 2;

	/**
	 * Screen content representation
	 * 
	 * @author denzil
	 * 
	 */
	public static final class Screen_Data implements BaseColumns {
		private Screen_Data() {
		}

		public static final Uri CONTENT_URI = Uri.parse("content://"
				+ Screen_Provider.AUTHORITY + "/screen");
		public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.aware.screen";
		public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.aware.screen";

		public static final String _ID = "_id";
		public static final String TIMESTAMP = "timestamp";
		public static final String DEVICE_ID = "device_id";
		public static final String SCREEN_STATUS = "screen_status";
	}

	public static String DATABASE_NAME = "screen.db";
	public static final String[] DATABASE_TABLES = { "screen" };

	public static final String[] TABLES_FIELDS = {
	// screen
	Screen_Data._ID + " integer primary key autoincrement,"
			+ Screen_Data.TIMESTAMP + " real default 0,"
			+ Screen_Data.DEVICE_ID + " text default '',"
			+ Screen_Data.SCREEN_STATUS + " integer default 0" };

	private static UriMatcher sUriMatcher = null;
	private static HashMap<String, String> screenProjectionMap = null;
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
		case SCREEN:
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
		case SCREEN:
			return Screen_Data.CONTENT_TYPE;
		case SCREEN_ID:
			return Screen_Data.CONTENT_ITEM_TYPE;
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
		case SCREEN:
            database.beginTransaction();
			long screen_id = database.insertWithOnConflict(DATABASE_TABLES[0],
					Screen_Data.DEVICE_ID, values,SQLiteDatabase.CONFLICT_IGNORE);
            database.setTransactionSuccessful();
            database.endTransaction();
			if (screen_id > 0) {
				Uri screenUri = ContentUris.withAppendedId(
						Screen_Data.CONTENT_URI, screen_id);
				getContext().getContentResolver().notifyChange(screenUri, null);
				return screenUri;
			}
			throw new SQLException("Failed to insert row into " + uri);
		default:

			throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	@Override
	public boolean onCreate() {
	    AUTHORITY = getContext().getPackageName() + ".provider.screen";

	    sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Screen_Provider.AUTHORITY, DATABASE_TABLES[0],
                SCREEN);
        sUriMatcher.addURI(Screen_Provider.AUTHORITY,
                DATABASE_TABLES[0] + "/#", SCREEN_ID);

        screenProjectionMap = new HashMap<String, String>();
        screenProjectionMap.put(Screen_Data._ID, Screen_Data._ID);
        screenProjectionMap.put(Screen_Data.TIMESTAMP, Screen_Data.TIMESTAMP);
        screenProjectionMap.put(Screen_Data.DEVICE_ID, Screen_Data.DEVICE_ID);
        screenProjectionMap.put(Screen_Data.SCREEN_STATUS,
                Screen_Data.SCREEN_STATUS);
	    
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
		case SCREEN:
			qb.setTables(DATABASE_TABLES[0]);
			qb.setProjectionMap(screenProjectionMap);
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
		case SCREEN:
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