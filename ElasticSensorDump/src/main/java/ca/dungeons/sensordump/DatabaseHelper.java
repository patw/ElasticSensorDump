package ca.dungeons.sensordump;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONObject;


/**
 * A class to buffer generated data to a dataBase for later upload.
 * @author Gurtok.
 * @version First version of ESD dataBase helper.
 */
class DatabaseHelper extends SQLiteOpenHelper implements Runnable {

  /** Main database name */
  private static final String DATABASE_NAME = "dbStorage";
  /** Database version. */
  private static final int DATABASE_VERSION = 1;
  /** Table name for database. */
  private static final String TABLE_NAME = "StorageTable";
  /** Json data column name. */
  private static final String dataColumn = "JSON";
  /** Since we only have one database, we reference it on creation. */
  private SQLiteDatabase writableDatabase;
  /** Used to keep track of the database row we are working on. */
  private long deleteRowId = 0L;
  /** Used to keep track of supplied database entries in case of upload failure. */
  private int deleteBulkCount = 0;
  /** Used to keep track of the database population. */
  private static long databaseCount = 0L;

  /**
   * Default constructor.
   * Creates a new dataBase if required.
   * @param context Calling method context.
   */
  DatabaseHelper(Context context) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION);
    writableDatabase = getWritableDatabase();
    String query = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (ID INTEGER PRIMARY KEY, JSON TEXT);";
    writableDatabase.execSQL(query);
  }

  @Override
  public void run() {
  }

  /** Get number of database entries. */
  synchronized long databaseEntries() {
    //Log.e( "dbHelper", "database population: " + DatabaseUtils.queryNumEntries(writableDatabase, DatabaseHelper.TABLE_NAME, null) );
    return DatabaseUtils.queryNumEntries(writableDatabase, DatabaseHelper.TABLE_NAME, null);
  }

  /**
   @param db         Existing dataBase.
   @param oldVersion Old version number ID.
   @param newVersion New version number ID.
   */
  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
  }

  /** Required over-ride method. Not currently used. */
  @Override
  public void onCreate(SQLiteDatabase db) {
  }

  /**
   Pack the json object into a content values object for shipping.
   Insert completed json object into dataBase.
   Key will autoIncrement.
   Will also start a background thread to upload the database to Kibana.
   @param jsonObject Passed object to be inserted.
   */
  void JsonToDatabase(JSONObject jsonObject) {
    long checkDB;
    ContentValues values = new ContentValues();
    values.put(dataColumn, jsonObject.toString());
    if( !writableDatabase.isOpen() ){
      writableDatabase = this.getWritableDatabase();
    }
    checkDB = writableDatabase.insert(TABLE_NAME, null, values);
    if (checkDB == -1) {
      Log.e("Failed insert", "Failed insert database.");
    }else{
      databaseCount++;
    }

  }

  /** Delete a list of rows from database. */
  void deleteUploadedIndices() {
    writableDatabase.execSQL("DELETE FROM " + TABLE_NAME + " WHERE ID = " + deleteRowId + " AND " + (deleteRowId + deleteBulkCount) );
    databaseCount = databaseCount - deleteBulkCount;
  }

  /** Query the database for up to 100 rows. Concatenate using the supplied schema.
   Return null if database is empty. */
  String getBulkString( String esIndex, String esType ){
    String bulkOutString = "";
    String separatorString = "{\"index\":{\"_index\":\"" + esIndex + "\",\"_type\":\"" + esType + "\"}}";
    String newLine = "\n";

    Cursor outCursor = writableDatabase.rawQuery("SELECT * FROM " + DatabaseHelper.TABLE_NAME + " ORDER BY ID ASC LIMIT 10", new String[]{});
    deleteBulkCount = outCursor.getCount();
    outCursor.moveToFirst();
    deleteRowId = outCursor.getLong( 0 );
    do{
      bulkOutString = bulkOutString.concat( separatorString + newLine + outCursor.getString(1) );
      outCursor.moveToNext();
    }while( !outCursor.isAfterLast() );
    outCursor.close();
    return bulkOutString;
  }


}
