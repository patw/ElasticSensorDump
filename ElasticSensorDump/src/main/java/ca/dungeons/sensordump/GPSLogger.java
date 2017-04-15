package ca.dungeons.sensordump;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;

class GPSLogger implements LocationListener {
    String gpsProvider;
    int gpsUpdates = 0;
    double gpsLat, gpsLong, gpsAlt;
    double gpsLatStart, gpsLongStart;
    double gpsDistanceMetres, gpsDistanceFeet, gpsTotalDistance;
    double gpsTotalDistanceKM, gpsTotalDistanceMiles;
    float gpsAccuracy, gpsBearing;
    float gpsSpeed, gpsSpeedKMH, gpsSpeedMPH;
    float gpsAcceleration, gpsAccelerationKMH, gpsAccelerationMPH;
    private float lastSpeed;
    private double lastLat, lastLong;
    boolean gpsHasData = false;

    /**
     * Lister for when the GPS reports a location change.
     * @param location Current location.
     */
    @Override
    public void onLocationChanged(Location location) {

        gpsLat = location.getLatitude();
        gpsLong = location.getLongitude();
        gpsAlt = location.getAltitude();
        gpsAccuracy = location.getAccuracy();
        gpsBearing = location.getBearing();
        gpsProvider = location.getProvider();
        gpsSpeed = location.getSpeed();

        // Store the lat/long for the first reading we got
        if (gpsUpdates == 0) {
            gpsLatStart = gpsLat;
            gpsLongStart = gpsLong;
            lastSpeed = gpsSpeed;
            lastLat = gpsLat;
            lastLong = gpsLong;
        }

        gpsUpdates++;
        MainActivity.gpsReadings = gpsUpdates;

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

    /** Reset the gps event counter. */
    void resetGPS(){ gpsUpdates = 0; }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras){}

    @Override
    public void onProviderEnabled(String provider){}

    @Override
    public void onProviderDisabled(String provider){}
}
