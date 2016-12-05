package ca.dungeons.sensordump;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;

/**
 * Created by pwendorf on 2016-12-03.
 */

public class GPSLogger implements LocationListener {

    public boolean gpsHasData = false;
    public double gpsLat;
    public double gpsLong;
    public double gpsAlt;
    public float gpsAccuracy;
    public float gpsBearing;
    public String gpsProvider;
    public float gpsSpeed;
    public float gpsSpeedKMH;
    public float gpsSpeedMPH;

    @Override
    public void onLocationChanged(Location location) {

        this.gpsLat = location.getLatitude();
        this.gpsLong = location.getLongitude();
        this.gpsAlt = location.getAltitude();
        this.gpsAccuracy = location.getAccuracy();
        this.gpsBearing = location.getBearing();
        this.gpsProvider = location.getProvider();
        this.gpsSpeed = location.getSpeed();

        // Metre per second is not ideal. Adding km/hr and mph as well
        this.gpsSpeedKMH = this.gpsSpeed * (float) 3.6;
        this.gpsSpeedMPH = this.gpsSpeed * (float) 2.23694;

        // We're live!
        this.gpsHasData = true;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }
}
