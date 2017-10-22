package ca.dungeons.sensordump;

import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;

/**
 Elastic Search Indexer.
 Use this thread to upload data to the elastic server. */
class ElasticSearchIndexer implements Runnable {

  private final String logTag = "esIndexer";

  private UploadRunnable uploadRunnable;


  /** The URL we use to post data to the server. */
  URL postUrl;

  /** Used to establish outside connection. */
  private HttpURLConnection httpCon;

  /** A variable to hold the JSON string to be uploaded. */
  String uploadString = "";

  /** Elastic username. */
  String esUsername = "";

  /** Elastic password. */
  String esPassword = "";

  private boolean connected = false;


  /**
   Post content to elastic server.
   @param uploadRunnable -
   */
  ElasticSearchIndexer(UploadRunnable uploadRunnable) {
    this.uploadRunnable = uploadRunnable;
  }

  /**
   This run method is executed upon each index start.
   */
  public void run() {
    if ( !uploadString.equals("") ) {
      index(uploadString);
    }
  }

  /**
   Send JSON data to elastic using POST.
   */
  private void index( String uploadString ) {
    Log.e(logTag+" index", "Index STARTED: "  );

    // POST our documents to elastic.
    try {
      HttpURLConnection httpCon = connect();
      String logTag = "eSearchIndexer";
      if( httpCon != null ){
        DataOutputStream dataOutputStream = new DataOutputStream( httpCon.getOutputStream() );
        dataOutputStream.writeBytes(uploadString);

        // Check status of post operation.
        uploadRunnable.indexSuccess( checkResponseCode( httpCon ) );
      }else{
        Log.e(logTag, "Http connection is null." );
      }
    } catch (IOException IOex) {
      IOex.printStackTrace();
    }

    if (connected) {
      httpCon.disconnect();
      connected = false;
    }

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
          Log.e(logTag, "Index successful");
          return true;
        } else if (responseCode == 400) {
          Log.e(logTag, "Index already exists. Skipping map.  " + httpCon.getResponseMessage());
          return true;
        } else {
          responseMessage = httpCon.getResponseMessage();
          throw new IOException("");
        }
      }catch (IOException ioEx) {
        // Something bad happened.

      }
    }
    Log.e( logTag + " response", String.format("%s%s\n%s%s\n%s",
            "Bad response code: ", responseCode,
            "Response Message: ", responseMessage,
            " request type: "));// End string.
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
      httpCon = (HttpURLConnection) postUrl.openConnection();
      httpCon.setConnectTimeout(2000);
      httpCon.setReadTimeout(2000);
      httpCon.setDoOutput(true);
      httpCon.setDoInput(true);
      httpCon.setRequestMethod("POST");
      httpCon.connect();
      connected = true;
      //Log.e( logTag, httpCon.getURL().toString() );
      return httpCon;
    } catch (MalformedURLException urlEx) {
      Log.e(logTag + " connect.", "Error building URL.");
      urlEx.printStackTrace();
    } catch (IOException IOex) {
      Log.e(logTag + " connect.", "Failed to connect to elastic. " + IOex.getMessage() + "  " + IOex.getCause());
      //IOex.printStackTrace();
    }
    return null;
  }



}
