package ca.dungeons.sensordump;

import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

class GPSLogger implements LocationListener {
    private String gpsProvider;
    private int gpsUpdates = 0;
    private double gpsLat, gpsLong, gpsAlt;
    private double gpsLatStart, gpsLongStart;
    private double gpsDistanceMetres, gpsDistanceFeet, gpsTotalDistance;
    private double gpsTotalDistanceKM, gpsTotalDistanceMiles;
    private float gpsAccuracy, gpsBearing;
    private float gpsSpeed, gpsSpeedKMH, gpsSpeedMPH;
    private float gpsAcceleration, gpsAccelerationKMH, gpsAccelerationMPH;
    private float lastSpeed;
    private double lastLat, lastLong;
    boolean gpsHasData = false;

    /**
     * Method to record gps.
     * @param location Current location.
     */
    @Override
    public void onLocationChanged(Location location) {
        Log.e("GPSlogger", "GPS EVENT!" );

        //Log.i("GPSLogger", "LocationChanged.");
        gpsLat = location.getLatitude();
        gpsLong = location.getLongitude();
        gpsAlt = location.getAltitude();
        gpsAccuracy = location.getAccuracy();
        gpsBearing = location.getBearing();
        gpsProvider = location.getProvider();
        gpsSpeed = location.getSpeed();

        // Store the lat/long for the first reading we got
        if( gpsUpdates == 0 ){
            gpsLatStart = gpsLat;
            gpsLongStart = gpsLong;
            lastSpeed = gpsSpeed;
            lastLat = gpsLat;
            lastLong = gpsLong;
        }

        // Metre per second is not ideal. Adding km/hr and mph as well
        gpsSpeedKMH = gpsSpeed * (float) 3.6;
        gpsSpeedMPH = gpsSpeed * (float) 2.23694;

        // Calculate acceleration
        gpsAcceleration = gpsSpeed - lastSpeed;
        gpsAccelerationKMH = gpsAcceleration * (float) 3.6;
        gpsAccelerationMPH = gpsAcceleration * (float) 2.23694;
        lastSpeed = gpsSpeed;

        // Calculate distance
        Location currentLocation = new Location("current");
        currentLocation.setLatitude(gpsLat);
        currentLocation.setLongitude(gpsLong);

        Location lastLocation = new Location("last");
        lastLocation.setLatitude(lastLat);
        lastLocation.setLongitude(lastLong);

        gpsDistanceMetres = currentLocation.distanceTo(lastLocation);
        gpsDistanceFeet = gpsDistanceMetres * 3.28084;
        lastLat = gpsLat;
        lastLong = gpsLong;

        // Track total distance
        gpsTotalDistance += gpsDistanceMetres;
        gpsTotalDistanceKM = gpsTotalDistance * 0.001;
        gpsTotalDistanceMiles = gpsTotalDistance * 0.000621371;

        // We're live!
        gpsHasData = true;


    }


    /** Required over ride. Not used. */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras){}
    /** Required over ride. Not used. */
    @Override
    public void onProviderEnabled(String provider){}
    /** Required over ride. Not used. */
    @Override
    public void onProviderDisabled(String provider){}

    /**
     * Take the passed json object, add the collected gps data.
     * @param passedJson A reference to the SensorThread json file that will be uploaded.
     * @return The json that now included the gps data.
     */
    JSONObject getGpsData( JSONObject passedJson ){

        if( passedJson != null ){
            try{
                // Function to update the joSensorData list.
                passedJson.put("location", "" + gpsLat + "," + gpsLong);
                passedJson.put("start_location", "" + gpsLatStart + "," + gpsLongStart);
                passedJson.put("altitude", gpsAlt);
                passedJson.put("accuracy", gpsAccuracy);
                passedJson.put("bearing", gpsBearing);
                passedJson.put("gps_provider", gpsProvider);
                passedJson.put("speed", gpsSpeed);
                passedJson.put("speed_kmh", gpsSpeedKMH);
                passedJson.put("speed_mph", gpsSpeedMPH);
                passedJson.put("gps_updates", gpsUpdates);
                passedJson.put("acceleration", gpsAcceleration);
                passedJson.put("acceleration_kmh", gpsAccelerationKMH);
                passedJson.put("acceleration_mph", gpsAccelerationMPH);
                passedJson.put("distance_metres", gpsDistanceMetres);
                passedJson.put("distance_feet", gpsDistanceFeet);
                passedJson.put("total_distance_metres", gpsTotalDistance);
                passedJson.put("total_distance_km", gpsTotalDistanceKM);
                passedJson.put("total_distance_miles", gpsTotalDistanceMiles);
            }catch(JSONException JsonEx ){
                Log.e( "GPSLogger", "Error creating Json. " );
                return passedJson;
            }
        }
        return passedJson;
    }


}
