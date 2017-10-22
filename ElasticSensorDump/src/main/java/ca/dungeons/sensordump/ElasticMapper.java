package ca.dungeons.sensordump;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;

/** Created by Gurtok on 10/5/2017. */

class ElasticMapper implements Runnable{
  private final String logTag = "Mapper";

  private boolean connected = false;

  private UploadRunnable uploadRunnable;

  /**
   Used to establish outside connection.
   */
  private HttpURLConnection httpCon;

  /** Elastic username. */
  String esUsername = "";

  /** Elastic password. */
  String esPassword = "";

  /** The URL we use to create an index and PUT a mapping schema on it. */
  URL mapUrl;

  /**
   * Mapping constructor:
   * Send mapping to server. Only runs one time.
   */
  ElasticMapper( UploadRunnable uploadRunnable ) {
    this.uploadRunnable = uploadRunnable;
  }

  @Override
  public void run() {
    createMapping();
  }

  /** Create a map and send to elastic for sensor index. */
  private void createMapping() {
    Log.e(logTag + " newMap", "Mapping uploading.");
    httpCon = connect();

    // Connect to elastic using PUT to make elastic understand this is a mapping.
    if ( httpCon != null ) {
      try {
        DataOutputStream dataOutputStream = new DataOutputStream(httpCon.getOutputStream());

        // Lowest json level, contains explicit typing of sensor data.
        JSONObject mappingTypes = new JSONObject();
        // Type "start_location" && "location" using pre-defined typeGeoPoint. ^^
        mappingTypes.put("start_location", new JSONObject().put("type", "geo_point"));
        mappingTypes.put("location", new JSONObject().put("type", "geo_point"));
        // Put the two newly typed fields under properties.
        JSONObject properties = new JSONObject().put("properties", mappingTypes);

        // Mappings should be nested under index_type.
        JSONObject esTypeObj = new JSONObject().put("esd", properties);
        // File this new properties json under _mappings.
        JSONObject mappings = new JSONObject().put("mappings", esTypeObj);

        // Write out to elastic using the passed outputStream that is connected.
        dataOutputStream.writeBytes(mappings.toString());
        uploadRunnable.mappingSuccess( checkResponseCode( httpCon ) );
      } catch (JSONException j) {
        Log.e(logTag + " newMap", "JSON error: " + j.toString());
      } catch (IOException IoEx) {
        Log.e(logTag + " newMap", "Failed to write to outputStreamWriter.");
      }
    } else {
      Log.e(logTag, "Connection is bad.");
    }

    if (connected) {
      httpCon.disconnect();
      connected = false;
    }
    Log.e(logTag, "Finished mapping.");
  }

  /**
   * Helper class to determine if an individual indexing operation was successful.
   **/
  private boolean checkResponseCode( HttpURLConnection httpCon ) {

    String responseMessage = "ResponseCode placeholder.";
    int responseCode = 0;
    if( httpCon != null ){
      try {
        responseCode = httpCon.getResponseCode();
        if (200 <= responseCode && responseCode <= 299) {
          // I expect only the finest of 200s" - Ademara
          return true;
        } else if (responseCode == 400) {
          Log.e(logTag, "Index already exists. Skipping map.");
          return true;
        } else {
          responseMessage = httpCon.getResponseMessage();
          throw new IOException("");
        }
      }catch (IOException ioEx) {
        // Something bad happened.
        Log.e( logTag + " response", String.format("%s%s\n%s%s\n%s",
                "Bad response code: ", responseCode,
                "Response Message: ", responseMessage,
                httpCon.getURL() + " request type: " + httpCon.getRequestMethod())// End string.
        );
      }
    }
    return false;
  }

  /**
   Open a connection with the server.
   */
  private HttpURLConnection connect() {
    connected = false;
    // Send authentication if required
    if (esUsername.length() > 0 && esPassword.length() > 0) {
      Authenticator.setDefault(new Authenticator() {
        protected PasswordAuthentication getPasswordAuthentication() {
          return new PasswordAuthentication(esUsername, esPassword.toCharArray());
        }
      });
    }
    // Establish connection.
    try {
      httpCon = (HttpURLConnection) mapUrl.openConnection();
      httpCon.setConnectTimeout(2000);
      httpCon.setReadTimeout(2000);
      httpCon.setDoOutput(true);
      httpCon.setDoInput(true);
      httpCon.setRequestMethod("PUT");
      httpCon.connect();
      return httpCon;
    } catch (MalformedURLException urlEx) {
      Log.e(logTag + " connect.", "Error building URL.");
      urlEx.printStackTrace();
    } catch (IOException IOex) {
      Log.e(logTag + " connect.", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
      IOex.printStackTrace();
    }
    return null;
  }


}
